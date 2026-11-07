#!/usr/bin/env python3
"""Dedicated HOST target: real reminder delivery after lifecycle or civil-time changes.

Requires explicit --dedicated-target authorization because it installs APK updates,
grants test notification/exact-alarm access, and reboots, Force Stops, or changes
the device clock/timezone, forces Doze, or restricts background execution. It never
clears data or claims Force Stop is automatic
process recovery. Time changes use only the target's exposed alarm shell commands.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid

PACKAGE = "network.orion"
TEST_CLASS = "network.orion.app.scheduling.OrionScheduledReminderRuntimeTest"
SCENARIOS = ("reboot", "force_stop", "time_change", "timezone_change", "doze", "background_restriction")
BACKGROUND_LEVELS = {"unrestricted", "exempted", "adaptive_bucket", "restricted_bucket", "background_restricted", "hibernation"}
APP_OP_MODES = {"allow", "ignore", "deny", "default", "foreground", "errored"}


def instrumentation_command(mode, fixture, scenario=None):
    if mode not in ("prepare", "verify", "resume_doze") or str(uuid.UUID(fixture)) != fixture or (scenario is not None and scenario not in SCENARIOS):
        raise ValueError("Invalid scheduled-runtime fixture arguments.")
    if mode == "resume_doze" and scenario != "doze":
        raise ValueError("Doze re-registration requires its exact scenario.")
    if mode == "prepare" and scenario is None:
        raise ValueError("Preparation requires an explicit scenario.")
    command = ["shell", "am", "instrument", "-w", "-e", "class", TEST_CLASS,
               "-e", "orion_scheduled_mode", mode, "-e", "orion_scheduled_fixture", fixture]
    if scenario is not None:
        command += ["-e", "orion_scheduled_scenario", scenario]
    return command + ["network.orion.test/androidx.test.runner.AndroidJUnitRunner"]


def lifecycle_command(scenario, *, epoch_millis=None, zone_id=None):
    if scenario == "reboot":
        if epoch_millis is not None or zone_id is not None:
            raise ValueError("Reboot does not accept time parameters.")
        return ["reboot"]
    if scenario == "force_stop":
        if epoch_millis is not None or zone_id is not None:
            raise ValueError("Force Stop does not accept time parameters.")
        return ["shell", "am", "force-stop", PACKAGE]
    if scenario == "time_change":
        if type(epoch_millis) is not int or not 0 <= epoch_millis <= 2**63 - 1 or zone_id is not None:
            raise ValueError("Time change requires bounded epoch milliseconds only.")
        return ["shell", "cmd", "alarm", "set-time", str(epoch_millis)]
    if scenario == "timezone_change":
        if not isinstance(zone_id, str) or not re.fullmatch(r"[A-Za-z0-9_+./-]{1,64}", zone_id) or epoch_millis is not None:
            raise ValueError("Timezone change requires a bounded Olson identifier only.")
        return ["shell", "cmd", "alarm", "set-timezone", zone_id]
    if scenario in ("doze", "background_restriction"):
        if epoch_millis is not None or zone_id is not None:
            raise ValueError("Power-state scenarios do not accept time parameters.")
        if scenario == "doze":
            return ["shell", "cmd", "deviceidle", "force-idle", "deep"]
        return ["shell", "appops", "set", PACKAGE, "RUN_ANY_IN_BACKGROUND", "ignore"]
    raise ValueError("Unknown lifecycle scenario.")


def allowed_return_command():
    """Model an explicit launcher return, including its platform startup classification."""
    return ["shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER", "-n", PACKAGE + "/network.orion.app.MainActivity"]


def request_reboot(adb):
    """Transport acknowledgement is optional; only observed_new_boot proves reboot."""
    try:
        adb(*lifecycle_command("reboot"), timeout=5)
        return "RETURNED"
    except (subprocess.TimeoutExpired, RuntimeError):
        # Wireless ADB can disappear without closing its pending reboot request.
        # Keep the strict boot-count and real-notification checks below mandatory.
        return "UNAVAILABLE"


def observed_new_boot(adb, previous_count):
    if adb("shell", "getprop", "sys.boot_completed") != "1":
        return None
    current = adb("shell", "settings", "get", "global", "boot_count")
    if not re.fullmatch(r"[0-9]+", current) or int(current) <= int(previous_count):
        return None
    return int(current)


def background_restriction_level(adb):
    level = adb("shell", "am", "get-bg-restriction-level", "--user", "0", PACKAGE)
    if level not in BACKGROUND_LEVELS:
        raise RuntimeError("Effective background restriction level is unavailable.")
    return level


def original_appop_mode(output, operation):
    modes = "|".join(sorted(APP_OP_MODES))
    explicit = re.search(r"(?m)^\s*" + re.escape(operation) + r":\s*(" + modes + r")\b", output)
    if explicit:
        return explicit.group(1)
    # An absent operation uses opToDefaultMode (often ALLOWED), not MODE_DEFAULT.
    default = re.search(r"(?m)^\s*Default mode:\s*(" + modes + r")\b", output)
    if default:
        return default.group(1)
    raise RuntimeError("Original AppOps policy could not be observed.")


def restore_background_policy(adb, mode, level):
    if mode not in APP_OP_MODES or level not in BACKGROUND_LEVELS:
        raise ValueError("Invalid background restoration policy.")
    adb("shell", "appops", "set", PACKAGE, "RUN_ANY_IN_BACKGROUND", mode, timeout=10)
    # On some builds, restoring AppOps.DEFAULT alone leaves AppRestrictionController
    # at background_restricted. Restore the original effective policy as well.
    adb("shell", "am", "set-bg-restriction-level", "--user", "0", PACKAGE, level, timeout=10)
    if background_restriction_level(adb) != level:
        raise RuntimeError("Effective background restriction was not restored.")


def restored_wall_millis(original_device_millis, elapsed_monotonic_seconds):
    """Preserve device wall-clock progression without assuming host/device clocks agree."""
    if type(original_device_millis) is not int or not 0 <= original_device_millis <= 2**63 - 1:
        raise ValueError("Invalid original device clock.")
    if type(elapsed_monotonic_seconds) not in (int, float) or not math.isfinite(elapsed_monotonic_seconds) or elapsed_monotonic_seconds < 0:
        raise ValueError("Invalid monotonic elapsed duration.")
    if elapsed_monotonic_seconds > (2**63 - 1 - original_device_millis) / 1000:
        raise ValueError("Restored device clock would overflow.")
    result = original_device_millis + int(elapsed_monotonic_seconds * 1000)
    if result > 2**63 - 1:
        raise ValueError("Restored device clock would overflow.")
    return result


def alternate_timezone(original_zone):
    if not isinstance(original_zone, str) or not re.fullmatch(r"[A-Za-z0-9_+./-]{1,64}", original_zone):
        raise ValueError("Invalid original timezone identifier.")
    return "America/Sao_Paulo" if original_zone == "Asia/Tokyo" else "Asia/Tokyo"


def deep_idle_enabled(adb):
    value = adb("shell", "cmd", "deviceidle", "enabled", "deep")
    if value not in ("0", "1"):
        raise RuntimeError("Deep idle enabled state was not observable.")
    return value == "1"


def ensure_deep_idle_enabled(adb, enabled):
    """Configure the dedicated test target without whitelisting or changing alarm policy."""
    if type(enabled) is not bool:
        raise ValueError("Expected an explicit deep-idle configuration state.")
    if deep_idle_enabled(adb) != enabled:
        adb("shell", "cmd", "deviceidle", "enable" if enabled else "disable", "deep")
    if deep_idle_enabled(adb) != enabled:
        raise RuntimeError("Requested deep idle enabled state did not become observable.")


def idle_prerequisites(screen, charging):
    if screen not in ("true", "false") or charging not in ("true", "false"):
        raise RuntimeError("Device idle power prerequisites were not observable.")
    return screen == "false" and charging == "false"


def enter_deep_idle(adb, wait=time.sleep):
    """Bounded public-shell attempts; only an observed IDLE state is evidence."""
    attempts = []
    for attempt in range(3):
        response = adb("shell", "cmd", "deviceidle", "force-idle", "deep", check=False)
        state = adb("shell", "cmd", "deviceidle", "get", "deep")
        attempts.append({"state": state, "response": response[:256]})
        if state == "IDLE":
            return attempts
        if attempt < 2:
            wait(1)
    raise RuntimeError("Deep Doze not reached after bounded attempts; last state=" + state)


def matching_notification_keys(output, action_id):
    if str(uuid.UUID(action_id)) != action_id:
        raise ValueError("Invalid notification action identifier.")
    matches = set()
    for line in output.splitlines():
        key = line.strip()
        fields = key.split("|")
        if len(fields) >= 5 and fields[1:4] == [PACKAGE, "0", action_id]:
            matches.add(key)
    return sorted(matches)


def instrumentation_passed(output):
    return bool(re.search(r"OK \(1 test\)", output)) and not any(
        marker in output for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--environment", required=True, choices=("ANDROID_EMULATOR", "PHYSICAL_DEVICE"))
    parser.add_argument("--scenario", required=True, choices=SCENARIOS)
    parser.add_argument("--dedicated-target", required=True, action="store_true")
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--evidence-dir", type=Path, required=True)
    args = parser.parse_args()
    if not args.adb:
        parser.error("Pass host adb with --adb.")
    root = Path(__file__).resolve().parents[2]
    artifacts = [root / "app/build/outputs/apk/debug/app-debug.apk",
                 root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
    if not all(p.is_file() for p in artifacts):
        parser.error("Build matching app/test APKs in canonical Docker first.")
    args.evidence_dir.mkdir(parents=True, exist_ok=True)
    fixture = str(uuid.uuid4())
    report = {"result": "FAIL", "environment": args.environment, "scenario": args.scenario,
              "fixtureId": fixture, "dedicatedTarget": True, "checks": {},
              "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
              "workingTreeDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True)),
              "artifactSha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in artifacts},
              "sourceSha256": {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [
                  Path(__file__).resolve(), root / "app/src/androidTest/kotlin/network/orion/app/scheduling/OrionScheduledReminderRuntimeTest.kt"]}}
    exact_mode = None
    notification_was_granted = None
    original_device_wall = None
    original_wall_monotonic = None
    original_timezone = None
    background_mode = None
    background_level = None
    doze_started = False
    original_deep_idle_enabled = None

    def adb(*command, check=True, timeout=55):
        result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True,
                                text=True, timeout=timeout, check=False)
        if check and result.returncode:
            raise RuntimeError("ADB failed: " + " ".join(command[:3]))
        return result.stdout.strip()

    def progress(message):
        print(message, flush=True)

    def device_wall_millis():
        value = adb("shell", "date", "+%s", timeout=10)
        if not re.fullmatch(r"[0-9]{1,16}", value):
            raise RuntimeError("Device did not return an epoch timestamp.")
        return restored_wall_millis(int(value) * 1000, 0)

    try:
        emulated = adb("shell", "getprop", "ro.kernel.qemu") == "1"
        if emulated != (args.environment == "ANDROID_EMULATOR"):
            raise RuntimeError("Declared environment does not match the actual target.")
        if adb("shell", "am", "get-current-user") != "0":
            raise RuntimeError("This dedicated fixture supports the primary user only.")
        report.update({"androidRuntimeEvidence": "NON_DEFINITIVE" if emulated else "DEFINITIVE",
                       "apiLevel": adb("shell", "getprop", "ro.build.version.sdk"),
                       "androidBuild": adb("shell", "getprop", "ro.build.fingerprint"),
                       "abi": adb("shell", "getprop", "ro.product.cpu.abi")})
        initial_boot_count = adb("shell", "settings", "get", "global", "boot_count")
        if not re.fullmatch(r"[0-9]+", initial_boot_count):
            raise RuntimeError("Target does not expose a valid boot session count.")
        report["bootCountBefore"] = int(initial_boot_count)
        report["backgroundRestrictionLevelAtStart"] = background_restriction_level(adb)
        for artifact in artifacts:
            adb("install", "-r", "-t", str(artifact))
        package_state = adb("shell", "dumpsys", "package", PACKAGE)
        granted = re.search(r"android\.permission\.POST_NOTIFICATIONS: granted=(true|false)", package_state)
        notification_was_granted = granted.group(1) == "true" if granted else None
        raw_mode = adb("shell", "appops", "get", PACKAGE, "SCHEDULE_EXACT_ALARM")
        exact_mode = original_appop_mode(raw_mode, "SCHEDULE_EXACT_ALARM")
        report["testAccess"] = {"notificationPreviouslyGranted": notification_was_granted, "exactOriginalMode": exact_mode}
        adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        adb("shell", "appops", "set", PACKAGE, "SCHEDULE_EXACT_ALARM", "allow")
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        progress("Preparing a synthetic explicitly confirmed reminder through the production Core.")
        output = adb(*instrumentation_command("prepare", fixture, args.scenario))
        (args.evidence_dir / "prepare-instrumentation.txt").write_text(output + "\n")
        if not instrumentation_passed(output):
            raise RuntimeError("Production reminder preparation failed.")
        metadata = json.loads(adb("shell", "run-as", PACKAGE, "cat", "files/orion_scheduled_runtime_" + fixture + ".json"))
        if metadata.get("fixtureId") != fixture or str(uuid.UUID(metadata["actionId"])) != metadata["actionId"]:
            raise RuntimeError("Invalid prepared fixture metadata.")
        report["fixture"] = metadata
        if matching_notification_keys(adb("shell", "cmd", "notification", "list"), metadata["actionId"]):
            raise RuntimeError("Reminder delivered before the requested lifecycle boundary.")
        report["checks"]["registered_before_lifecycle_boundary"] = "PASS"
        progress("Applying explicit " + args.scenario + " boundary on the dedicated target.")
        if args.scenario == "time_change":
            original_device_wall = device_wall_millis()
            original_wall_monotonic = time.monotonic()
            target = restored_wall_millis(original_device_wall, 120)
            report["wallMutation"] = {"beforeMillis": original_device_wall, "targetMillis": target,
                                      "clockSource": "DEVICE_DATE_SECONDS_PLUS_HOST_MONOTONIC_ELAPSED"}
            try:
                adb(*lifecycle_command(args.scenario, epoch_millis=target))
            except RuntimeError:
                report["timeChangeCapability"] = "UNAVAILABLE_OR_DENIED"
                raise
            observed = device_wall_millis()
            report["wallMutation"]["observedMillis"] = observed
            if not target - 1000 <= observed <= target + 10_000:
                raise RuntimeError("Device clock did not retain the requested forward adjustment.")
            report["timeChangeCapability"] = "OBSERVED"
            report["checks"]["alarm_service_clock_advanced"] = "PASS"
        elif args.scenario == "timezone_change":
            original_timezone = adb("shell", "getprop", "persist.sys.timezone")
            target_zone = alternate_timezone(original_timezone)
            report["timezoneMutation"] = {"before": original_timezone, "target": target_zone}
            try:
                adb(*lifecycle_command(args.scenario, zone_id=target_zone))
            except RuntimeError:
                report["timezoneChangeCapability"] = "UNAVAILABLE_OR_DENIED"
                raise
            observed_zone = adb("shell", "getprop", "persist.sys.timezone")
            report["timezoneMutation"]["observed"] = observed_zone
            if observed_zone != target_zone:
                raise RuntimeError("Device timezone did not change to the explicit target zone.")
            report["timezoneChangeCapability"] = "OBSERVED"
            report["checks"]["alarm_service_timezone_changed"] = "PASS"
        elif args.scenario == "doze":
            if metadata.get("precision") != "EXACT":
                raise RuntimeError("Doze exact-alarm qualification requires an actually registered exact alarm.")
            if adb("shell", "cmd", "deviceidle", "get", "force") != "false":
                raise RuntimeError("Dedicated target already has forced idle state; refusing to overwrite it.")
            original_deep_idle_enabled = deep_idle_enabled(adb)
            report["deepIdleTestConfiguration"] = {"originalEnabled": original_deep_idle_enabled,
                                                    "temporaryEnableRequired": not original_deep_idle_enabled}
            doze_started = True
            try:
                ensure_deep_idle_enabled(adb, True)
                adb("shell", "dumpsys", "battery", "unplug")
                adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
                power_deadline = time.monotonic() + 10
                while True:
                    screen = adb("shell", "cmd", "deviceidle", "get", "screen", timeout=5)
                    charging = adb("shell", "cmd", "deviceidle", "get", "charging", timeout=5)
                    if idle_prerequisites(screen, charging):
                        report["deepIdleTestConfiguration"].update({"screenOn": False, "charging": False})
                        break
                    if time.monotonic() >= power_deadline:
                        raise RuntimeError("Screen-off and unplugged state did not become observable before Doze.")
                    time.sleep(0.1)
                if metadata.get("dozeAlarmTemporarilyRemoved") is not True:
                    raise RuntimeError("Doze preparation did not suspend its derived test alarm.")
                report["deepIdleEntryAttempts"] = enter_deep_idle(adb)
            except RuntimeError:
                report["dozeCapability"] = "UNAVAILABLE_OR_DENIED"
                raise
            idle = adb("shell", "cmd", "deviceidle", "get", "deep")
            report["deepIdleBeforeNotification"] = idle
            if idle != "IDLE":
                raise RuntimeError("Device did not enter observed deep Doze.")
            report["dozeCapability"] = "OBSERVED"
            report["checks"]["deep_idle_observed_before_wait"] = "PASS"
            # Rebuild the original approved artifact AFTER idle entry. Some OEMs
            # refuse idle entry when a near wake-from-idle alarm is already present.
            # This mode only reconciles an existing binding; it cannot confirm one.
            output = adb(*instrumentation_command("resume_doze", fixture, "doze"))
            (args.evidence_dir / "resume-doze-instrumentation.txt").write_text(output + "\n")
            if not instrumentation_passed(output) or "ORIGINAL_DOZE_BINDING_REGISTERED" not in output:
                raise RuntimeError("Original Doze binding was not re-registered.")
            if adb("shell", "cmd", "deviceidle", "get", "deep") != "IDLE":
                raise RuntimeError("Deep Doze did not remain active after re-registration.")
            report["checks"]["original_binding_registered_while_deep_idle"] = "PASS"
        elif args.scenario == "background_restriction":
            background_level = background_restriction_level(adb)
            report["backgroundOriginalLevel"] = background_level
            raw_background = adb("shell", "appops", "get", PACKAGE, "RUN_ANY_IN_BACKGROUND")
            background_mode = original_appop_mode(raw_background, "RUN_ANY_IN_BACKGROUND")
            try:
                adb(*lifecycle_command("background_restriction"))
            except RuntimeError:
                report["backgroundRestrictionCapability"] = "UNAVAILABLE_OR_DENIED"
                raise
            observed = adb("shell", "appops", "get", PACKAGE, "RUN_ANY_IN_BACKGROUND")
            if not re.search(r"RUN_ANY_IN_BACKGROUND:\s*ignore\b", observed):
                raise RuntimeError("Background restriction was not observable in AppOps.")
            adb(*allowed_return_command())
            report["backgroundRestrictionCapability"] = "OBSERVED"
            report["deliveryContext"] = "EXPLICIT_ALLOWED_FOREGROUND_RETURN_WHILE_BACKGROUND_RESTRICTED"
            report["checks"]["background_restricted_and_explicit_foreground_return"] = "PASS"
        elif args.scenario == "reboot":
            report["rebootRequestAcknowledgement"] = request_reboot(adb)
        else:
            adb(*lifecycle_command(args.scenario))
        if args.scenario == "force_stop":
            state = adb("shell", "dumpsys", "package", PACKAGE)
            if "stopped=true" not in state or adb("shell", "pidof", PACKAGE, check=False):
                raise RuntimeError("Force Stop did not produce an observable stopped boundary.")
            report["checks"]["package_stopped_boundary_observed"] = "PASS"
            time.sleep(2)
            if adb("shell", "pidof", PACKAGE, check=False):
                raise RuntimeError("Package unexpectedly restarted while Force Stopped.")
            adb(*allowed_return_command())
            report["checks"]["explicit_allowed_return"] = "PASS"
        deadline = time.monotonic() + 180
        def poll(*command, check=True):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError("Production notification wait deadline reached.")
            return adb(*command, check=check, timeout=min(5, remaining))
        last_progress = 0.0
        automatic_pid = ""
        matching = []
        while time.monotonic() < deadline:
            if time.monotonic() - last_progress >= 10:
                progress("Waiting for production recovery and actual notification before starting verification instrumentation.")
                last_progress = time.monotonic()
            try:
                if poll("get-state", check=False) == "device":
                    if args.scenario == "reboot":
                        boot_count = observed_new_boot(poll, initial_boot_count)
                        if boot_count is None:
                            time.sleep(min(1, max(0, deadline - time.monotonic())))
                            continue
                        report["bootCountAfter"] = boot_count
                    if args.scenario != "doze":
                        poll("shell", "input", "keyevent", "KEYCODE_WAKEUP", check=False)
                        poll("shell", "wm", "dismiss-keyguard", check=False)
                    current_pid = poll("shell", "pidof", PACKAGE, check=False)
                    if re.fullmatch(r"[1-9][0-9]*", current_pid):
                        automatic_pid = current_pid
                    matching = matching_notification_keys(poll("shell", "cmd", "notification", "list"), metadata["actionId"])
                    if automatic_pid and len(matching) == 1:
                        break
            except (subprocess.TimeoutExpired, RuntimeError):
                pass
            time.sleep(min(1, max(0, deadline - time.monotonic())))
        if not automatic_pid or len(matching) != 1:
            raise RuntimeError("No autonomous real notification appeared before verification instrumentation.")
        report["productionPidBeforeVerification"] = automatic_pid
        report["notificationObservedBeforeVerification"] = True
        report["matchingNotificationKeys"] = matching
        report["checks"]["production_notification_before_verification"] = "PASS"
        if args.scenario == "doze":
            idle = adb("shell", "cmd", "deviceidle", "get", "deep")
            report["deepIdleAtNotificationObservation"] = idle
            if idle != "IDLE":
                raise RuntimeError("Notification was not observed while deep Doze remained active.")
            report["checks"]["real_exact_notification_observed_in_deep_idle"] = "PASS"
        if args.scenario == "reboot":
            report["checks"]["automatic_main_start_after_reboot"] = "PASS"
        # Allow the bounded registration proof and Room completion to finish before
        # instrumentation potentially replaces the process; never drive maintenance.
        time.sleep(2)
        output = adb(*instrumentation_command("verify", fixture, args.scenario))
        (args.evidence_dir / "verify-instrumentation.txt").write_text(output + "\n")
        if not instrumentation_passed(output) or "REAL_NOTIFICATION_AND_COMMITTED_FIRED" not in output:
            raise RuntimeError("Production delivery verification failed.")
        report["checks"]["single_committed_delivery_private_notification_and_cleanup"] = "PASS"
        if args.scenario == "force_stop":
            if int(report["apiLevel"]) >= 35:
                detected = re.search(r"INSTRUMENTATION_STATUS: orion_scheduled_force_stop_detected_at=([0-9]+)", output)
                if not detected or int(detected.group(1)) < metadata["preparedAtEpochMillis"] or \
                        int(detected.group(1)) == metadata.get("previousForceStopDetectedAtEpochMillis"):
                    raise RuntimeError("Fresh persisted primary Force Stop detection was not verified.")
                report["lastForceStopDetectedAtMillis"] = int(detected.group(1))
                report["checks"]["was_force_stopped_persisted_primary_signal"] = "PASS"
            else:
                report["primaryForceStopSignal"] = "UNSUPPORTED_BELOW_API35"
        if args.scenario in ("time_change", "timezone_change"):
            report["checks"]["approved_instant_preserved_after_system_time_change"] = "PASS"
        if args.scenario == "background_restriction":
            if "orion_scheduled_background_restricted=true" not in output or "orion_scheduled_permissions_health=DEGRADED" not in output:
                raise RuntimeError("Background restriction was not reflected in Core diagnostics and health.")
            report["checks"]["background_restriction_observable_degradation"] = "PASS"
            report["backgroundContinuousOperationQualified"] = False
        report["result"] = "PASS"
        progress("Lifecycle delivery evidence passed.")
    except Exception as error:
        report["failureType"] = type(error).__name__
        report["failure"] = str(error)
        raise
    finally:
        if doze_started:
            try:
                adb("shell", "cmd", "deviceidle", "unforce", timeout=10)
                adb("shell", "dumpsys", "battery", "reset", timeout=10)
                adb("shell", "input", "keyevent", "KEYCODE_WAKEUP", timeout=10)
                adb("shell", "wm", "dismiss-keyguard", timeout=10)
                if adb("shell", "cmd", "deviceidle", "get", "force", timeout=10) != "false":
                    raise RuntimeError("Forced Doze state was not released.")
                report["dozeAndBatteryRestore"] = "PASS"
            except Exception:
                report["dozeAndBatteryRestore"] = "FAILED"
                report["result"] = "FAIL"
            if original_deep_idle_enabled is not None:
                try:
                    ensure_deep_idle_enabled(adb, original_deep_idle_enabled)
                    report["deepIdleEnabledRestore"] = "PASS"
                except Exception:
                    report["deepIdleEnabledRestore"] = "FAILED"
                    report["result"] = "FAIL"
        if background_mode is not None:
            try:
                restore_background_policy(adb, background_mode, background_level)
                report["backgroundRestrictionRestore"] = "PASS"
                report["backgroundRestrictionLevelRestore"] = "PASS"
            except Exception:
                report["backgroundRestrictionRestore"] = "FAILED"
                report["result"] = "FAIL"
        if original_device_wall is not None and original_wall_monotonic is not None:
            try:
                restored = restored_wall_millis(original_device_wall, time.monotonic() - original_wall_monotonic)
                adb(*lifecycle_command("time_change", epoch_millis=restored), timeout=10)
                observed = device_wall_millis()
                report["wallMutation"].update({"restoreTargetMillis": restored, "restoredObservedMillis": observed})
                if abs(observed - restored) > 5000:
                    raise RuntimeError("Device clock restoration did not remain observable.")
                report["wallClockRestore"] = "PASS"
            except Exception:
                report["wallClockRestore"] = "FAILED"
                report["result"] = "FAIL"
        if original_timezone is not None:
            try:
                adb(*lifecycle_command("timezone_change", zone_id=original_timezone), timeout=10)
                if adb("shell", "getprop", "persist.sys.timezone", timeout=10) != original_timezone:
                    raise RuntimeError("Original timezone was not restored.")
                report["timezoneRestore"] = "PASS"
            except Exception:
                report["timezoneRestore"] = "FAILED"
                report["result"] = "FAIL"
        if exact_mode is not None:
            try:
                adb("shell", "appops", "set", PACKAGE, "SCHEDULE_EXACT_ALARM", exact_mode, timeout=10)
                report["exactAccessRestore"] = "PASS"
            except Exception:
                report["exactAccessRestore"] = "FAILED"
                report["result"] = "FAIL"
        if notification_was_granted is False:
            try:
                adb("shell", "pm", "revoke", PACKAGE, "android.permission.POST_NOTIFICATIONS", timeout=10)
                report["notificationPermissionRestore"] = "PASS"
            except Exception:
                report["notificationPermissionRestore"] = "FAILED"
                report["result"] = "FAIL"
        (args.evidence_dir / "scheduled-reminder-runtime-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    if report["result"] != "PASS":
        raise RuntimeError("Scheduled reminder qualification did not pass all lifecycle/cleanup checks.")


if __name__ == "__main__":
    main()
