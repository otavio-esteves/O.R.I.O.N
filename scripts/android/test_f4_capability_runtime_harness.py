#!/usr/bin/env python3
import importlib.util
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("orion_capabilities", Path(__file__).with_name("verify-f4-capability-runtime.py"))
RECORDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RECORDER)


class CapabilityRecorderTest(unittest.TestCase):
    fixture = "00000000-0000-0000-0000-000000000001"

    def test_inspections_are_bound_to_their_owned_scenario(self):
        for mode, scenario in (("inspect_denied", "notification_denied"), ("inspect_approximate", "exact_access"),
                               ("inspect_exact", "exact_access"), ("inspect_power_save", "battery_saver")):
            command = RECORDER.probe_command(mode, self.fixture, scenario)
            self.assertIn(mode, command)
            self.assertIn(scenario, command)
            for wrong in set(RECORDER.SCENARIOS) - {scenario}:
                with self.assertRaises(ValueError): RECORDER.probe_command(mode, self.fixture, wrong)

    def test_unknown_scenarios_modes_and_noncanonical_references_are_rejected(self):
        for mode, fixture, scenario in (("confirm", self.fixture, "exact_access"),
                                         ("prepare", "../unsafe", "battery_saver"),
                                         ("verify", self.fixture, "reboot;unsafe")):
            with self.assertRaises(ValueError): RECORDER.probe_command(mode, fixture, scenario)

    def test_verify_never_prepares_or_manufactures_a_second_confirmation(self):
        command = RECORDER.probe_command("verify", self.fixture, "notification_denied")
        self.assertEqual("verify", command[command.index("orion_scheduled_mode") + 1])
        self.assertNotIn("prepare", command)
        self.assertNotIn("confirm", command)

    def test_apk_lookup_is_closed_and_cannot_inject_shell(self):
        calls = []
        with self.assertRaises(ValueError): RECORDER.installed_digest(lambda *a: calls.append(a), "network.orion;reboot")
        self.assertEqual([], calls)
        for output in ("package:/data/app/a/base.apk;reboot", "package:/data/app/$(reboot)/base.apk",
                       "package:/data/app/a/base.apk\npackage:/data/app/a/split.apk"):
            with self.assertRaises(RuntimeError): RECORDER.installed_digest(lambda *a: output, "network.orion")

    def test_installed_digest_must_bind_to_the_exact_base_path(self):
        path = "/data/app/~~test==/network.orion-abc==/base.apk"
        def adb(*args): return "package:" + path if args[1] == "pm" else "a"*64 + "  " + path
        self.assertEqual("a"*64, RECORDER.installed_digest(adb, "network.orion"))
        with self.assertRaises(RuntimeError):
            RECORDER.installed_digest(lambda *a: "package:" + path if a[1] == "pm" else "a"*64 + "  /other/base.apk", "network.orion")

    def test_missing_confirmation_binding_cannot_be_accepted_as_fixture_evidence(self):
        metadata = {key: self.fixture for key in ("fixtureId", "reminderId", "actionId")}
        metadata.update(normalizedPayloadHash="a"*64, confirmationFingerprint="b"*64)
        self.assertEqual(metadata, RECORDER.retained_binding(metadata))
        for key in ("normalizedPayloadHash", "confirmationFingerprint"):
            wrong = dict(metadata); wrong[key] = "unbound"
            with self.assertRaises(ValueError): RECORDER.retained_binding(wrong)

    def test_restore_preserves_both_original_notification_permission_states(self):
        for granted in (True, False):
            calls = []
            def adb(*command):
                calls.append(command)
                if command[:3] == ("shell", "settings", "get"): return "0"
                if command[:3] == ("shell", "appops", "get"): return "SCHEDULE_EXACT_ALARM: default"
                if command[:3] == ("shell", "dumpsys", "package"): return "android.permission.POST_NOTIFICATIONS: granted=" + str(granted).lower()
                return ""
            self.assertTrue(all(v == "PASS" for v in RECORDER.restore_policy(adb, "default", "0", granted).values()))
            self.assertIn(("shell", "pm", "grant" if granted else "revoke", "network.orion", "android.permission.POST_NOTIFICATIONS"), calls)

    def test_successful_restore_command_is_not_proof_of_restored_policy(self):
        checks = RECORDER.restore_policy(lambda *args: "unavailable", "default", "0", True)
        self.assertTrue(all(v == "FAIL" for v in checks.values()))

    def test_unobserved_original_settings_are_never_overwritten(self):
        calls = []
        self.assertEqual({}, RECORDER.restore_policy(lambda *args: calls.append(args), None, None, None))
        self.assertEqual([], calls)

    def test_inspection_requires_both_successful_instrumentation_and_exact_marker(self):
        good = "INSTRUMENTATION_STATUS: orion_scheduled_inspection=INSPECT_POWER_SAVE\nOK (1 test)"
        self.assertTrue(RECORDER.inspection_passed(good, "inspect_power_save"))
        self.assertFalse(RECORDER.inspection_passed(good, "inspect_denied"))
        self.assertFalse(RECORDER.inspection_passed(good.replace("OK (1 test)", "FAILURES!!!"), "inspect_power_save"))

    def test_full_battery_saver_recorder_flow_with_synthetic_transport(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp); apk = directory/"synthetic.apk"; apk.write_bytes(b"synthetic artifact")
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            state = {"power": "0", "notifications": False, "exact": "default"}
            metadata = {key: self.fixture for key in ("fixtureId", "reminderId", "actionId")}
            metadata.update(normalizedPayloadHash="a"*64, confirmationFingerprint="b"*64)
            def run(command, **kwargs):
                args = command[3:]; output = ""
                if args[:3] == ["shell", "getprop", "ro.kernel.qemu"]: output = "0"
                elif args[:3] == ["shell", "getprop", "ro.build.version.sdk"]: output = "35"
                elif args[:3] == ["shell", "getprop", "ro.build.fingerprint"]: output = "synthetic/build"
                elif args == ["shell", "am", "get-current-user"]: output = "0"
                elif args[:3] == ["shell", "pm", "path"]: output = "package:/data/app/synthetic/base.apk"
                elif args[:2] == ["shell", "sha256sum"]: output = digest + "  /data/app/synthetic/base.apk"
                elif args[:3] == ["shell", "settings", "get"]: output = state["power"]
                elif args[:3] == ["shell", "appops", "get"]: output = "SCHEDULE_EXACT_ALARM: " + state["exact"]
                elif args[:3] == ["shell", "appops", "set"]: state["exact"] = args[-1]
                elif args[:3] == ["shell", "dumpsys", "package"]: output = "android.permission.POST_NOTIFICATIONS: granted=" + str(state["notifications"]).lower()
                elif args[:2] == ["shell", "pm"]: state["notifications"] = args[2] == "grant"
                elif args[:4] == ["shell", "cmd", "power", "set-mode"]: state["power"] = args[-1]
                elif args[:3] == ["shell", "run-as", "network.orion"]: output = json.dumps(metadata)
                elif args[:4] == ["shell", "cmd", "notification", "list"]:
                    output = "0|network.orion|0|" + self.fixture + "|10369" if state["power"] == "1" else ""
                elif args[:3] == ["shell", "am", "instrument"]:
                    mode = args[args.index("orion_scheduled_mode") + 1]
                    output = "OK (1 test)\n"
                    if mode.startswith("inspect_"): output += "INSTRUMENTATION_STATUS: orion_scheduled_inspection=" + mode.upper()
                    if mode == "verify": output += "REAL_NOTIFICATION_AND_COMMITTED_FIRED"
                elif args[:3] != ["shell", "am", "start"]: self.fail("Unexpected synthetic command: " + repr(args))
                return subprocess.CompletedProcess(command, 0, output, "")
            argv = [RECORDER.__file__, "--adb", "synthetic-adb", "--serial", "synthetic", "--scenario", "battery_saver",
                    "--environment", "PHYSICAL_DEVICE", "--dedicated-target", "--evidence-dir", str(directory/"evidence"),
                    "--app-apk", str(apk), "--test-apk", str(apk)]
            with patch("sys.argv", argv), patch.object(RECORDER.subprocess, "run", run), \
                    patch.object(RECORDER.uuid, "uuid4", lambda: RECORDER.uuid.UUID(self.fixture)), patch("sys.stdout", io.StringIO()):
                RECORDER.main()
            report = json.loads((directory/"evidence"/"f4-capability-runtime-report.json").read_text())
            self.assertEqual("PASS", report["result"])
            self.assertEqual("PASS", report["checks"]["real_notification_before_verification"])
            self.assertEqual({"power": "0", "notifications": False, "exact": "default"}, state)

    def test_positive_notification_wait_survives_transport_loss_but_never_infers_delivery(self):
        clock = [0]; responses = iter((subprocess.TimeoutExpired("synthetic", 5), [], ["owned-key"]))
        def read(**kwargs):
            self.assertLessEqual(kwargs["timeout"], 5)
            result = next(responses)
            if isinstance(result, Exception): raise result
            return result
        result = RECORDER.wait_for_notification(read, 10, lambda: clock[0], lambda seconds: clock.__setitem__(0, clock[0]+seconds))
        self.assertEqual(["owned-key"], result)

    def test_positive_notification_wait_cannot_succeed_without_observation(self):
        clock = [0]
        def read(**kwargs): raise subprocess.TimeoutExpired("synthetic", 5)
        with self.assertRaises(RuntimeError):
            RECORDER.wait_for_notification(read, 10, lambda: clock[0], lambda seconds: clock.__setitem__(0, clock[0]+seconds))
        self.assertEqual(10, clock[0])


if __name__ == "__main__":
    unittest.main()
