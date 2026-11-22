#!/usr/bin/env python3
"""Exercise Android's assistant role on an explicitly owned, clean emulator."""
import argparse
import json
from pathlib import Path
import re
import subprocess

from android_apk_pair import APK_NAMES, RunnerFailure, verify_artifacts
from chat_ui_protocol import parse_instrumentation

PACKAGE = "network.orion"
ROLE = "android.app.role.ASSISTANT"
INTERACTOR = "network.orion.voice.service.OrionVoiceInteractionService"
SESSION = "network.orion.voice.service.OrionVoiceInteractionSessionService"
GATEWAY = "network.orion.app.voice.OrionCoreGatewayService"
ROLE_TEST = "network.orion.app.voice.OrionSystemAssistantRoleScenarioTest"
ROLE_METHOD = "platformRoleAndSessionMatchExpectedState"


def run(adb_path, serial, avd_name, apk_dir, report_dir):
    facts = {"environment": "ANDROID_EMULATOR", "definitiveHardwareEvidence": False,
             "roleMechanism": "ANDROID_ROLE_SHELL", "result": "FAIL", "checks": {}, "errors": []}
    installed = []
    role_change_started = False

    def invoke(*args, timeout=45):
        return subprocess.run([str(adb_path), "-s", serial, *args], capture_output=True,
                              text=True, timeout=timeout)

    def adb(*args, timeout=45):
        response = invoke(*args, timeout=timeout)
        if response.returncode != 0:
            raise RunnerFailure("ADB_COMMAND_FAILED")
        return response.stdout.strip()

    def selected_components():
        return [adb("shell", "settings", "--user", "0", "get", "secure", key)
                for key in ("assistant", "voice_interaction_service")]

    def change_role(operation):
        adb("shell", "cmd", "role", operation, "--user", "0", ROLE, PACKAGE)

    def existing_assistant():
        holders = adb("shell", "cmd", "role", "get-role-holders", "--user", "0", ROLE)
        return bool(holders) or any(component not in ("", "null") for component in selected_components())

    def denied(service, pattern):
        outcome = invoke("shell", "am", "startservice", "--user", "0", "-n", f"{PACKAGE}/{service}")
        output = outcome.stdout + "\n" + outcome.stderr
        if outcome.returncode == 0 or not re.search(pattern, output):
            raise RunnerFailure("EXTERNAL_COMPONENT_ACCESS_NOT_DENIED")

    def role_stage(stage):
        response = invoke("shell", "am", "instrument", "-w", "-r", "-e", "class", f"{ROLE_TEST}#{ROLE_METHOD}",
                          "-e", "orion_assistant_role_stage", stage,
                          f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner", timeout=120)
        results = parse_instrumentation(response.stdout + "\n" + response.stderr, response.returncode,
                                        frozenset({(ROLE_TEST, ROLE_METHOD)}))
        facts.setdefault("instrumentation", {})[stage] = results.summary()
        return results.successful

    try:
        if not re.fullmatch(r"emulator-[0-9]{4,5}", serial) or not re.fullmatch(r"[A-Za-z0-9_-]{1,96}", avd_name):
            raise RunnerFailure("INVALID_EMULATOR_SELECTION")
        if adb("shell", "getprop", "ro.kernel.qemu") != "1" or adb("emu", "avd", "name").splitlines() != [avd_name, "OK"]:
            raise RunnerFailure("EMULATOR_OWNERSHIP_MISMATCH")
        if adb("shell", "getprop", "sys.boot_completed") != "1" or adb("shell", "am", "get-current-user") != "0":
            raise RunnerFailure("EMULATOR_USER_NOT_READY")
        if adb("shell", "id", "-u") != "2000":
            raise RunnerFailure("UNPRIVILEGED_SHELL_REQUIRED")
        if existing_assistant():
            raise RunnerFailure("PREEXISTING_ASSISTANT")
        inventory = adb("shell", "pm", "list", "packages", "-u", PACKAGE).splitlines()
        if any(not re.fullmatch(r"package:[A-Za-z0-9_.]+", row) for row in inventory):
            raise RunnerFailure("INVALID_PACKAGE_INVENTORY")
        if any(f"package:{package}" in inventory for package in APK_NAMES):
            raise RunnerFailure("PREEXISTING_PACKAGE")

        hashes = verify_artifacts(apk_dir)
        facts["apkHashes"] = hashes
        api = adb("shell", "getprop", "ro.build.version.sdk")
        if not re.fullmatch(r"[0-9]{2,3}", api):
            raise RunnerFailure("INVALID_RUNTIME_METADATA")
        facts["api"] = int(api)
        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            adb("shell", "settings", "put", "global", setting, "0")
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")

        for package, name in APK_NAMES.items():
            if adb("install", "--no-incremental", "-R", "-t", str(apk_dir / name), timeout=90).splitlines()[-1:] != ["Success"]:
                raise RunnerFailure("INSTALL_FAILED")
            installed.append(package)
            location = adb("shell", "pm", "path", package)
            if not re.fullmatch(r"package:/data/app/[A-Za-z0-9_./=+~\-]+/base\.apk", location):
                raise RunnerFailure("INSTALLED_PACKAGE_MISMATCH")
            digest = adb("shell", "sha256sum", location.removeprefix("package:")).split()
            if len(digest) != 2 or digest[0] != hashes[name]:
                raise RunnerFailure("INSTALLED_HASH_MISMATCH")

        denied(INTERACTOR, r"Requires permission android\.permission\.BIND_VOICE_INTERACTION\b")
        for component in (SESSION, GATEWAY):
            denied(component, r"Requires permission not exported from uid \d+")
        facts["checks"]["external_service_access_denied"] = "PASS"

        adb("shell", "pm", "grant", PACKAGE, "android.permission.RECORD_AUDIO")
        role_change_started = True
        change_role("add-role-holder")
        if not role_stage("selected"):
            raise RunnerFailure("ROLE_GRANT_OR_SESSION_FAILED")
        checks = facts["checks"]
        checks["qualified_android_role_granted"] = "PASS"
        checks["actual_system_session_leaves_ingress_actions_and_microphone_unchanged"] = "PASS"

        change_role("remove-role-holder")
        if not role_stage("revoked"):
            raise RunnerFailure("ROLE_REVOCATION_OR_CORE_FAILED")
        checks["role_revoked_and_core_responds"] = "PASS"
        role_change_started = False
        if existing_assistant():
            raise RunnerFailure("ASSISTANT_SELECTION_NOT_RESTORED")
        facts["result"] = "PASS"
    except RunnerFailure as failure:
        facts["errors"].append(str(failure))
    except subprocess.TimeoutExpired:
        facts["errors"].append("RUNNER_TIMEOUT")
    except (OSError, ValueError, UnicodeError):
        facts["errors"].append("RUNNER_INFRASTRUCTURE_FAILURE")
    finally:
        if role_change_started:
            try:
                change_role("remove-role-holder")
            except (RunnerFailure, subprocess.TimeoutExpired, OSError):
                facts["errors"].append("ROLE_CLEANUP_FAILED")
        for package in reversed(installed):
            try:
                if adb("uninstall", package, timeout=60) != "Success":
                    raise RunnerFailure("UNINSTALL_FAILED")
            except (RunnerFailure, subprocess.TimeoutExpired, OSError):
                facts["errors"].append("PACKAGE_CLEANUP_FAILED")
        if facts["errors"]:
            facts["result"] = "FAIL"
        report_dir.mkdir(parents=True, exist_ok=True)
        (report_dir / "summary.json").write_text(json.dumps(facts, indent=2) + "\n")
    print(json.dumps(facts, sort_keys=True))
    return 0 if facts["result"] == "PASS" else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd-name", required=True)
    parser.add_argument("--apk-dir", type=Path, required=True)
    parser.add_argument("--report-dir", type=Path, required=True)
    args = parser.parse_args()
    raise SystemExit(run(args.adb, args.serial, args.avd_name, args.apk_dir, args.report_dir))
