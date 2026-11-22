"""Unit coverage for the ownership, access and cleanup checks in the F10 host runner."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("system_assistant_runner",
                                             Path(__file__).with_name("verify-system-assistant.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class AssistantFixture:
    def __init__(self, apk_dir):
        self.apk_dir = apk_dir
        self.installed = {}
        self.current_role = ""
        self.unresolved_holder = ""
        self.calls = []
        self.model_shell_uid = "2000"
        self.avd = "orion-f10"
        self.selected_passes = True

    def run(self, command, **_):
        args = tuple(command[3:])
        self.calls.append(args)
        if args in (("shell", "getprop", "ro.kernel.qemu"), ("shell", "getprop", "sys.boot_completed")):
            return self.completed(command, "1")
        if args == ("emu", "avd", "name"):
            return self.completed(command, f"{self.avd}\nOK")
        if args == ("shell", "am", "get-current-user"):
            return self.completed(command, "0")
        if args == ("shell", "id", "-u"):
            return self.completed(command, self.model_shell_uid)
        if args[:6] == ("shell", "settings", "--user", "0", "get", "secure"):
            return self.completed(command, self.current_role)
        if args == ("shell", "getprop", "ro.build.version.sdk"):
            return self.completed(command, "35")
        if args[:5] == ("shell", "pm", "list", "packages", "-u"):
            return self.completed(command, "\n".join(f"package:{pkg}" for pkg in self.installed))
        if args[0] == "install":
            name = Path(args[-1]).name
            package = next(pkg for pkg, filename in runner.APK_NAMES.items() if filename == name)
            self.installed[package] = name
            return self.completed(command, "Success")
        if args[:3] == ("shell", "pm", "path"):
            return self.completed(command, f"package:/data/app/{args[-1]}/base.apk")
        if args[:2] == ("shell", "sha256sum"):
            filename = self.installed[Path(args[-1]).parent.name]
            digest = hashlib.sha256((self.apk_dir / filename).read_bytes()).hexdigest()
            return self.completed(command, f"{digest}  {args[-1]}")
        if args[:3] == ("shell", "am", "startservice"):
            denial = ("android.permission.BIND_VOICE_INTERACTION" if args[-1].endswith(runner.INTERACTOR)
                      else "not exported from uid 10055")
            return self.completed(command, code=1, stderr="Error: Requires permission " + denial)
        if args[:3] == ("shell", "cmd", "role"):
            if args[3] == "get-role-holders":
                return self.completed(command, self.unresolved_holder or self.current_role.partition("/")[0])
            self.current_role = "network.orion/.voice.service.OrionVoiceInteractionService" if args[3] == "add-role-holder" else ""
            return self.completed(command)
        if args[:3] == ("shell", "am", "instrument"):
            stage = args[args.index("orion_assistant_role_stage") + 1]
            frame = ("INSTRUMENTATION_STATUS: class=" + runner.ROLE_TEST + "\n" +
                     "INSTRUMENTATION_STATUS: test=" + runner.ROLE_METHOD + "\n" +
                     "INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: current=1\n")
            passed = self.selected_passes or stage == "revoked"
            results = "INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)" if passed else "INSTRUMENTATION_STATUS_CODE: -2"
            return self.completed(command, frame + "INSTRUMENTATION_STATUS_CODE: 1\n" + frame + results +
                                  "\nINSTRUMENTATION_CODE: -1")
        if args[0] == "uninstall":
            del self.installed[args[1]]
            return self.completed(command, "Success")
        return self.completed(command)

    @staticmethod
    def completed(command, stdout="", code=0, stderr=""):
        return subprocess.CompletedProcess(command, code, stdout, stderr)


class SystemAssistantHarnessTest(unittest.TestCase):
    def setUp(self):
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.apk_dir = Path(work.name)
        self.report_dir = self.apk_dir / "report"
        sums = []
        for name in runner.APK_NAMES.values():
            (self.apk_dir / name).write_text(name)
            sums.append(f"{hashlib.sha256(name.encode()).hexdigest()}  {name}\n")
        (self.apk_dir / "SHA256SUMS").write_text("".join(sums))
        self.fixture = AssistantFixture(self.apk_dir)

    def verify(self):
        with patch.object(runner.subprocess, "run", side_effect=self.fixture.run), contextlib.redirect_stdout(io.StringIO()):
            code = runner.run(Path("/test/adb"), "emulator-5554", "orion-f10", self.apk_dir, self.report_dir)
        return code, json.loads((self.report_dir / "summary.json").read_text())

    def test_refuses_wrong_emulator_before_installing_or_changing_role(self):
        self.fixture.avd = "different"
        result, report = self.verify()
        self.assertEqual((1, "EMULATOR_OWNERSHIP_MISMATCH"), (result, report["errors"][0]))
        self.assertEqual(len(self.fixture.calls), 2)

    def test_retains_an_existing_assistant(self):
        self.fixture.current_role = "com.android.example/.Assistant"
        result, report = self.verify()
        self.assertEqual((1, "PREEXISTING_ASSISTANT"), (result, report["errors"][0]))
        self.assertEqual(self.fixture.current_role, "com.android.example/.Assistant")
        self.assertFalse(self.fixture.installed)

    def test_retains_a_role_holder_without_an_active_component(self):
        self.fixture.unresolved_holder = "com.android.example"
        result, report = self.verify()
        self.assertEqual((1, "PREEXISTING_ASSISTANT"), (result, report["errors"][0]))
        self.assertFalse(self.fixture.installed)

    def test_actual_role_stages_are_required_and_fixture_is_cleaned(self):
        result, report = self.verify()
        self.assertEqual((0, "PASS"), (result, report["result"]))
        self.assertEqual({"selected", "revoked"}, set(report["instrumentation"]))
        self.assertTrue(all(stage["passed"] == 1 for stage in report["instrumentation"].values()))
        self.assertEqual(self.fixture.current_role, "")
        self.assertFalse(self.fixture.installed)

    def test_failed_active_session_revokes_role_and_removes_owned_apks(self):
        self.fixture.selected_passes = False
        result, report = self.verify()
        self.assertEqual((1, "ROLE_GRANT_OR_SESSION_FAILED"), (result, report["errors"][0]))
        self.assertEqual(self.fixture.current_role, "")
        self.assertFalse(self.fixture.installed)
