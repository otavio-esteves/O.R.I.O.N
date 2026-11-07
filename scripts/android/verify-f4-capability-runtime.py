#!/usr/bin/env python3
"""HOST dedicated-target F4 capability loss/gain and battery-saver qualification."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid

SPEC = importlib.util.spec_from_file_location("orion_scheduled", Path(__file__).with_name("verify-scheduled-reminder-runtime.py"))
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)
SCENARIOS = ("notification_denied", "exact_access", "battery_saver")


def probe_command(mode, fixture, scenario):
    allowed = {"prepare": SCENARIOS, "verify": SCENARIOS,
               "inspect_denied": ("notification_denied",),
               "inspect_approximate": ("exact_access",), "inspect_exact": ("exact_access",),
               "inspect_power_save": ("battery_saver",)}
    if mode not in allowed or scenario not in allowed[mode] or str(uuid.UUID(fixture)) != fixture:
        raise ValueError("Invalid explicit qualification probe.")
    return ["shell", "am", "instrument", "-w", "-e", "class", BASE.TEST_CLASS,
            "-e", "orion_scheduled_mode", mode, "-e", "orion_scheduled_fixture", fixture,
            "-e", "orion_scheduled_scenario", scenario, "-e", "orion_scheduled_delay_seconds", "120",
            "network.orion.test/androidx.test.runner.AndroidJUnitRunner"]


def installed_digest(adb, package):
    if package not in ("network.orion", "network.orion.test"):
        raise ValueError("Unsupported package.")
    path = adb("shell", "pm", "path", package)
    if not re.fullmatch(r"package:/data/app/[A-Za-z0-9_./=+~\-]+/base\.apk", path):
        raise RuntimeError("A single installed base APK is required.")
    digest = adb("shell", "sha256sum", path[8:])
    if not re.fullmatch(r"[0-9a-f]{64}\s+" + re.escape(path[8:]), digest):
        raise RuntimeError("Installed APK identity is unavailable.")
    return digest[:64]


def retained_binding(metadata):
    for key in ("fixtureId", "reminderId", "actionId"):
        if str(uuid.UUID(metadata[key])) != metadata[key]:
            raise ValueError("Noncanonical fixture reference.")
    for key in ("normalizedPayloadHash", "confirmationFingerprint"):
        if not re.fullmatch(r"[0-9a-f]{64}", metadata[key]):
            raise ValueError("Missing original confirmation binding.")
    return metadata


def inspection_passed(output, mode):
    return BASE.instrumentation_passed(output) and ("orion_scheduled_inspection=" + mode.upper()) in output


def wait_for_notification(read, timeout=180, monotonic=time.monotonic, sleep=time.sleep):
    deadline = monotonic() + timeout
    while monotonic() < deadline:
        try:
            keys = read(timeout=min(5, max(0.1, deadline - monotonic())))
        except (subprocess.TimeoutExpired, RuntimeError):
            keys = []
        if len(keys) > 1:
            raise RuntimeError("Duplicate notification identity observed.")
        if keys:
            return keys
        sleep(min(2, max(0, deadline - monotonic())))
    raise RuntimeError("No real notification before verification.")


def restore_policy(adb, exact, power, notifications):
    checks = {}
    operations = []
    if power is not None:
        if power not in ("0", "1"):
            raise ValueError("Invalid original power policy.")
        operations.append(("powerSaverRestore", ["shell", "cmd", "power", "set-mode", power],
                           lambda: adb("shell", "settings", "get", "global", "low_power") == power))
    if exact is not None:
        if exact not in BASE.APP_OP_MODES:
            raise ValueError("Invalid original exact-alarm policy.")
        operations.append(("exactAccessRestore", ["shell", "appops", "set", BASE.PACKAGE, "SCHEDULE_EXACT_ALARM", exact],
                           lambda: BASE.original_appop_mode(adb("shell", "appops", "get", BASE.PACKAGE, "SCHEDULE_EXACT_ALARM"), "SCHEDULE_EXACT_ALARM") == exact))
    if notifications is not None:
        if type(notifications) is not bool:
            raise ValueError("Invalid original notification policy.")
        def notification_restored():
            match = re.search(r"android\.permission\.POST_NOTIFICATIONS: granted=(true|false)",
                              adb("shell", "dumpsys", "package", BASE.PACKAGE))
            return match is not None and (match.group(1) == "true") == notifications
        operations.append(("notificationRestore", ["shell", "pm", "grant" if notifications else "revoke", BASE.PACKAGE,
                                                   "android.permission.POST_NOTIFICATIONS"], notification_restored))
    for name, command, observed in operations:
        try:
            adb(*command)
            checks[name] = "PASS" if observed() else "FAIL"
        except Exception:
            checks[name] = "FAIL"
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--environment", required=True, choices=("PHYSICAL_DEVICE", "ANDROID_EMULATOR"))
    parser.add_argument("--scenario", required=True, choices=SCENARIOS)
    parser.add_argument("--dedicated-target", action="store_true", required=True)
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--app-apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    args = parser.parse_args()
    if not args.adb or args.evidence_dir.exists():
        parser.error("Provide host ADB and a new evidence directory.")
    root = Path(__file__).resolve().parents[2]
    artifacts = {"network.orion": args.app_apk or root / "app/build/outputs/apk/debug/app-debug.apk",
                 "network.orion.test": args.test_apk or root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"}
    if not all(p.is_file() for p in artifacts.values()):
        parser.error("Build and install the matching APK pair first.")
    args.evidence_dir.mkdir(parents=True)
    report = {"result": "FAIL", "gateClosed": False, "scenario": args.scenario,
              "environment": args.environment, "checks": {},
              "artifactSha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in artifacts.values()},
              "sourceSha256": {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (
                  Path(__file__), Path(BASE.__file__), root / "app/src/androidTest/kotlin/network/orion/app/scheduling/OrionScheduledReminderRuntimeTest.kt")}}
    fixture = str(uuid.uuid4())
    report["fixtureId"] = fixture
    original_exact = original_power = original_notifications = None
    metadata = None
    verified = False

    def adb(*command, check=True, timeout=20):
        result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True,
                                text=True, timeout=timeout, check=False)
        if check and result.returncode:
            raise RuntimeError("Host/device command failed.")
        return result.stdout.strip()

    def probe(mode):
        output = adb(*probe_command(mode, fixture, args.scenario), timeout=55)
        (args.evidence_dir / (mode + ".txt")).write_text(output + "\n")
        if not BASE.instrumentation_passed(output):
            raise RuntimeError("Qualification probe failed: " + mode)
        if mode.startswith("inspect_") and not inspection_passed(output, mode):
            raise RuntimeError("Inspection evidence marker is absent.")
        if mode == "verify" and "REAL_NOTIFICATION_AND_COMMITTED_FIRED" not in output:
            raise RuntimeError("Committed delivery evidence is absent.")
        report["checks"][mode] = "PASS"

    def notification(timeout=20):
        return BASE.matching_notification_keys(adb("shell", "cmd", "notification", "list", timeout=timeout), metadata["actionId"])

    try:
        emulated = adb("shell", "getprop", "ro.kernel.qemu") == "1"
        if emulated != (args.environment == "ANDROID_EMULATOR"):
            raise RuntimeError("Device environment mismatch.")
        if adb("shell", "am", "get-current-user") != "0":
            raise RuntimeError("The dedicated fixture requires the primary user.")
        report["apiLevel"] = adb("shell", "getprop", "ro.build.version.sdk")
        report["androidBuild"] = adb("shell", "getprop", "ro.build.fingerprint")
        for package, path in artifacts.items():
            if installed_digest(adb, package) != report["artifactSha256"][path.name]:
                raise RuntimeError("Installed artifact differs from the expected APK.")
        observed_power = adb("shell", "settings", "get", "global", "low_power")
        if observed_power not in ("0", "1"):
            raise RuntimeError("Original power-saver policy is unavailable.")
        original_power = observed_power
        original_exact = BASE.original_appop_mode(adb("shell", "appops", "get", BASE.PACKAGE, "SCHEDULE_EXACT_ALARM"), "SCHEDULE_EXACT_ALARM")
        package = adb("shell", "dumpsys", "package", BASE.PACKAGE)
        match = re.search(r"android\.permission\.POST_NOTIFICATIONS: granted=(true|false)", package)
        if not match:
            raise RuntimeError("Original notification access is unavailable.")
        original_notifications = match.group(1) == "true"
        adb("shell", "pm", "grant", BASE.PACKAGE, "android.permission.POST_NOTIFICATIONS")
        adb("shell", "appops", "set", BASE.PACKAGE, "SCHEDULE_EXACT_ALARM", "allow")
        probe("prepare")
        metadata = retained_binding(json.loads(adb("shell", "run-as", BASE.PACKAGE, "cat", "files/orion_scheduled_runtime_" + fixture + ".json")))
        if metadata["fixtureId"] != fixture or notification():
            raise RuntimeError("Fixture mismatch or premature notification.")
        report["fixture"] = metadata
        print("Original confirmed fixture prepared; applying " + args.scenario, flush=True)
        if args.scenario == "notification_denied":
            adb("shell", "pm", "revoke", BASE.PACKAGE, "android.permission.POST_NOTIFICATIONS")
            remaining = max(0, metadata["scheduledAtEpochMillis"] / 1000 - int(adb("shell", "date", "+%s")))
            deadline = time.monotonic() + remaining + 3
            while time.monotonic() < deadline:
                if notification():
                    raise RuntimeError("Notification appeared while access was denied.")
                time.sleep(min(2, max(0, deadline - time.monotonic())))
            adb(*BASE.allowed_return_command())
            probe("inspect_denied")
            adb("shell", "pm", "grant", BASE.PACKAGE, "android.permission.POST_NOTIFICATIONS")
            adb(*BASE.allowed_return_command())
        elif args.scenario == "exact_access":
            adb("shell", "appops", "set", BASE.PACKAGE, "SCHEDULE_EXACT_ALARM", "ignore")
            adb(*BASE.allowed_return_command())
            probe("inspect_approximate")
            adb("shell", "appops", "set", BASE.PACKAGE, "SCHEDULE_EXACT_ALARM", "allow")
            adb(*BASE.allowed_return_command())
            probe("inspect_exact")
            adb(*BASE.allowed_return_command())
        else:
            adb("shell", "cmd", "power", "set-mode", "1")
            probe("inspect_power_save")
            adb(*BASE.allowed_return_command())
        report["notificationKeysBeforeVerification"] = wait_for_notification(notification)
        report["checks"]["real_notification_before_verification"] = "PASS"
        probe("verify")
        verified = True
        report["result"] = "PASS"
    except Exception as error:
        report["failureType"] = type(error).__name__
        report["failure"] = str(error) if isinstance(error, RuntimeError) else "Bounded qualification command failed."
        raise
    finally:
        if metadata and not verified:
            report["cleanupOnlyVerification"] = True
            try:
                # Exact fixture-only finally cleanup; a failed attempt stays failed.
                probe("verify")
            except Exception:
                pass
        restored = restore_policy(adb, original_exact, original_power, original_notifications)
        report.update(restored)
        if "FAIL" in restored.values():
            report["result"] = "FAIL"
        (args.evidence_dir / "f4-capability-runtime-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
