#!/usr/bin/env python3
"""HOST cleanup of an exited soak recorder; never resumes or approves qualification."""
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import uuid

SPEC = importlib.util.spec_from_file_location("soak_recovery_capabilities", Path(__file__).with_name("verify-f4-capability-runtime.py"))
CAP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAP)
JOURNAL = "soak-recovery-journal.json"
STATE_CLASS = "network.orion.app.scheduling.OrionF4SoakStateTest"


def acquire_lock(directory):
    """Lock the directory inode without changing retained evidence contents."""
    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        os.close(descriptor)
        raise RuntimeError("Another recorder or recovery owns this evidence directory.") from None
    return descriptor


def atomic_json(path, value):
    descriptor, temporary = tempfile.mkstemp(prefix="." + path.name + ".", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w") as stream:
            json.dump(value, stream, indent=2); stream.write("\n")
            stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(directory)
        finally: os.close(directory)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)


def device_identity(adb):
    serial = adb("shell", "getprop", "ro.serialno")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", serial):
        raise ValueError("Device identity is unavailable.")
    return hashlib.sha256(serial.encode()).hexdigest()


def validate_journal(value):
    try:
        if type(value["schemaVersion"]) is not int or value["schemaVersion"] not in (1, 2):
            raise ValueError()
        if value["environment"] not in ("PHYSICAL_DEVICE", "ANDROID_EMULATOR"):
            raise ValueError()
        if type(value["stateCleanupRequired"]) is not bool:
            raise ValueError()
        fixtures = [value["fixtureId"]]
        reminders = value["reminders"]
        if type(reminders) is not list or len(reminders) > 24:
            raise ValueError()
        for item in reminders:
            fixtures.append(item["fixtureId"])
            if type(item["delaySeconds"]) is not int or not 60 <= item["delaySeconds"] <= 86400:
                raise ValueError()
        if len(set(fixtures)) != len(fixtures) or any(str(uuid.UUID(item)) != item for item in fixtures):
            raise ValueError()
        artifacts = value["artifactSha256"]
        if set(artifacts) != {"app-debug.apk", "app-debug-androidTest.apk"}:
            raise ValueError()
        if any(not re.fullmatch(r"[0-9a-f]{64}", item) for item in [value["deviceIdentitySha256"], *artifacts.values()]):
            raise ValueError()
        policies = value["originalPolicies"]
        if set(policies) != {"exact", "power", "notifications", "screenTimeout"}:
            raise ValueError()
        if policies["exact"] not in CAP.BASE.APP_OP_MODES or policies["power"] not in ("0", "1"):
            raise ValueError()
        timeout = policies["screenTimeout"]
        if type(policies["notifications"]) is not bool or not valid_screen_timeout(timeout, value["schemaVersion"]):
            raise ValueError()
    except (KeyError, TypeError, AttributeError, ValueError):
        raise ValueError("Recovery journal is missing or has invalid bounded fields.") from None
    return value


def valid_screen_timeout(value, version=2):
    if value is None: return version == 2
    return type(value) is str and re.fullmatch(r"[0-9]{1," + ("9" if version == 1 else "10") + r"}", value) is not None and int(value) <= 2**31 - 1


def read_journal(path):
    with path.open("rb") as stream:
        content = stream.read(128 * 1024 + 1)
    if len(content) > 128 * 1024:
        raise ValueError("Recovery journal exceeds its bound.")
    return validate_journal(json.loads(content))


def verify_target(adb, journal):
    qemu = adb("shell", "getprop", "ro.kernel.qemu")
    if qemu not in ("", "0", "1") or (qemu == "1") != (journal["environment"] == "ANDROID_EMULATOR"):
        raise RuntimeError("Device environment mismatch.")
    if adb("shell", "am", "get-current-user") != "0":
        raise RuntimeError("Recovery requires the original primary user.")
    if device_identity(adb) != journal["deviceIdentitySha256"]:
        raise RuntimeError("Device identity mismatch.")
    for package, artifact in (("network.orion", "app-debug.apk"), ("network.orion.test", "app-debug-androidTest.apk")):
        if CAP.installed_digest(adb, package) != journal["artifactSha256"][artifact]:
            raise RuntimeError("Installed APK identity mismatch.")


def cleanup_fixtures(adb, journal, evidence):
    checks = {}
    probes = []
    for item in journal["reminders"]:
        fixture = item["fixtureId"]
        probes.append(("reminder-" + fixture,
            ["shell", "am", "instrument", "-w", "-e", "class", CAP.BASE.TEST_CLASS,
             "-e", "orion_scheduled_mode", "cleanup", "-e", "orion_scheduled_fixture", fixture,
             "-e", "orion_scheduled_scenario", "soak", "-e", "orion_scheduled_delay_seconds", str(item["delaySeconds"]),
             "network.orion.test/androidx.test.runner.AndroidJUnitRunner"],
            "orion_scheduled_cleanup=OWNED_FIXTURE_REMOVED"))
    if journal["stateCleanupRequired"]:
        probes.append(("state-" + journal["fixtureId"],
            ["shell", "am", "instrument", "-w", "-e", "class", STATE_CLASS,
             "-e", "orion_soak_mode", "cleanup", "-e", "orion_soak_fixture", journal["fixtureId"],
             "network.orion.test/androidx.test.runner.AndroidJUnitRunner"], "orion_soak_state=CLEANUP"))
    for name, command, marker in probes:
        try:
            output = adb(*command, timeout=55)
            (evidence / (name + ".txt")).write_text(output + "\n")
            checks[name] = "PASS" if CAP.BASE.instrumentation_passed(output) and marker in output else "FAIL"
        except (OSError, RuntimeError, subprocess.TimeoutExpired):
            checks[name] = "FAIL"
    return checks


def restore_policies(adb, policies):
    checks = CAP.restore_policy(adb, policies["exact"], policies["power"], policies["notifications"])
    checks.update(restore_screen_timeout(adb, policies["screenTimeout"]))
    return checks


def restore_screen_timeout(adb, timeout):
    if not valid_screen_timeout(timeout):
        raise ValueError("Invalid screen-timeout restoration value.")
    try:
        if timeout is None: adb("shell", "settings", "delete", "system", "screen_off_timeout")
        else: adb("shell", "settings", "put", "system", "screen_off_timeout", timeout)
        restored = adb("shell", "settings", "get", "system", "screen_off_timeout") == ("null" if timeout is None else timeout)
        return {"screenTimeoutRestore": "PASS" if restored else "FAIL"}
    except Exception:
        return {"screenTimeoutRestore": "FAIL"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--dedicated-target", action="store_true", required=True)
    args = parser.parse_args()
    if not args.adb or not args.run_dir.is_dir() or args.evidence_dir.exists():
        parser.error("Provide host ADB, an existing run directory and a NEW cleanup evidence directory.")
    lock = acquire_lock(args.run_dir)
    try:
        journal = read_journal(args.run_dir / JOURNAL)
        args.evidence_dir.mkdir(parents=True)
        report = {"result": "CLEANUP_INCOMPLETE", "fullF4GateClosed": False, "fullSoakGateClosed": False,
                  "environment": journal["environment"], "instrumentationCheckpointsRestartMain": True,
                  "originalRunUnchanged": True, "qualificationResumed": False,
                  "journalSha256": hashlib.sha256((args.run_dir / JOURNAL).read_bytes()).hexdigest(),
                  "collectorSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
        def adb(*command, timeout=15):
            result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True,
                                    text=True, timeout=timeout, check=False)
            if result.returncode or len(result.stdout.encode()) > 1024 * 1024:
                raise RuntimeError("Bounded recovery command is unavailable.")
            return result.stdout.strip()
        verified = False
        try:
            verify_target(adb, journal)
            verified = True
            report["identityVerified"] = True
            report["fixtureCleanup"] = cleanup_fixtures(adb, journal, args.evidence_dir)
        except (Exception, KeyboardInterrupt) as error:
            report["failureType"] = type(error).__name__
        finally:
            if verified:
                report["policyRestoration"] = restore_policies(adb, journal["originalPolicies"])
                if "failureType" not in report and all(v == "PASS" for v in report["fixtureCleanup"].values()) and all(v == "PASS" for v in report["policyRestoration"].values()):
                    report["result"] = "CLEANUP_COMPLETE_NOT_QUALIFICATION"
            atomic_json(args.evidence_dir / "f4-soak-cleanup-report.json", report)
        print(json.dumps(report, indent=2))
        return 0 if report["result"] == "CLEANUP_COMPLETE_NOT_QUALIFICATION" else 1
    finally:
        os.close(lock)


if __name__ == "__main__": raise SystemExit(main())
