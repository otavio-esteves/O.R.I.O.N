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
QUALIFIED = "network.orion.qualification"
QUALIFIED_APK_PATH = "/data/app/~~qual==/network.orion.qualification-xyz==/base.apk"
QUALIFIED_HASH = "c" * 64
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
            ("shell", "cmd", "package", "list", "packages", "-U", "network.orion"): "package:network.orion uid:10148",
            ("shell", "sha256sum", APK_PATH): HASH + "  " + APK_PATH,
            ("shell", "settings", "get", "global", "boot_count"): "358",
            ("shell", "pidof", "network.orion"): "123",
            ("shell", "dumpsys", "battery"): BATTERY,
            ("shell", "dumpsys", "meminfo", "-s", "123"): "** MEMINFO in pid 123 [network.orion] **\nTOTAL PSS: 23000 TOTAL RSS: 40000\nSECRET",
            ("shell", "run-as", "network.orion", "du", "-sk", "databases", "files"): "100\tdatabases\n24\tfiles",
            ("shell", "dumpsys", "activity", "exit-info", "network.orion"): EXITS,
        }

    def __call__(self, *args, **kwargs):
        self.calls.append(args)
        value = self.responses[args]
        if isinstance(value, Exception): raise value
        return value


class SoakObservationTest(unittest.TestCase):
    def qualified_adb(self,coexist=False):
        adb=FakeAdb()
        global_responses={command:value for command,value in adb.responses.items()
            if "network.orion" not in command and command not in {("shell","sha256sum",APK_PATH),("shell","dumpsys","meminfo","-s","123")}}
        if not coexist:adb.responses=global_responses
        adb.responses.update({
            ("shell","pm","path",QUALIFIED):"package:"+QUALIFIED_APK_PATH,
            ("shell","sha256sum",QUALIFIED_APK_PATH):QUALIFIED_HASH+"  "+QUALIFIED_APK_PATH,
            ("shell","cmd","package","list","packages","-U",QUALIFIED):
                "package:network.orion uid:10148\npackage:network.orion.qualification.test uid:10162\npackage:"+QUALIFIED+" uid:10161",
            ("shell","pidof",QUALIFIED):"334",
            ("shell","dumpsys","meminfo","-s","334"):"** MEMINFO in pid 334 ["+QUALIFIED+"] **\nTOTAL PSS: 33000 TOTAL RSS: 44000",
            ("shell","run-as",QUALIFIED,"du","-sk","databases","files"):RuntimeError("Non-debuggable private storage unavailable"),
            ("shell","dumpsys","activity","exit-info",QUALIFIED):EXITS.replace("network.orion",QUALIFIED),
        })
        return adb
    def collect_qualified(self,adb):
        clock=Clock()
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp)/"qualified"
            report=RECORDER.observe(adb,directory,QUALIFIED_HASH,"PHYSICAL_DEVICE",10,5,clock.read,clock.sleep,package=QUALIFIED)
            samples=directory/"samples.jsonl"
            rows=[json.loads(line) for line in samples.read_text().splitlines()] if samples.exists() else []
            return report,rows
    def test_qualified_only_and_coexisting_targets_never_query_the_main_package(self):
        for coexist in [False,True]:
            with self.subTest(coexist=coexist):
                adb=self.qualified_adb(coexist);report,rows=self.collect_qualified(adb)
                self.assertEqual(QUALIFIED,report['packageName']);self.assertEqual(10161,report['applicationUidBefore'])
                self.assertEqual(10161,report['applicationUidAfter']);self.assertFalse(report['artifactMismatch'])
                self.assertTrue(rows);self.assertEqual(33000,rows[0]['metrics']['mainMemory']['pssKiB'])
                self.assertNotIn('privateStorage',rows[0]['metrics']);self.assertIn('privateStorage',rows[0]['unavailable'])
                self.assertEqual('INCOMPLETE',report['result']);self.assertFalse(report['fullSoakGateClosed'])
                self.assertFalse(any('network.orion' in command for command in adb.calls))
    def test_qualified_artifact_mismatch_cannot_fall_back_to_a_coexisting_main_apk(self):
        adb=self.qualified_adb(True);adb.responses[("shell","sha256sum",QUALIFIED_APK_PATH)]="b"*64+"  "+QUALIFIED_APK_PATH
        report,rows=self.collect_qualified(adb)
        self.assertTrue(report['artifactMismatch']);self.assertEqual([],rows)
    def test_qualified_meminfo_rejects_wrong_pid_package_and_prefix_collision(self):
        for header in ["** MEMINFO in pid 123 ["+QUALIFIED+"] **","** MEMINFO in pid 334 [network.orion] **",
                       "** MEMINFO in pid 334 [networkXorionXqualification] **","** MEMINFO in pid 334 ["+QUALIFIED+".extra] **"]:
            adb=self.qualified_adb(True);adb.responses[("shell","dumpsys","meminfo","-s","334")]=header+"\nTOTAL PSS: 33000"
            report,rows=self.collect_qualified(adb)
            self.assertNotIn('mainMemory',rows[0]['metrics']);self.assertIn('mainMemory',rows[0]['unavailable'])
    def test_package_allowlist_rejects_injection_before_any_device_call(self):
        for package in ['network.orion.qualification.test','networkXorion','network.orion;reboot','$(id)']:
            adb=FakeAdb()
            with tempfile.TemporaryDirectory() as tmp,self.assertRaises(ValueError):
                RECORDER.observe(adb,Path(tmp)/'new',HASH,'PHYSICAL_DEVICE',10,5,package=package)
            self.assertEqual([],adb.calls)
    def test_qualified_exit_snapshot_rejects_foreign_or_prefix_collision_records(self):
        valid=EXITS.replace('network.orion',QUALIFIED)
        self.assertEqual(2,RECORDER.exits(valid,package=QUALIFIED)['retainedRecords'])
        for foreign in [EXITS,valid.replace(QUALIFIED,QUALIFIED+'.extra'),valid.replace(QUALIFIED,'networkXorionXqualification')]:
            with self.assertRaises(ValueError):RECORDER.exits(foreign,package=QUALIFIED)
    def test_uid_change_inside_qualified_poll_discards_target_metrics_instead_of_mixing_identities(self):
        adb=self.qualified_adb(True);uid_reads=0
        def changed(*args,**kwargs):
            nonlocal uid_reads
            if args==('shell','cmd','package','list','packages','-U',QUALIFIED):
                uid_reads+=1
                if uid_reads>=3:return 'package:'+QUALIFIED+' uid:10163'
            return adb(*args,**kwargs)
        report,rows=self.collect_qualified(changed)
        self.assertTrue(report['uidMismatch']);self.assertEqual('INCOMPLETE',report['result'])
        self.assertIn('packageIdentity',rows[0]['unavailable'])
        for name in ['mainPids','mainMemory','privateStorage','retainedExitHistory']:self.assertNotIn(name,rows[0]['metrics'])
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

    def test_slow_final_read_is_a_gap_even_without_a_later_poll(self):
        clock = Clock(); adb = FakeAdb()
        def slow(*args, **kwargs):
            if args == ("shell", "dumpsys", "battery"): clock.now += 11
            return adb(*args, **kwargs)
        with tempfile.TemporaryDirectory() as tmp:
            report = RECORDER.observe(slow, Path(tmp)/"new", HASH, "PHYSICAL_DEVICE", 10, 5, clock.read, clock.sleep)
        self.assertEqual("INCOMPLETE", report["result"])
        self.assertGreater(report["samplingGaps"], 0)

    def test_injected_clock_is_explicitly_synthetic(self):
        report, _ = self.collect()
        self.assertEqual("SYNTHETIC_INJECTED", report["hostTiming"]["clockSource"])
        self.assertEqual("UNVERIFIED_INPUT", report["hostTiming"]["evidence"])
        self.assertNotEqual("PHYSICAL_OBSERVATION_ONLY", report["androidRuntimeEvidence"])

    def test_suspend_visible_only_to_boot_clock_prevents_completion(self):
        active = Clock(); boot = [0]
        clock = RECORDER.HOST_TIME.HostClock(lambda: boot[0])
        def suspend(seconds):
            active.sleep(seconds)
            boot[0] += round((seconds + 60) * RECORDER.HOST_TIME.NANOS)
        with tempfile.TemporaryDirectory() as tmp:
            report = RECORDER.observe(FakeAdb(), Path(tmp)/"new", HASH, "PHYSICAL_DEVICE", 10, 5,
                                      sleep=suspend, clock=clock)
        self.assertEqual(5, active.now)
        self.assertEqual("INCOMPLETE", report["result"])
        self.assertEqual(1, report["samplingGapReasons"]["START_TO_START"])
        self.assertEqual(65_000_000_000, report["elapsedNanoseconds"])

    def test_endpoint_identity_delay_is_a_gap_and_cannot_inflate_sample_duration(self):
        clock = Clock(); adb = FakeAdb(); identities = 0
        def slow_endpoint(*args, **kwargs):
            nonlocal identities
            if args == ("shell", "getprop", "ro.serialno"):
                identities += 1
                if identities == 2: clock.now += 11
            return adb(*args, **kwargs)
        with tempfile.TemporaryDirectory() as tmp:
            report = RECORDER.observe(slow_endpoint, Path(tmp)/"new", HASH, "PHYSICAL_DEVICE", 10, 5, clock.read, clock.sleep)
        self.assertEqual("INCOMPLETE", report["result"])
        self.assertEqual({"END_WINDOW": 1}, report["samplingGapReasons"])
        self.assertEqual(10, report["elapsedSeconds"])
        self.assertEqual(10_000_000_000, report["elapsedNanoseconds"])

    def test_unavailable_default_clock_fails_before_device_read_and_keeps_report(self):
        adb = FakeAdb()
        with tempfile.TemporaryDirectory() as tmp, patch.object(RECORDER.HOST_TIME.HostClock, "linux",
                side_effect=RECORDER.HOST_TIME.HostTimingError("CLOCK_BOOTTIME_UNAVAILABLE")):
            directory = Path(tmp)/"new"
            report = RECORDER.observe(adb, directory, HASH, "PHYSICAL_DEVICE", 10, 5)
            self.assertTrue((directory/"soak-observation-report.json").exists())
        self.assertEqual([], adb.calls)
        self.assertEqual("INCOMPLETE", report["result"])
        self.assertEqual("UNAVAILABLE", report["hostTiming"]["clockSource"])
        self.assertEqual("CLOCK_BOOTTIME_UNAVAILABLE", report["hostTimingFailure"])

    def test_changed_host_boot_preserves_flushed_samples_without_reinterpreting_time(self):
        now = [0]; boot = ["00000000-0000-0000-0000-000000000001"]
        clock = RECORDER.HOST_TIME.HostClock(lambda: now[0], lambda: boot[0])
        def reboot(_):
            boot[0] = "00000000-0000-0000-0000-000000000002"; now[0] = 1
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)/"new"
            report = RECORDER.observe(FakeAdb(), directory, HASH, "PHYSICAL_DEVICE", 10, 5, sleep=reboot, clock=clock)
            self.assertEqual(1, len((directory/"samples.jsonl").read_text().splitlines()))
        self.assertEqual("INCOMPLETE", report["result"])
        self.assertEqual("HOST_BOOT_CHANGED", report["hostTimingFailure"])

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
