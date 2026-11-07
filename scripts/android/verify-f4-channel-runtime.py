#!/usr/bin/env python3
"""HOST channel denial via official Android Settings UI on an explicitly dedicated target."""
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
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location("orion_capabilities", Path(__file__).with_name("verify-f4-capability-runtime.py"))
CAP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAP)
TEST_CLASS = "network.orion.app.scheduling.OrionF4ChannelQualificationTest"


def main_switch(xml):
    root = ET.fromstring(xml)
    nodes = list(root.iter("node"))
    if not any(n.get("text") == "Lembretes" for n in nodes) or not any("O.R.I.O.N." in n.get("text", "") for n in nodes):
        raise ValueError("The exact app/channel Settings page is not observable.")
    groups = [n for n in nodes if n.get("package") == "com.android.settings" and
              n.get("resource-id", "").split("/")[-1] in ("switch_bar", "main_switch_bar")]
    switches = [n for group in groups for n in group.iter("node") if n.get("checkable") == "true"]
    if len(switches) != 1 or switches[0].get("checked") not in ("true", "false"):
        raise ValueError("The native main switch is absent or ambiguous; refusing to tap.")
    bounds = re.fullmatch(r"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]", switches[0].get("bounds", ""))
    if not bounds:
        raise ValueError("Invalid switch bounds.")
    left, top, right, bottom = map(int, bounds.groups())
    if not (0 <= left < right <= 10000 and 0 <= top < bottom <= 10000):
        raise ValueError("Invalid native control coordinates.")
    return switches[0].get("checked") == "true", ((left+right)//2, (top+bottom)//2)


def settings_command():
    return ["shell", "am", "start", "-W", "-a", "android.settings.CHANNEL_NOTIFICATION_SETTINGS",
            "-p", "com.android.settings", "--es", "android.provider.extra.APP_PACKAGE", "network.orion",
            "--es", "android.provider.extra.CHANNEL_ID", "orion_reminders"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--serial", required=True)
    parser.add_argument("--environment", required=True, choices=("PHYSICAL_DEVICE", "ANDROID_EMULATOR"))
    parser.add_argument("--app-apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--dedicated-target", required=True, action="store_true")
    args = parser.parse_args()
    if not args.adb or args.evidence_dir.exists() or not all(p.is_file() for p in (args.app_apk, args.test_apk)):
        parser.error("Host ADB, existing APKs and a new evidence directory are required.")
    args.evidence_dir.mkdir(parents=True)
    report = {"result": "FAIL", "environment": args.environment, "checks": {}, "gateClosed": False,
              "artifactSha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (args.app_apk, args.test_apk)},
              "sourceSha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (
                  Path(__file__), Path(CAP.__file__), Path(__file__).resolve().parents[2] / "app/src/androidTest/kotlin/network/orion/app/scheduling/OrionF4ChannelQualificationTest.kt")}}
    original_permission = None
    original_importance = None
    changed = False
    restored = False
    probe_index = 0
    remote_xml = "/data/local/tmp/orion_f4_channel_" + str(uuid.uuid4()) + ".xml"
    def adb(*command, timeout=20):
        result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, text=True, timeout=timeout)
        if result.returncode: raise RuntimeError("Channel qualification command failed.")
        return result.stdout.strip()
    def read_switch():
        adb("shell", "uiautomator", "dump", "--compressed", remote_xml)
        return main_switch(adb("shell", "cat", remote_xml))
    def set_switch(enabled):
        nonlocal changed
        adb(*settings_command())
        deadline = time.monotonic() + 10
        while True:
            try: current, point = read_switch(); break
            except (ValueError, ET.ParseError):
                if time.monotonic() >= deadline: raise
                time.sleep(0.5)
        if current != enabled:
            changed = True
            adb("shell", "input", "tap", str(point[0]), str(point[1]))
        while read_switch()[0] != enabled:
            if time.monotonic() >= deadline: raise RuntimeError("Native channel switch did not change.")
            time.sleep(0.5)
    def probe(mode):
        nonlocal probe_index
        probe_index += 1
        output = adb("shell", "am", "instrument", "-w", "-e", "class", TEST_CLASS,
                     "-e", "orion_channel_mode", mode, "network.orion.test/androidx.test.runner.AndroidJUnitRunner", timeout=55)
        (args.evidence_dir/(str(probe_index)+"-"+mode+".txt")).write_text(output+"\n")
        match = re.search(r"orion_channel_importance=([0-9]+)", output)
        if not CAP.BASE.instrumentation_passed(output) or not match: raise RuntimeError("Actual channel probe failed.")
        return int(match.group(1))
    try:
        if (adb("shell", "getprop", "ro.kernel.qemu") == "1") != (args.environment == "ANDROID_EMULATOR"):
            raise RuntimeError("Environment mismatch.")
        if adb("shell", "am", "get-current-user") != "0": raise RuntimeError("Primary user is required.")
        for package, path in (("network.orion", args.app_apk), ("network.orion.test", args.test_apk)):
            if CAP.installed_digest(adb, package) != hashlib.sha256(path.read_bytes()).hexdigest(): raise RuntimeError("Installed APK mismatch.")
        policy = adb("shell", "dumpsys", "window", "policy")
        if "mIsShowing=false" not in policy or "interactiveState=INTERACTIVE_STATE_AWAKE" not in policy:
            raise RuntimeError("Unlock the target before using native Settings.")
        match = re.search(r"android\.permission\.POST_NOTIFICATIONS: granted=(true|false)", adb("shell", "dumpsys", "package", "network.orion"))
        if not match: raise RuntimeError("Original notification permission is unavailable.")
        original_permission = match.group(1) == "true"
        adb("shell", "pm", "grant", "network.orion", "android.permission.POST_NOTIFICATIONS")
        original_importance = probe("restored")
        report["originalImportance"] = original_importance
        if original_importance != 3: raise RuntimeError("Only an originally default-importance channel is supported; no channel setting changed.")
        # Restoring is mandatory even if the UI tap changes state then its observation fails.
        set_switch(False)
        report["checks"]["actual_channel_denial_without_invocation"] = "PASS" if probe("disabled") == 0 else "FAIL"
        set_switch(True)
        if probe("restored") != original_importance: raise RuntimeError("Original channel importance was not restored.")
        restored = True
        report["checks"]["actual_channel_restored"] = "PASS"
        if "FAIL" in report["checks"].values(): raise RuntimeError("Channel qualification failed.")
        report["result"] = "PASS"
    except Exception as error:
        report["failureType"] = type(error).__name__
        report["failure"] = str(error) if isinstance(error, (ValueError, RuntimeError)) else "Bounded native UI observation failed."
    finally:
        if changed and not restored:
            try:
                set_switch(True)
                if probe("restored") != original_importance: raise RuntimeError("Channel restore mismatch.")
                report["channelRestore"] = "PASS"
            except Exception: report["channelRestore"] = "FAIL"; report["result"] = "FAIL"
        elif restored: report["channelRestore"] = "PASS"
        restored_policy = CAP.restore_policy(adb, None, None, original_permission)
        report.update(restored_policy)
        if "FAIL" in restored_policy.values(): report["result"] = "FAIL"
        try: adb("shell", "rm", "-f", remote_xml)
        except Exception: pass
        (args.evidence_dir/"f4-channel-runtime-report.json").write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps(report,indent=2))
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
