#!/usr/bin/env python3
"""HOST recorder: replace a prior installed APK without uninstalling or clearing data."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import uuid


def instrumentation_command(mode, fixture):
    if mode not in ("prepare", "verify") or str(uuid.UUID(fixture)) != fixture:
        raise ValueError("Invalid upgrade fixture arguments.")
    return ["shell", "am", "instrument", "-w", "-e", "class",
            "network.orion.app.UpgradePersistenceTest", "-e", "orion_upgrade_mode", mode,
            "-e", "orion_upgrade_fixture", fixture,
            "network.orion.test/androidx.test.runner.AndroidJUnitRunner"]


def verify_instrumentation(output):
    if not re.search(r"^OK \(1 test\)\s*$", output, re.MULTILINE) or any(
        marker in output for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED", "Process crashed")
    ):
        raise RuntimeError("Upgrade instrumentation did not pass exactly one test.")


def installed_version(output):
    matches = re.findall(r"^\s*versionCode=(\d+)\b", output, re.MULTILINE)
    if len(matches) != 1:
        raise RuntimeError("Expected exactly one installed package version.")
    return int(matches[0])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--environment", required=True, choices=["ANDROID_EMULATOR", "PHYSICAL_DEVICE"])
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--previous-apk", type=Path, required=True)
    parser.add_argument("--previous-test-apk", type=Path, required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    args = parser.parse_args()
    if not args.adb:
        parser.error("Pass host adb with --adb.")
    root = Path(__file__).resolve().parents[2]
    candidate = root / "app/build/outputs/apk/debug/app-debug.apk"
    candidate_test = root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    artifacts = {"previous": args.previous_apk, "previousTest": args.previous_test_apk,
                 "candidate": candidate, "candidateTest": candidate_test}
    if not all(path.is_file() for path in artifacts.values()):
        parser.error("Build both application versions and their upgrade instrumentation APKs first.")
    hashes = {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in artifacts.items()}
    if hashes["previous"] == hashes["candidate"]:
        parser.error("Previous and candidate APK must be distinct artifacts.")
    args.evidence_dir.mkdir(parents=True, exist_ok=True)
    fixture = str(uuid.uuid4())
    report = {"result": "FAIL", "environment": args.environment, "fixtureId": fixture,
              "artifactSha256": hashes, "checks": {},
              "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
              "workingTreeDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True)),
              "sourceSha256": {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in (
                  Path(__file__).resolve(), root / "app/src/androidTest/kotlin/network/orion/app/UpgradePersistenceTest.kt")}}

    def adb(*command):
        result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True,
                                text=True, timeout=90, check=False)
        if result.returncode:
            raise RuntimeError("ADB failed: " + " ".join(command[:3]) + "\n" + result.stdout + result.stderr)
        return result.stdout.strip()

    def instrument(mode):
        output = adb(*instrumentation_command(mode, fixture))
        (args.evidence_dir / (mode + "-instrumentation.txt")).write_text(output + "\n")
        verify_instrumentation(output)
        return output

    try:
        emulated = adb("shell", "getprop", "ro.kernel.qemu") == "1"
        if emulated != (args.environment == "ANDROID_EMULATOR"):
            raise RuntimeError("Declared environment does not match ro.kernel.qemu.")
        report.update({"androidRuntimeEvidence": "NON_DEFINITIVE" if emulated else "DEFINITIVE",
                       "apiLevel": adb("shell", "getprop", "ro.build.version.sdk"),
                       "androidBuild": adb("shell", "getprop", "ro.build.fingerprint"),
                       "abi": adb("shell", "getprop", "ro.product.cpu.abi"),
                       "pageSize": adb("shell", "getconf", "PAGE_SIZE")})
        for path in (args.previous_apk, args.previous_test_apk):
            adb("install", "-r", "-t", str(path))
        report["previousVersionCode"] = installed_version(adb("shell", "dumpsys", "package", "network.orion"))
        instrument("prepare")
        report["checks"]["seed_prior_installation"] = "PASS"
        for path in (candidate, candidate_test):
            adb("install", "-r", "-t", str(path))
        report["candidateVersionCode"] = installed_version(adb("shell", "dumpsys", "package", "network.orion"))
        if report["candidateVersionCode"] <= report["previousVersionCode"]:
            raise RuntimeError("Candidate must have a strictly higher installed versionCode.")
        output = instrument("verify")
        verified_pid = re.search(r"INSTRUMENTATION_STATUS: orion_upgrade_verified_pid=([1-9][0-9]*)", output)
        if not verified_pid:
            raise RuntimeError("Missing upgrade verification process evidence.")
        report["verifiedPid"] = verified_pid.group(1)
        report["checks"]["encrypted_records_queues_and_keys_survived"] = "PASS"
        report["checks"]["pending_and_unknown_never_replayed"] = "PASS"
        report["result"] = "PASS"
    except Exception as error:
        report["failureType"] = type(error).__name__
        report["failure"] = str(error)
        raise
    finally:
        (args.evidence_dir / "installed-upgrade-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
