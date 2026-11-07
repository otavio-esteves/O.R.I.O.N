#!/usr/bin/env python3
"""HOST controlled F4 soak workload; short runs never close the real 24-hour gate."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time
import uuid

SPEC = importlib.util.spec_from_file_location("orion_capabilities", Path(__file__).with_name("verify-f4-capability-runtime.py"))
CAP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAP)
RECOVERY_SPEC = importlib.util.spec_from_file_location("orion_soak_recovery", Path(__file__).with_name("recover-f4-soak-runtime.py"))
RECOVERY = importlib.util.module_from_spec(RECOVERY_SPEC)
RECOVERY_SPEC.loader.exec_module(RECOVERY)
STATE_CLASS = "network.orion.app.scheduling.OrionF4SoakStateTest"


def reminder_delays(duration, interval):
    if type(duration) is not int or type(interval) is not int or not 600 <= duration <= 86400 or not 300 <= interval <= 3600:
        raise ValueError("Duration must be 10m–24h; interval must be 5m–1h.")
    first = 600 if duration >= 86400 else 120
    values = list(range(first, duration, interval))
    if not 1 <= len(values) <= 24:
        raise ValueError("The exact synthetic manifest is bounded to 24 reminders.")
    return values


def workload_command(kind, mode, fixture, delay=None):
    if str(uuid.UUID(fixture)) != fixture:
        raise ValueError("Invalid fixture identity.")
    if kind == "state":
        if mode not in ("prepare", "snapshot", "verify", "cleanup") or delay is not None:
            raise ValueError("Invalid state checkpoint.")
        return ["shell", "am", "instrument", "-w", "-e", "class", STATE_CLASS,
                "-e", "orion_soak_mode", mode, "-e", "orion_soak_fixture", fixture,
                "network.orion.test/androidx.test.runner.AndroidJUnitRunner"]
    if kind != "reminder" or mode not in ("prepare", "verify", "cleanup") or type(delay) is not int or not 60 <= delay <= 86400:
        raise ValueError("Invalid original reminder workload.")
    return ["shell", "am", "instrument", "-w", "-e", "class", CAP.BASE.TEST_CLASS,
            "-e", "orion_scheduled_mode", mode, "-e", "orion_scheduled_fixture", fixture,
            "-e", "orion_scheduled_scenario", "soak", "-e", "orion_scheduled_delay_seconds", str(delay),
            "network.orion.test/androidx.test.runner.AndroidJUnitRunner"]


def system_metrics(thermal, power, own_uid):
    match = re.search(r"(?m)^\s*Thermal Status:\s*([0-6])\s*$", thermal)
    locks = re.search(r"(?m)^\s*Wake Locks: size=([0-9]+)\s*$", power)
    if not match or not locks or type(own_uid) is not int or own_uid <= 0:
        raise ValueError("Technical system snapshot format is unavailable.")
    section = power[locks.end():].split("Suspend Blockers:", 1)[0]
    lines = [line for line in section.splitlines() if "_WAKE_LOCK" in line]
    if len(lines) != int(locks.group(1)):
        raise ValueError("Incomplete wake-lock attribution snapshot.")
    attributed = 0
    for line in lines:
        owner = re.search(r"\buid=([0-9]+)\b", line)
        source = re.search(r"WorkSource\{([^}]+)\}", line)
        if (owner and int(owner.group(1)) == own_uid) or (source and str(own_uid) in re.findall(r"\b[0-9]+\b", source.group(1))):
            attributed += 1
    return {"thermalStatus": int(match.group(1)), "appAttributedWakeLocks": attributed,
            "systemWakeLocks": int(locks.group(1)), "attributionSnapshotOnly": True}


def terminate_main(adb, process):
    if not re.fullmatch(r"[1-9][0-9]{0,9}", process):
        raise ValueError("One exact main PID is required.")
    adb("shell", "run-as", CAP.BASE.PACKAGE, "kill", "-9", process)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--environment", required=True, choices=("PHYSICAL_DEVICE", "ANDROID_EMULATOR"))
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--app-apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--duration-seconds", type=int, default=3600)
    parser.add_argument("--reminder-interval-seconds", type=int, default=600)
    parser.add_argument("--dedicated-target", action="store_true", required=True)
    args = parser.parse_args()
    try:
        delays = reminder_delays(args.duration_seconds, args.reminder_interval_seconds)
    except ValueError as error:
        parser.error(str(error))
    observer = Path(__file__).with_name("record-soak-observation.py")
    if not args.adb or args.evidence_dir.exists() or not all(p.is_file() for p in (args.app_apk, args.test_apk, observer)):
        parser.error("Install the exact APK pair, include the passive observer, and use a new evidence directory.")
    args.evidence_dir.mkdir(parents=True)
    run_lock = RECOVERY.acquire_lock(args.evidence_dir)
    fixture = str(uuid.uuid4())
    report = {"result": "INCOMPLETE", "fullF4GateClosed": False, "fullSoakGateClosed": False,
              "durationRequestedSeconds": args.duration_seconds, "eligibleFor24HourReview": False,
              "fullSoakProfileComplete": False,
              "environment": args.environment, "fixtureId": fixture, "checks": {},
              "instrumentationCheckpointsRestartMain": True,
              "limitations": ["Short runs cannot substitute for 24 hours.",
                              "Passive polling adds overhead; battery/idle qualification remains separate.",
                              "Wake-lock and exit-history snapshots require review; they are not exhaustive traces.",
                              "Boot/unlock, passive backlog histories and actual heartbeat execution need separate qualification evidence."],
              "artifactSha256": {"app-debug.apk": hashlib.sha256(args.app_apk.read_bytes()).hexdigest(),
                                 "app-debug-androidTest.apk": hashlib.sha256(args.test_apk.read_bytes()).hexdigest()},
              "sourceSha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (Path(__file__), observer, Path(CAP.__file__), Path(RECOVERY.__file__))},
              "manifestDelaysSeconds": delays, "reminders": [], "observedNotifications": {}}
    state_prepared = False
    telemetry = telemetry_output = None
    original_power = original_exact = original_notifications = None
    original_timeout = None
    verified = set()

    def persist():
        RECOVERY.atomic_json(args.evidence_dir / "f4-soak-runtime-report.json", report)

    persist()

    def adb(*command, check=True, timeout=15):
        result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True,
                                text=True, timeout=timeout, check=False)
        if check and result.returncode:
            raise RuntimeError("Host/device command failed.")
        return result.stdout.strip()

    def checkpoint(kind, mode, item, delay=None):
        output = adb(*workload_command(kind, mode, item, delay), timeout=55)
        (args.evidence_dir / (kind + "-" + mode + "-" + item + ".txt")).write_text(output + "\n")
        if not CAP.BASE.instrumentation_passed(output):
            raise RuntimeError("Workload checkpoint failed: " + kind + "/" + mode)
        marker = ("orion_soak_state=" + mode.upper() if kind == "state" else
                  "REAL_NOTIFICATION_AND_COMMITTED_FIRED" if mode == "verify" else
                  "orion_scheduled_cleanup=OWNED_FIXTURE_REMOVED" if mode == "cleanup" else None)
        if marker and marker not in output:
            raise RuntimeError("Workload checkpoint marker is absent.")

    try:
        qemu = adb("shell", "getprop", "ro.kernel.qemu")
        if qemu not in ("", "0", "1"):
            raise RuntimeError("Environment property is unavailable.")
        emulated = qemu == "1"
        if emulated != (args.environment == "ANDROID_EMULATOR"):
            raise RuntimeError("Environment mismatch.")
        if adb("shell", "am", "get-current-user") != "0":
            raise RuntimeError("The dedicated workload requires the primary user.")
        report["apiLevel"] = adb("shell", "getprop", "ro.build.version.sdk")
        report["androidBuild"] = adb("shell", "getprop", "ro.build.fingerprint")
        for package, path in (("network.orion", args.app_apk), ("network.orion.test", args.test_apk)):
            if CAP.installed_digest(adb, package) != hashlib.sha256(path.read_bytes()).hexdigest():
                raise RuntimeError("Installed APK identity mismatch.")
        observed_power = adb("shell", "settings", "get", "global", "low_power")
        if observed_power not in ("0", "1"):
            raise RuntimeError("Original power policy is unavailable.")
        original_power = observed_power
        observed_timeout = adb("shell", "settings", "get", "system", "screen_off_timeout")
        if not re.fullmatch(r"[0-9]{1,9}", observed_timeout):
            raise RuntimeError("Original screen timeout is unavailable.")
        original_timeout = observed_timeout
        original_exact = CAP.BASE.original_appop_mode(adb("shell", "appops", "get", "network.orion", "SCHEDULE_EXACT_ALARM"), "SCHEDULE_EXACT_ALARM")
        permissions = adb("shell", "dumpsys", "package", "network.orion")
        match = re.search(r"android\.permission\.POST_NOTIFICATIONS: granted=(true|false)", permissions)
        if not match:
            raise RuntimeError("Original notification policy is unavailable.")
        original_notifications = match.group(1) == "true"
        uid = re.search(r"(?m)^package:network\.orion uid:([0-9]+)$", adb("shell", "cmd", "package", "list", "packages", "-U", "network.orion"))
        if not uid:
            raise RuntimeError("The scoped application UID is unavailable.")
        own_uid = int(uid.group(1))
        report["deviceIdentitySha256"] = RECOVERY.device_identity(adb)
        journal = {"schemaVersion": 1, "environment": args.environment, "fixtureId": fixture,
                   "deviceIdentitySha256": report["deviceIdentitySha256"], "artifactSha256": report["artifactSha256"],
                   "originalPolicies": {"exact": original_exact, "power": original_power,
                                        "notifications": original_notifications, "screenTimeout": original_timeout},
                   "stateCleanupRequired": False, "reminders": []}
        RECOVERY.validate_journal(journal)
        RECOVERY.atomic_json(args.evidence_dir / RECOVERY.JOURNAL, journal)
        persist()
        adb("shell", "pm", "grant", "network.orion", "android.permission.POST_NOTIFICATIONS")
        adb("shell", "appops", "set", "network.orion", "SCHEDULE_EXACT_ALARM", "allow")
        journal["stateCleanupRequired"] = True
        RECOVERY.atomic_json(args.evidence_dir / RECOVERY.JOURNAL, journal)
        state_prepared = True
        checkpoint("state", "prepare", fixture)
        for delay in delays:
            item = str(uuid.uuid4())
            report["reminders"].append({"fixtureId": item, "delaySeconds": delay})
            journal["reminders"].append({"fixtureId": item, "delaySeconds": delay})
            RECOVERY.atomic_json(args.evidence_dir / RECOVERY.JOURNAL, journal)
            persist()
            checkpoint("reminder", "prepare", item, delay)
            metadata = CAP.retained_binding(json.loads(adb("shell", "run-as", "network.orion", "cat", "files/orion_scheduled_runtime_" + item + ".json")))
            report["reminders"][-1]["binding"] = metadata
        checkpoint("state", "snapshot", fixture)
        initial = adb("shell", "run-as", "network.orion", "cat", "files/orion_soak_snapshot_" + fixture + ".json")
        (args.evidence_dir / "initial-state.json").write_text(initial + "\n")
        # Preparation is complete. Restore normal timeout; the unattended phase needs no fresh consent.
        adb("shell", "settings", "put", "system", "screen_off_timeout", "60000")
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        telemetry_output = (args.evidence_dir / "passive-observer.txt").open("w")
        telemetry = subprocess.Popen(["python3", str(observer), "--adb", args.adb, "--serial", args.serial,
                                      "--environment", args.environment, "--apk", str(args.app_apk),
                                      "--duration-seconds", str(args.duration_seconds), "--interval-seconds", "60",
                                      "--evidence-dir", str(args.evidence_dir / "telemetry")],
                                     stdout=telemetry_output, stderr=subprocess.STDOUT)
        start = time.monotonic(); next_metrics = start; injected = False
        with (args.evidence_dir / "system-snapshots.jsonl").open("w") as stream:
            while time.monotonic() - start < args.duration_seconds:
                elapsed = time.monotonic() - start
                output = adb("shell", "cmd", "notification", "list")
                for item in report["reminders"]:
                    action = item["binding"]["actionId"]
                    keys = CAP.BASE.matching_notification_keys(output, action)
                    if len(keys) > 1:
                        raise RuntimeError("Duplicate notification identity observed.")
                    if keys and action not in report["observedNotifications"]:
                        process = adb("shell", "pidof", "network.orion", check=False)
                        report["observedNotifications"][action] = {"elapsedSeconds": round(elapsed, 3), "key": keys[0],
                            "productionPidAtObservation": process if re.fullmatch(r"[1-9][0-9]{0,9}", process) else None}
                if time.monotonic() >= next_metrics:
                    row = system_metrics(adb("shell", "dumpsys", "thermalservice"), adb("shell", "dumpsys", "power"), own_uid)
                    row["elapsedSeconds"] = round(elapsed, 3)
                    stream.write(json.dumps(row) + "\n"); stream.flush()
                    print(str(round(elapsed)) + "s: " + str(len(report["observedNotifications"])) + "/" + str(len(delays)) + " real notifications observed", flush=True)
                    next_metrics = time.monotonic() + 60
                    persist()
                if not injected and elapsed >= args.duration_seconds / 2:
                    process = adb("shell", "pidof", "network.orion", check=False)
                    if process:
                        terminate_main(adb, process)
                        death_deadline = time.monotonic() + 10
                        while adb("shell", "pidof", "network.orion", check=False) == process:
                            if time.monotonic() >= death_deadline:
                                raise RuntimeError("The controlled process death was not observed.")
                            time.sleep(0.1)
                        report["controlledProcessDeath"] = {"method": "RUN_AS_SIGKILL", "beforePid": process,
                                                            "elapsedSeconds": round(elapsed, 3)}
                    else:
                        report["controlledProcessDeath"] = {"result": "NOT_RUNNING_NO_INJECTION"}
                    injected = True
                    persist()
                time.sleep(min(10, max(0, args.duration_seconds - (time.monotonic() - start))))
        report["elapsedWorkloadSeconds"] = round(time.monotonic() - start, 3)
        if len(report["observedNotifications"]) != len(delays):
            raise RuntimeError("Not every approved notification was independently observed.")
        # The observer takes its final sample at the duration boundary. Instrumentation
        # restarts MAIN, so it must not race that sample's PID/memory reads.
        telemetry.wait(timeout=90)
        passive = json.loads((args.evidence_dir / "telemetry/soak-observation-report.json").read_text())
        if passive["result"] != "OBSERVATION_COMPLETE_REVIEW_REQUIRED":
            raise RuntimeError("Passive observation contains gaps or identity mismatches.")
        final_pid = adb("shell", "pidof", "network.orion", check=False)
        # An idle process may have been reclaimed after posting; residency at the end is not required.
        report["productionPidBeforeVerification"] = final_pid if re.fullmatch(r"[1-9][0-9]{0,9}", final_pid) else None
        death = report.get("controlledProcessDeath", {})
        after_death = [item for item in report["observedNotifications"].values()
                       if item["elapsedSeconds"] > death.get("elapsedSeconds", args.duration_seconds)
                       and item["productionPidAtObservation"] not in (None, death.get("beforePid"))]
        report["checks"]["controlledDeathAndAutonomousReturn"] = "PASS" if after_death else "NOT_PROVEN"
        for item in report["reminders"]:
            checkpoint("reminder", "verify", item["fixtureId"], item["delaySeconds"])
            adb("shell", "run-as", "network.orion", "test", "!", "-e", "files/orion_scheduled_runtime_" + item["fixtureId"] + ".json")
            verified.add(item["fixtureId"])
        checkpoint("state", "verify", fixture)
        final = adb("shell", "run-as", "network.orion", "cat", "files/orion_soak_snapshot_" + fixture + ".json")
        (args.evidence_dir / "final-state.json").write_text(final + "\n")
        report["checks"].update(originalBindingsAndSingleCommittedDelivery="PASS", taskTimerAndUncertaintyPreserved="PASS", passiveObservation="PASS")
        report["real24HourDurationObserved"] = args.duration_seconds >= 86400 and report["elapsedWorkloadSeconds"] >= 86400
        report["result"] = "SOAK_RUN_COMPLETE_REVIEW_REQUIRED" if report["real24HourDurationObserved"] else "SHORT_RUN_COMPLETE_REVIEW_REQUIRED"
    except (Exception, KeyboardInterrupt) as error:
        report["failureType"] = type(error).__name__
        report["failure"] = str(error) if isinstance(error, RuntimeError) else "Workload interrupted or bounded command unavailable."
    finally:
        if telemetry and telemetry.poll() is None:
            telemetry.send_signal(signal.SIGINT)
            try: telemetry.wait(timeout=20)
            except subprocess.TimeoutExpired: telemetry.kill(); telemetry.wait()
        if telemetry_output: telemetry_output.close()
        for item in report["reminders"]:
            if item["fixtureId"] not in verified:
                try:
                    checkpoint("reminder", "cleanup", item["fixtureId"], item["delaySeconds"])
                    report.setdefault("reminderFixtureCleanup", {})[item["fixtureId"]] = "PASS"
                except Exception:
                    report.setdefault("reminderFixtureCleanup", {})[item["fixtureId"]] = "FAIL"
                    report["result"] = "INCOMPLETE"
        if state_prepared:
            try: checkpoint("state", "cleanup", fixture); report["stateFixtureCleanup"] = "PASS"
            except Exception: report["stateFixtureCleanup"] = "FAIL"; report["result"] = "INCOMPLETE"
        restored = CAP.restore_policy(adb, original_exact, original_power, original_notifications)
        report.update(restored)
        if "FAIL" in restored.values(): report["result"] = "INCOMPLETE"
        if original_timeout is not None:
            try:
                adb("shell", "settings", "put", "system", "screen_off_timeout", original_timeout)
                if adb("shell", "settings", "get", "system", "screen_off_timeout") != original_timeout:
                    raise RuntimeError("Screen timeout was not restored.")
                report["screenTimeoutRestore"] = "PASS"
            except Exception: report["screenTimeoutRestore"] = "FAIL"; report["result"] = "INCOMPLETE"
        try: persist()
        finally: os.close(run_lock)
    print(json.dumps(report, indent=2))
    return 0 if report["result"] in ("SHORT_RUN_COMPLETE_REVIEW_REQUIRED", "SOAK_RUN_COMPLETE_REVIEW_REQUIRED") else 1


if __name__ == "__main__":
    raise SystemExit(main())
