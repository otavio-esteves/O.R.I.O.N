#!/usr/bin/env python3
"""Device-independent contracts for interrupted soak recovery, never runtime evidence."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("soak_recovery", Path(__file__).with_name("recover-f4-soak-runtime.py"))
RECOVERY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RECOVERY)


class SoakRecoveryTest(unittest.TestCase):
    def journal(self):
        return {"schemaVersion": 1, "environment": "PHYSICAL_DEVICE", "fixtureId": "00000000-0000-0000-0000-000000000001",
                "deviceIdentitySha256": "a" * 64,
                "artifactSha256": {"app-debug.apk": "b" * 64, "app-debug-androidTest.apk": "c" * 64},
                "originalPolicies": {"exact": "default", "power": "0", "notifications": False, "screenTimeout": "60000"},
                "stateCleanupRequired": True,
                "reminders": [{"fixtureId": "00000000-0000-0000-0000-000000000002", "delaySeconds": 120}]}

    def test_journal_survives_replacement_without_including_temp_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / RECOVERY.JOURNAL
            RECOVERY.atomic_json(path, self.journal())
            updated = self.journal(); updated["stateCleanupRequired"] = False
            RECOVERY.atomic_json(path, updated)
            self.assertEqual(updated, RECOVERY.read_journal(path))
            self.assertEqual([path], list(Path(tmp).iterdir()))
            self.assertEqual(0o600, path.stat().st_mode & 0o777)

    def test_live_recorder_lock_blocks_recovery(self):
        with tempfile.TemporaryDirectory() as tmp:
            lock = RECOVERY.acquire_lock(Path(tmp))
            try:
                with self.assertRaises(RuntimeError): RECOVERY.acquire_lock(Path(tmp))
            finally: RECOVERY.os.close(lock)
            RECOVERY.os.close(RECOVERY.acquire_lock(Path(tmp)))

    def test_invalid_journal_is_rejected_before_any_device_command(self):
        bad = []
        for key, value in (("fixtureId", "../other"), ("deviceIdentitySha256", "unknown"),
                           ("environment", "HOST"), ("schemaVersion", True), ("stateCleanupRequired", 1)):
            item = self.journal(); item[key] = value; bad.append(item)
        for key, value in (("power", "unknown"), ("notifications", "false"),
                           ("exact", "allow; unsafe"), ("screenTimeout", "60000\nunsafe")):
            item = self.journal(); item["originalPolicies"][key] = value; bad.append(item)
        item = self.journal(); item["reminders"] *= 25; bad.append(item)
        item = self.journal(); item["reminders"] *= 2; bad.append(item)
        item = self.journal(); item["reminders"][0]["delaySeconds"] = True; bad.append(item)
        item = self.journal(); del item["originalPolicies"]; bad.append(item)
        item = self.journal(); item["artifactSha256"] = {}; bad.append(item)
        for item in bad:
            with self.subTest(item=item), self.assertRaises(ValueError): RECOVERY.validate_journal(item)

    def test_cleanup_uses_only_recorded_fixtures_never_prepare_or_verify(self):
        calls = []
        def adb(*command, **kwargs):
            calls.append(command)
            if command[:3] == ("shell", "am", "instrument"):
                mode = "orion_scheduled_cleanup=OWNED_FIXTURE_REMOVED" if "orion_scheduled_mode" in command else "orion_soak_state=CLEANUP"
                return "OK (1 test)\n" + mode
            return ""
        with tempfile.TemporaryDirectory() as tmp:
            report = RECOVERY.cleanup_fixtures(adb, self.journal(), Path(tmp))
        self.assertTrue(all(value == "PASS" for value in report.values()))
        self.assertEqual(2, len([c for c in calls if c[:3] == ("shell", "am", "instrument")]))
        self.assertFalse(any(word in ("prepare", "verify", "confirm", "grant", "install", "clear") for c in calls for word in c))

    def test_one_cleanup_failure_does_not_skip_other_fixtures(self):
        calls = []
        def adb(*command, **kwargs):
            calls.append(command)
            if "orion_scheduled_mode" in command: raise RuntimeError("transport unavailable")
            return "OK (1 test)\norion_soak_state=CLEANUP"
        with tempfile.TemporaryDirectory() as tmp:
            report = RECOVERY.cleanup_fixtures(adb, self.journal(), Path(tmp))
        self.assertIn("FAIL", report.values()); self.assertIn("PASS", report.values())
        self.assertEqual(2, len(calls))

    def test_identity_mismatch_refuses_mutation(self):
        for wrong in ("device", "app", "test", "environment", "user", "unknown_environment"):
            with self.subTest(wrong=wrong):
                calls = []
                def adb(*command, **kwargs):
                    calls.append(command)
                    if command[-1] == "ro.serialno": return "another" if wrong == "device" else "synthetic"
                    if command[-1] == "ro.kernel.qemu": return "1" if wrong == "environment" else "unknown" if wrong == "unknown_environment" else "0"
                    if command[-1] == "get-current-user": return "10" if wrong == "user" else "0"
                    if command[:3] == ("shell", "pm", "path"): return "package:/data/app/synthetic/" + command[-1] + "/base.apk"
                    if command[:2] == ("shell", "sha256sum"):
                        is_test = "/network.orion.test/" in command[-1]
                        digest = ("d" if wrong == ("test" if is_test else "app") else "c" if is_test else "b") * 64
                        return digest + "  " + command[-1]
                    self.fail("Unexpected command: " + repr(command))
                journal = self.journal(); journal["deviceIdentitySha256"] = hashlib.sha256(b"synthetic").hexdigest()
                with self.assertRaises((ValueError, RuntimeError)): RECOVERY.verify_target(adb, journal)
                self.assertFalse(any("instrument" in c for c in calls))

    def test_screen_restoration_is_attempted_after_other_policy_failures(self):
        calls = []
        def adb(*command, **kwargs):
            calls.append(command)
            if command[:3] == ("shell", "settings", "get"): return "60000"
            if command[:3] == ("shell", "settings", "put"): return ""
            raise RuntimeError("unavailable")
        checks = RECOVERY.restore_policies(adb, self.journal()["originalPolicies"])
        self.assertEqual("PASS", checks["screenTimeoutRestore"])
        self.assertEqual(3, list(checks.values()).count("FAIL"))

    def test_v2_preserves_an_absent_screen_setting_without_guessing_its_effective_default(self):
        journal = self.journal(); journal["schemaVersion"] = 2
        journal["originalPolicies"]["screenTimeout"] = None
        RECOVERY.validate_journal(journal)
        calls = []
        def adb(*command, **kwargs):
            calls.append(command)
            return "null" if command[:3] == ("shell", "settings", "get") else ""
        self.assertEqual({"screenTimeoutRestore": "PASS"}, RECOVERY.restore_screen_timeout(adb, None))
        self.assertIn(("shell", "settings", "delete", "system", "screen_off_timeout"), calls)
        self.assertFalse(any("put" in command for command in calls))
        journal["schemaVersion"] = 1
        with self.assertRaises(ValueError): RECOVERY.validate_journal(journal)

    def test_valid_ten_digit_screen_timeout_preserves_the_exact_value_and_rejects_overflow(self):
        journal = self.journal(); journal["schemaVersion"] = 2
        journal["originalPolicies"]["screenTimeout"] = "2147483647"
        RECOVERY.validate_journal(journal)
        for bad in ("2147483648", "99999999999", "-1", 60000, True, "60000\nunsafe"):
            self.assertFalse(RECOVERY.valid_screen_timeout(bad))
        self.assertFalse(RECOVERY.valid_screen_timeout("2147483647", version=1))

    def test_failed_atomic_replacement_retains_the_previous_recovery_plan(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / RECOVERY.JOURNAL
            RECOVERY.atomic_json(path, self.journal())
            previous = path.read_bytes()
            with patch.object(RECOVERY.os, "replace", side_effect=OSError("synthetic full filesystem")):
                with self.assertRaises(OSError): RECOVERY.atomic_json(path, {"incomplete": True})
            self.assertEqual(previous, path.read_bytes())
            self.assertEqual([path], list(Path(tmp).iterdir()))

    def test_cli_cleanup_preserves_original_evidence_and_never_promotes_a_run(self):
        for failed in (False, True):
            with self.subTest(failed=failed), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp); source = root / "run"; source.mkdir(); output = root / "cleanup"
                journal = self.journal(); journal["deviceIdentitySha256"] = hashlib.sha256(b"synthetic").hexdigest()
                RECOVERY.atomic_json(source / RECOVERY.JOURNAL, journal)
                (source / "f4-soak-runtime-report.json").write_text('{"result":"INCOMPLETE"}\n')
                original = {path.name: path.read_bytes() for path in source.iterdir()}
                calls = []
                def run(command, **kwargs):
                    args = command[3:]; calls.append(args); text = ""
                    self.assertLessEqual(kwargs["timeout"], 55)
                    if args[-1] == "ro.serialno": text = "synthetic"
                    elif args[-1] == "ro.kernel.qemu": text = "0"
                    elif args[-1] == "get-current-user": text = "0"
                    elif args[:3] == ["shell", "pm", "path"]: text = "package:/data/app/synthetic/" + args[-1] + "/base.apk"
                    elif args[:2] == ["shell", "sha256sum"]: text = ("c" if "/network.orion.test/" in args[-1] else "b") * 64 + "  " + args[-1]
                    elif args[:3] == ["shell", "am", "instrument"]:
                        self.assertNotIn("prepare", args); self.assertNotIn("verify", args)
                        if "orion_scheduled_mode" in args:
                            text = "FAILURES!!!" if failed else "OK (1 test)\norion_scheduled_cleanup=OWNED_FIXTURE_REMOVED"
                        else: text = "OK (1 test)\norion_soak_state=CLEANUP"
                    elif args[:3] == ["shell", "settings", "get"]: text = "0" if args[-1] == "low_power" else "60000"
                    elif args[:3] == ["shell", "appops", "get"]: text = "SCHEDULE_EXACT_ALARM: default"
                    elif args[:3] == ["shell", "dumpsys", "package"]: text = "android.permission.POST_NOTIFICATIONS: granted=false"
                    elif args[:3] not in (["shell", "appops", "set"], ["shell", "pm", "revoke"], ["shell", "settings", "put"]) and args[:4] != ["shell", "cmd", "power", "set-mode"]:
                        self.fail("Unexpected command: " + repr(args))
                    return subprocess.CompletedProcess(command, 0, text, "")
                argv = [RECOVERY.__file__, "--adb", "synthetic-adb", "--serial", "synthetic", "--dedicated-target",
                        "--run-dir", str(source), "--evidence-dir", str(output)]
                with patch("sys.argv", argv), patch.object(RECOVERY.subprocess, "run", run), patch("sys.stdout", io.StringIO()):
                    self.assertEqual(1 if failed else 0, RECOVERY.main())
                self.assertEqual(original, {path.name: path.read_bytes() for path in source.iterdir()})
                report = json.loads((output / "f4-soak-cleanup-report.json").read_text())
                self.assertEqual("CLEANUP_INCOMPLETE" if failed else "CLEANUP_COMPLETE_NOT_QUALIFICATION", report["result"])
                self.assertFalse(report["fullF4GateClosed"]); self.assertFalse(report["fullSoakGateClosed"])
                self.assertFalse(report["qualificationResumed"])
                self.assertTrue(all(value == "PASS" for value in report["policyRestoration"].values()))

    def test_oversized_or_incomplete_journal_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / RECOVERY.JOURNAL
            for content in (b"{}", b"{" * (128 * 1024 + 1), b"{truncated"):
                path.write_bytes(content)
                with self.assertRaises(ValueError): RECOVERY.read_journal(path)

    def test_real_host_sigkill_keeps_the_recorders_pre_mutation_journal_and_releases_lock(self):
        # Only ADB is synthetic. The recorder, filesystem, process death and locks are real.
        worker = '''
import hashlib, importlib.util, subprocess, sys
from pathlib import Path
spec=importlib.util.spec_from_file_location("killed_recorder",sys.argv[1])
recorder=importlib.util.module_from_spec(spec);spec.loader.exec_module(recorder)
root=Path(sys.argv[2]);digest=hashlib.sha256(b"synthetic artifact").hexdigest()
def adb(command,**kwargs):
    args=command[3:]; output=""
    if args==["shell","pm","grant","network.orion","android.permission.POST_NOTIFICATIONS"]:
        (root/"at-mutation").write_text("ready")
        sys.stdin.read(1)
        raise RuntimeError("Parent must kill this process before this point")
    if args[-1] in ("ro.kernel.qemu","get-current-user","low_power"):output="0"
    elif args[-1]=="ro.serialno":output="synthetic"
    elif args[-1]=="ro.build.version.sdk":output="35"
    elif args[-1]=="ro.build.fingerprint":output="synthetic/build"
    elif args[:3]==["shell","pm","path"]:output="package:/data/app/synthetic/base.apk"
    elif args[:2]==["shell","sha256sum"]:output=digest+"  /data/app/synthetic/base.apk"
    elif args[-1]=="screen_off_timeout":output="17000"
    elif args[:3]==["shell","appops","get"]:output="SCHEDULE_EXACT_ALARM: default"
    elif args[:3]==["shell","dumpsys","package"]:output="android.permission.POST_NOTIFICATIONS: granted=false"
    elif args[:4]==["shell","cmd","package","list"]:output="package:network.orion uid:10369"
    else:raise RuntimeError("Unexpected command")
    return subprocess.CompletedProcess(command,0,output,"")
recorder.subprocess.run=adb
sys.argv=[recorder.__file__,"--adb","synthetic-adb","--serial","synthetic","--environment","PHYSICAL_DEVICE",
          "--dedicated-target","--app-apk",str(root/"app.apk"),"--test-apk",str(root/"test.apk"),
          "--duration-seconds","600","--reminder-interval-seconds","300","--evidence-dir",str(root/"run")]
recorder.main()
'''
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in ("app.apk", "test.apk"): (root / name).write_bytes(b"synthetic artifact")
            process = subprocess.Popen([sys.executable, "-c", worker, str(Path(RECOVERY.__file__).with_name("verify-f4-soak-runtime.py")), str(root)],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                deadline = time.monotonic() + 5
                while not (root / "at-mutation").exists():
                    if process.poll() is not None or time.monotonic() >= deadline:
                        self.fail("Recorder did not reach the bounded synthetic ADB mutation")
                    time.sleep(0.01)
                with self.assertRaises(RuntimeError): RECOVERY.acquire_lock(root / "run")
                process.kill(); self.assertEqual(-9, process.wait(timeout=3))
                journal = RECOVERY.read_journal(root / "run" / RECOVERY.JOURNAL)
                self.assertEqual("17000", journal["originalPolicies"]["screenTimeout"])
                self.assertFalse(journal["originalPolicies"]["notifications"])
                self.assertEqual([], journal["reminders"])
                self.assertFalse(journal["stateCleanupRequired"])
                self.assertEqual("INCOMPLETE", json.loads((root / "run/f4-soak-runtime-report.json").read_text())["result"])
                RECOVERY.os.close(RECOVERY.acquire_lock(root / "run"))
            finally:
                if process.poll() is None: process.kill(); process.wait(timeout=3)
                process.communicate(timeout=3)


if __name__ == "__main__": unittest.main()
