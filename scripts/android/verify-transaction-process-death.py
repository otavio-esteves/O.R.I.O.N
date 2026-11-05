#!/usr/bin/env python3
"""Host recorder: kill an instrumented debug process during an open Room transaction."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid

SCENARIOS = {
    "generic": ("network.orion.app.TransactionProcessDeathTest",
                "app/src/androidTest/kotlin/network/orion/app/TransactionProcessDeathTest.kt"),
    "task": ("network.orion.app.tasks.OrionTaskTransactionProcessDeathTest",
             "app/src/androidTest/kotlin/network/orion/app/tasks/OrionTaskTransactionProcessDeathTest.kt"),
    "reminder": ("network.orion.app.reminders.OrionReminderTransactionProcessDeathTest",
                 "app/src/androidTest/kotlin/network/orion/app/reminders/OrionReminderTransactionProcessDeathTest.kt"),
}


def terminate_debug_main(adb, process_id):
    """Explicit positive PID under the debug UID; no root or Force Stop fallback."""
    if not re.fullmatch(r"[1-9][0-9]*", process_id):
        raise RuntimeError("Expected exactly one positive main process ID.")
    adb("shell", "run-as", "network.orion", "kill", "-9", process_id)


def instrumentation_command(mode, fixture, scenario="generic"):
    if scenario not in SCENARIOS or mode not in ("prepare", "verify") or str(uuid.UUID(fixture)) != fixture:
        raise ValueError("Invalid transaction fixture arguments.")
    test_class = SCENARIOS[scenario][0]
    return ["shell", "am", "instrument", "-w", "-e", "class",
            test_class, "-e", "orion_transaction_mode", mode,
            "-e", "orion_transaction_fixture", fixture,
            "network.orion.test/androidx.test.runner.AndroidJUnitRunner"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--environment", required=True, choices=["ANDROID_EMULATOR", "PHYSICAL_DEVICE"])
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--scenario", choices=SCENARIOS, default="generic")
    args = parser.parse_args()
    if not args.adb:
        parser.error("Pass host adb with --adb.")
    root = Path(__file__).resolve().parents[2]
    artifacts = [root / "app/build/outputs/apk/debug/app-debug.apk",
                 root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
    if not all(path.is_file() for path in artifacts):
        parser.error("Build :app:assembleDebug :app:assembleDebugAndroidTest in Docker first.")
    args.evidence_dir.mkdir(parents=True, exist_ok=True)
    fixture = str(uuid.uuid4())
    test_source = SCENARIOS[args.scenario][1]
    report = {"result": "FAIL", "environment": args.environment, "fixtureId": fixture, "scenario": args.scenario,
              "terminationMethod": "RUN_AS_SIGKILL", "checks": {},
              "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
              "workingTreeDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True)),
              "sourceSha256": {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                               for path in [root / test_source,
                                            Path(__file__).resolve()]},
              "artifactSha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in artifacts}}
    prepare = None
    prepare_output = None

    def adb(*command, check=True):
        result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True,
                                text=True, timeout=55, check=False)
        if check and result.returncode:
            raise RuntimeError("ADB failed: " + " ".join(command[:3]))
        return result.stdout.strip()

    try:
        emulated = adb("shell", "getprop", "ro.kernel.qemu") == "1"
        if emulated != (args.environment == "ANDROID_EMULATOR"):
            raise RuntimeError("Declared environment does not match ro.kernel.qemu.")
        report.update({"androidRuntimeEvidence": "NON_DEFINITIVE" if emulated else "DEFINITIVE",
                       "apiLevel": adb("shell", "getprop", "ro.build.version.sdk"),
                       "androidBuild": adb("shell", "getprop", "ro.build.fingerprint"),
                       "abi": adb("shell", "getprop", "ro.product.cpu.abi"),
                       "pageSize": adb("shell", "getconf", "PAGE_SIZE")})
        for artifact in artifacts:
            adb("install", "-r", "-t", str(artifact))
        prepare_output = (args.evidence_dir / "prepare-instrumentation.txt").open("w")
        prepare = subprocess.Popen([args.adb, "-s", args.serial, *instrumentation_command("prepare", fixture, args.scenario)],
                                   stdout=prepare_output, stderr=subprocess.STDOUT, text=True)
        checkpoint = "files/orion_transaction_" + fixture + ".ready"
        deadline = time.monotonic() + 40
        before = ""
        while time.monotonic() < deadline:
            before = adb("shell", "run-as", "network.orion", "cat", checkpoint, check=False)
            if re.fullmatch(r"[1-9][0-9]*", before):
                break
            if prepare.poll() is not None:
                raise RuntimeError("Prepare instrumentation exited before the open transaction checkpoint.")
            time.sleep(0.2)
        if not re.fullmatch(r"[1-9][0-9]*", before):
            raise RuntimeError("Open transaction checkpoint timed out.")
        if adb("shell", "pidof", "network.orion") != before:
            raise RuntimeError("Checkpoint PID does not match the current main process.")
        report["beforePid"] = before
        terminate_debug_main(adb, before)
        deadline = time.monotonic() + 10
        while before in adb("shell", "pidof", "network.orion", check=False).split() and time.monotonic() < deadline:
            time.sleep(0.2)
        if before in adb("shell", "pidof", "network.orion", check=False).split():
            raise RuntimeError("The checkpoint process survived SIGKILL.")
        prepare.wait(timeout=10)
        prepare_output.close()
        prepare_output = None
        report["checks"]["kill_during_room_transaction"] = "PASS"
        output = adb(*instrumentation_command("verify", fixture, args.scenario))
        (args.evidence_dir / "verify-instrumentation.txt").write_text(output + "\n")
        if not re.search(r"OK \(1 test\)", output) or any(marker in output for marker in ["FAILURES!!!", "INSTRUMENTATION_FAILED"]):
            raise RuntimeError("Transaction recovery verification failed.")
        verified = re.search(r"INSTRUMENTATION_STATUS: orion_transaction_verified_pid=([1-9][0-9]*)", output)
        after = verified.group(1) if verified else ""
        if not after or after == before:
            raise RuntimeError("Verification did not run in a new main process.")
        report["afterPid"] = after
        report["checks"]["committed_present_uncommitted_absent"] = "PASS"
        report["result"] = "PASS"
    except Exception as error:
        report["failureType"] = type(error).__name__
        report["failure"] = str(error)
        raise
    finally:
        if prepare is not None and prepare.poll() is None:
            # Stop only the host-side ADB recorder. No fallback target termination.
            prepare.terminate()
            try:
                prepare.wait(timeout=5)
            except subprocess.TimeoutExpired:
                prepare.kill()
                prepare.wait(timeout=5)
        if prepare_output is not None:
            prepare_output.close()
        (args.evidence_dir / "transaction-process-death-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
