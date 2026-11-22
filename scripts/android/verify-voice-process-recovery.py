#!/usr/bin/env python3
"""Exercise the debug probe on an explicitly owned emulator. No live microphone or model required."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import uuid


def run(adb_path, serial, avd, apk, report):
    facts = {"environment": "ANDROID_EMULATOR", "result": "FAIL", "checks": {}}
    package = "network.orion"
    test = f"{package}/.app.testing"
    fixture = str(uuid.uuid4())

    def adb(*args, check=True):
        result = subprocess.run([adb_path, "-s", serial, *args], capture_output=True, text=True, timeout=45)
        if check and result.returncode:
            raise RuntimeError("ADB_COMMAND_FAILED")
        return result.stdout.strip()

    def until(predicate, seconds=25):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = predicate()
            if value:
                return value
            time.sleep(0.2)
        raise RuntimeError("CHECKPOINT_TIMEOUT")

    def pid(process):
        found = adb("shell", "pidof", process, check=False)
        return int(found) if re.fullmatch(r"[1-9][0-9]*", found) else None

    def start(name, stage):
        output = adb("shell", "am", "start", "--activity-single-top", "-n", f"{test}.OrionVoice{name}ProbeActivity",
                     "--es", "fixture", fixture, "--es", "stage", stage)
        if "Error" in output:
            raise RuntimeError("PROBE_START_FAILED")

    def marker(role, stage):
        output = adb("shell", "run-as", package, "cat", f"files/orion_voice_probe_{role}_{fixture}", check=False)
        match = re.fullmatch(f"{stage} ([1-9][0-9]*)", output)
        return int(match[1]) if match else None

    try:
        if adb("shell", "getprop", "ro.kernel.qemu") != "1" or adb("emu", "avd", "name").splitlines() != [avd, "OK"]:
            raise RuntimeError("EMULATOR_OWNERSHIP_MISMATCH")
        adb("install", "-r", "-t", str(apk))
        adb("shell", "am", "start", "-n", f"{package}/.app.MainActivity")
        main = until(lambda: pid(package))
        start("Session", "announce")
        session = until(lambda: marker("VOICE_SESSION", "STARTED"))
        start("Control", "announce")
        control = until(lambda: marker("VOICE_CONTROL", "STARTED"))
        if len({main, session, control}) != 3:
            raise RuntimeError("PROCESS_ROLE_MISMATCH")
        facts["checks"]["independent_voice_bootstrap"] = "PASS"
        adb("shell", "run-as", package, "kill", "-9", str(main))
        until(lambda: pid(package) != main)
        if pid(f"{package}:voice_session") != session:
            raise RuntimeError("SESSION_DIED_WITH_MAIN")
        start("Session", "rebind")
        if until(lambda: marker("VOICE_SESSION", "REBOUND"), seconds=35) != session or not pid(package) or pid(package) == main:
            raise RuntimeError("BIND_DID_NOT_RECOVER_MAIN")
        facts["checks"]["session_rebinds_and_completes_query_after_main_sigkill"] = "PASS"
        main = pid(package)

        adb("shell", "run-as", package, "kill", "-9", str(control))
        until(lambda: pid(f"{package}:voice") != control)
        if pid(package) != main or pid(f"{package}:voice_session") != session:
            raise RuntimeError("CONTROL_DEATH_SPREAD")
        facts["checks"]["control_death_preserves_core_and_session"] = "PASS"

        start("Control", "announce")
        control = until(lambda: (new := marker("VOICE_CONTROL", "STARTED")) and new != control and new)
        adb("shell", "run-as", package, "kill", "-9", str(session))
        until(lambda: pid(f"{package}:voice_session") != session)
        if pid(package) != main or pid(f"{package}:voice") != control:
            raise RuntimeError("SESSION_DEATH_SPREAD")
        facts["checks"]["session_death_preserves_core_and_control"] = "PASS"
        facts["result"] = "PASS"
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as failure:
        facts["failure"] = str(failure) if isinstance(failure, RuntimeError) else type(failure).__name__
    finally:
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(facts, indent=2) + "\n")
        print(json.dumps(facts))
    return 0 if facts["result"] == "PASS" else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd-name", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    raise SystemExit(run(args.adb, args.serial, args.avd_name, args.apk, args.report))
