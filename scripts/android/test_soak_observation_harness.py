#!/usr/bin/env python3
"""Device-independent observer contracts; synthetic fixtures only."""
import importlib.util
import json
import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("orion_soak", Path(__file__).with_name("record-soak-observation.py"))
RECORDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RECORDER)
HASH = "a" * 64
APK_PATH = "/data/app/~~abc==/network.orion-abc==/base.apk"
BATTERY = "level: 90\nscale: 100\nstatus: 2\ntemperature: 300\nUSB powered: true\nserial: SECRET"
EXITS = """ACTIVITY MANAGER PROCESS EXIT INFO (dumpsys activity exit-info)
  ApplicationExitInfo #0:
   timestamp=2026-11-07 10:00:00 pid=123 user=0
   process=network.orion reason=4 (CRASH)
   description=SECRET
  ApplicationExitInfo #1:
   timestamp=2026-11-07 10:00:01 pid=124 user=0
   process=network.orion:ai reason=5 (CRASH_NATIVE)
   description=SECRET
"""


class Clock:
    def __init__(self): self.now = 0
    def read(self): return self.now
    def sleep(self, duration): self.now += duration


class FakeAdb:
    def __init__(self):
        self.calls = []
        self.responses = {
            ("shell", "getprop", "ro.kernel.qemu"): "0",
            ("shell", "getprop", "ro.build.version.sdk"): "35",
            ("shell", "getprop", "ro.serialno"): "SYNTHETIC_SERIAL",
            ("shell", "pm", "path", "network.orion"): "package:" + APK_PATH,
            ("shell", "sha256sum", APK_PATH): HASH + "  " + APK_PATH,
            ("shell", "settings", "get", "global", "boot_count"): "358",
            ("shell", "pidof", "network.orion"): "123",
            ("shell", "dumpsys", "battery"): BATTERY,
            ("shell", "dumpsys", "meminfo", "-s", "123"): "TOTAL PSS: 23000 TOTAL RSS: 40000\nSECRET",
            ("shell", "run-as", "network.orion", "du", "-sk", "databases", "files"): "100\tdatabases\n24\tfiles",
            ("shell", "dumpsys", "activity", "exit-info", "network.orion"): EXITS,
        }

    def __call__(self, *args, **kwargs):
        self.calls.append(args)
        value = self.responses[args]
        if isinstance(value, Exception): raise value
        return value


class SoakObservationTest(unittest.TestCase):
    def collect(self, adb=None, duration=11, interval=5, sleep=None):
        clock = Clock()
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / "evidence"
            report = RECORDER.observe(adb or FakeAdb(), directory, HASH, "PHYSICAL_DEVICE", duration, interval,
                                      clock.read, sleep or clock.sleep)
            rows = [json.loads(x) for x in (directory / "samples.jsonl").read_text().splitlines()] if (directory / "samples.jsonl").exists() else []
            self.assertEqual(report, json.loads((directory / "soak-observation-report.json").read_text()))
            return report, rows

    def test_bounded_collection_writes_samples_but_never_passes_a_soak_gate(self):
        report, rows = self.collect()
        self.assertEqual("OBSERVATION_COMPLETE_REVIEW_REQUIRED", report["result"])
        self.assertFalse(report["fullSoakGateClosed"])
        self.assertEqual([0, 5, 10, 11], [r["elapsedSeconds"] for r in rows])
        self.assertEqual(4, report["samples"])
        self.assertEqual(0, report["samplesWithGaps"])

    def test_invalid_or_excessive_budgets_fail_before_any_adb_call(self):
        for duration, interval in [(0, 60), (float("nan"), 60), (float("inf"), 60), (86401*7, 60), (5, 0), (5, 3601), (7*86400, 5)]:
            adb = FakeAdb()
            with tempfile.TemporaryDirectory() as tmp, self.assertRaises(ValueError):
                RECORDER.observe(adb, Path(tmp)/"new", HASH, "PHYSICAL_DEVICE", duration, interval)
            self.assertEqual([], adb.calls)

    def test_existing_evidence_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            marker = Path(tmp)/"original"; marker.write_text("keep")
            adb = FakeAdb()
            with self.assertRaises(FileExistsError):
                RECORDER.observe(adb, Path(tmp), HASH, "PHYSICAL_DEVICE", 1, 5)
            self.assertEqual("keep", marker.read_text())
            self.assertEqual([], adb.calls)

    def test_mislabeled_emulator_cannot_become_physical_evidence(self):
        adb = FakeAdb(); adb.responses[("shell", "getprop", "ro.kernel.qemu")] = "1"
        report, rows = self.collect(adb)
        self.assertEqual("INCOMPLETE", report["result"])
        self.assertEqual([], rows)

    def test_installed_apk_mismatch_prevents_sampling(self):
        adb = FakeAdb(); adb.responses[("shell", "sha256sum", APK_PATH)] = "b"*64 + "  " + APK_PATH
        report, rows = self.collect(adb)
        self.assertTrue(report["artifactMismatch"])
        self.assertEqual([], rows)

    def test_changed_apk_at_end_invalidates_otherwise_complete_collection(self):
        adb = FakeAdb(); seen = 0
        def changing(*args, **kwargs):
            nonlocal seen
            if args[:2] == ("shell", "sha256sum"):
                seen += 1
                if seen > 1: return "b"*64 + "  " + APK_PATH
            return adb(*args, **kwargs)
        report, rows = self.collect(changing)
        self.assertEqual("INCOMPLETE", report["result"])
        self.assertTrue(report["artifactMismatch"])
        self.assertTrue(rows)

    def test_absent_process_is_explicit_and_is_not_a_zero_memory_or_crash_claim(self):
        adb = FakeAdb(); adb.responses[("shell", "pidof", "network.orion")] = ""
        report, rows = self.collect(adb)
        self.assertEqual([], rows[0]["metrics"]["mainPids"])
        self.assertNotIn("mainMemory", rows[0]["metrics"])
        self.assertNotIn(("shell", "dumpsys", "meminfo", "-s", "123"), adb.calls)
        self.assertFalse(report["fullSoakGateClosed"])

    def test_metric_disconnect_remains_a_gap_even_if_later_samples_recover(self):
        adb = FakeAdb(); first = True
        def disconnect(*args, **kwargs):
            nonlocal first
            if first and args == ("shell", "dumpsys", "battery"):
                first = False
                raise RuntimeError("SECRET")
            return adb(*args, **kwargs)
        report, rows = self.collect(disconnect)
        self.assertEqual("INCOMPLETE", report["result"])
        self.assertEqual(1, report["samplesWithGaps"])
        self.assertEqual(["battery"], rows[0]["unavailable"])
        self.assertEqual([], rows[-1]["unavailable"])
        self.assertNotIn("SECRET", json.dumps(report) + json.dumps(rows))

    def test_keyboard_interrupt_keeps_partial_evidence_without_success(self):
        def stop(_): raise KeyboardInterrupt()
        report, rows = self.collect(sleep=stop)
        self.assertTrue(report["interrupted"])
        self.assertEqual("INCOMPLETE", report["result"])
        self.assertEqual(1, len(rows))

    def test_preflight_errors_never_export_exception_content(self):
        adb = FakeAdb(); adb.responses[("shell", "getprop", "ro.kernel.qemu")] = RuntimeError("SECRET")
        report, _ = self.collect(adb)
        self.assertEqual("RuntimeError", report["failureType"])
        self.assertNotIn("SECRET", json.dumps(report))

    def test_only_allowlisted_numeric_and_boolean_fields_are_persisted(self):
        report, rows = self.collect()
        self.assertNotIn("SECRET", json.dumps(report) + json.dumps(rows))
        self.assertEqual({"retainedRecords": 2, "reasonCounts": {"4": 1, "5": 1}}, rows[0]["metrics"]["retainedExitHistory"])
        self.assertNotIn("timestamp", json.dumps(rows))

    def test_unsupported_oem_formats_are_unavailable_not_successful_zeroes(self):
        for command in [("shell", "dumpsys", "meminfo", "-s", "123"), ("shell", "dumpsys", "activity", "exit-info", "network.orion")]:
            adb = FakeAdb(); adb.responses[command] = "Unknown command SECRET"
            report, rows = self.collect(adb)
            self.assertEqual("INCOMPLETE", report["result"])
            self.assertTrue(rows[0]["unavailable"])

    def test_private_storage_accepts_only_the_two_requested_size_totals(self):
        for output in ["1 databases\n2 files\n3 secrets", "1 databases\n2 databases\n3 files", "1 databases", "-1 databases\n2 files"]:
            with self.assertRaises(ValueError): RECORDER.storage(output)

    def test_apk_path_cannot_inject_a_device_shell_command_or_select_splits(self):
        for path in ["package:/data/app/base.apk;reboot", "package:/data/app/$(reboot)/base.apk", "package:/data/app/a/base.apk\npackage:/data/app/a/split.apk"]:
            adb = FakeAdb(); adb.responses[("shell", "pm", "path", "network.orion")] = path
            with self.assertRaises(ValueError): RECORDER.installed_hash(adb)
            self.assertEqual(1, len(adb.calls))

    def test_pid_output_rejects_command_fragments_and_ambiguous_whitespace(self):
        for value in ["0", "123\n", "123;reboot", "$(id)", "123  456"]:
            with self.assertRaises(ValueError): RECORDER.pids(value)

    def test_observer_has_no_mutating_or_business_content_device_commands(self):
        adb = FakeAdb(); self.collect(adb)
        for command in adb.calls:
            self.assertNotIn("install", command)
            self.assertNotIn("instrument", command)
            self.assertNotIn("cat", command)
            self.assertNotIn("logcat", command)
            self.assertNotIn("start", command)
            self.assertNotIn("force-stop", command)
            self.assertNotIn("put", command)
            self.assertNotIn("reboot", command)

    def test_replaced_device_with_same_apk_is_still_incomplete(self):
        adb = FakeAdb(); seen = 0
        def replacing(*args, **kwargs):
            nonlocal seen
            if args == ("shell", "getprop", "ro.serialno"):
                seen += 1
                if seen > 1: return "OTHER_SYNTHETIC_SERIAL"
            return adb(*args, **kwargs)
        report, _ = self.collect(replacing)
        self.assertTrue(report["deviceMismatch"])
        self.assertEqual("INCOMPLETE", report["result"])
        self.assertNotIn("SYNTHETIC_SERIAL", json.dumps(report))

    def test_late_host_wakeup_does_not_hide_an_observation_gap(self):
        clock = Clock(); first = True
        def delay(duration):
            nonlocal first
            clock.now += duration + (11 if first else 0)
            first = False
        with tempfile.TemporaryDirectory() as tmp:
            report = RECORDER.observe(FakeAdb(), Path(tmp)/"new", HASH, "PHYSICAL_DEVICE", 30, 5, clock.read, delay)
        self.assertEqual("INCOMPLETE", report["result"])
        self.assertEqual(1, report["samplingGaps"])

    def test_multiple_main_pids_cannot_be_mislabeled_as_one_process_memory(self):
        adb = FakeAdb(); adb.responses[("shell", "pidof", "network.orion")] = "123 456"
        report, rows = self.collect(adb)
        self.assertEqual("INCOMPLETE", report["result"])
        self.assertIn("mainMemory", rows[0]["unavailable"])

    def test_command_timeout_is_recorded_as_missing_metric(self):
        adb = FakeAdb(); adb.responses[("shell", "dumpsys", "battery")] = subprocess.TimeoutExpired("SECRET", 10)
        report, rows = self.collect(adb)
        self.assertEqual("INCOMPLETE", report["result"])
        self.assertIn("battery", rows[0]["unavailable"])
        self.assertNotIn("SECRET", json.dumps(rows))

    def test_cli_collects_using_a_synthetic_executable_without_a_real_adb(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            apk = directory/"synthetic.apk"; apk.write_bytes(b"synthetic artifact")
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            responses = FakeAdb().responses
            responses[("shell", "sha256sum", APK_PATH)] = digest + "  " + APK_PATH
            source = "#!/usr/bin/env python3\nimport sys\nresponses=" + repr(responses) + "\nprint(responses[tuple(sys.argv[3:])])\n"
            executable = directory/"fake-adb"; executable.write_text(source); executable.chmod(0o700)
            result = subprocess.run(["python3", str(RECORDER.__file__), "--adb", str(executable),
                                     "--serial", "synthetic", "--environment", "PHYSICAL_DEVICE", "--apk", str(apk),
                                     "--evidence-dir", str(directory/"evidence"), "--duration-seconds", "1", "--interval-seconds", "5"],
                                    capture_output=True, text=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stdout+result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual("OBSERVATION_COMPLETE_REVIEW_REQUIRED", report["result"])
            self.assertFalse(report["fullSoakGateClosed"])
            self.assertNotIn("SECRET", (directory/"evidence"/"samples.jsonl").read_text())

    def test_transport_uses_exact_serial_and_bounded_lifetime(self):
        def run(command, **kwargs):
            self.assertEqual(["fake-adb", "-s", "device", "shell", "getprop", "ro.kernel.qemu"], command)
            self.assertEqual(10, kwargs["timeout"])
            kwargs["stdout"].write(b"0\n")
            return subprocess.CompletedProcess(command, 0)
        with patch.object(RECORDER.subprocess, "run", run):
            self.assertEqual("0", RECORDER.Adb("fake-adb", "device")("shell", "getprop", "ro.kernel.qemu"))

    def test_transport_output_bound_rejects_oversized_dumps(self):
        def run(command, **kwargs):
            kwargs["stdout"].write(b"x"*(RECORDER.MAX_OUTPUT_BYTES+1))
            return subprocess.CompletedProcess(command, 0)
        with patch.object(RECORDER.subprocess, "run", run), self.assertRaises(ValueError):
            RECORDER.Adb("fake", "device")("shell", "dumpsys", "battery")

    def test_no_process_exit_code_is_allowed_only_for_an_empty_pid_response(self):
        for output, allowed, succeeds in [(b"", True, True), (b"permission denied", True, False), (b"", False, False)]:
            def run(command, **kwargs):
                kwargs["stdout"].write(output)
                return subprocess.CompletedProcess(command, 1)
            with patch.object(RECORDER.subprocess, "run", run):
                if succeeds: self.assertEqual("", RECORDER.Adb("fake", "device")("shell", "pidof", "network.orion", allow_absent=allowed))
                else:
                    with self.assertRaises(RuntimeError): RECORDER.Adb("fake", "device")("shell", "pidof", "network.orion", allow_absent=allowed)


if __name__ == "__main__":
    unittest.main()
