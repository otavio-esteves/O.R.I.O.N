"""Pure HOST fixtures only. No ADB/device/model workload or physical qualification."""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


REC = load("orion_single_capture_test", "record-f5-single-attempt.py")
BASE_TEST = load("orion_single_capture_baseline_fixtures", "test_f5_physical_baseline_harness.py")


def header(phase="PREPARE_REFUSED", environment="PHYSICAL_DEVICE"):
    value = dict.fromkeys(REC.FLAGS, False)
    value.update(schemaVersion=1, kind="CORTEX_SINGLE_ATTEMPT_STATUS", qualification="NOT_QUALIFIED", phase=phase,
                 failureCode=None, refusal="NO_QUALIFIED_PROFILE" if phase == "PREPARE_REFUSED" else None,
                 environment=environment, environmentSource="BUILD_SIGNALS_ONLY", counterScope="DRIVER_METHOD_CALLS",
                 prepareCalls=1, executeCalls=0 if phase == "PREPARE_REFUSED" else 1,
                 readCalls=1 if phase in {"OBSERVED", "RELEASED", "RELEASE_NOT_AVAILABLE", "OBSERVATION_UNAVAILABLE"} else 0,
                 releaseCalls=1 if phase.startswith("RELEASE") else 0,
                 ownerCallInvoked=None if phase == "PREPARE_REFUSED" else True, partialPresent=phase == "OBSERVED")
    if phase == "EXECUTE_NOT_ATTEMPTED":
        value.update(ownerCallInvoked=False, refusal="CORE_NOT_READY")
    if phase == "OBSERVATION_UNAVAILABLE":
        value["failureCode"] = "OBSERVATION_UNAVAILABLE"
    return value


def runner_failure():
    value = dict.fromkeys(REC.FLAGS, False)
    value.update(schemaVersion=1, kind="CORTEX_SINGLE_ATTEMPT_RUNNER_FAILURE", qualification="NOT_QUALIFIED",
                 phase="RUNNER_FAILED", failureCode="EXECUTE_FAILED", environment="UNVERIFIED", ownerCallInvoked=None)
    return value


def projection():
    value = dict.fromkeys((REC.FLAGS - {"runtimeEvidenceV1Complete"}) |
                         {"runtimeProtocolV1Complete", "identifiersRetained", "timestampsRetained"}, False)
    value.update(format="ORION_MAIN_CYCLE_PARTIAL", schemaVersion=1, qualification="NOT_QUALIFIED", retainedSnapshot=True,
                 sourceStateAsReported="INCOMPLETE", sourceAsReported="TURN_ENTERED", throwClassificationAsReported=None,
                 actualTurnEnteredAsReported=True, inferenceAcceptedAsReported=True)
    for name in ("outcome", "stream", "resources", "native", "unload", "sourceIssueMask", "observerLoss"):
        value[name] = {"presence": "ABSENT", "value": None}
    value["outcome"] = {"presence": "PRESENT", "value": {"kind": "COMPLETED", "stage": "TERMINAL", "terminal": "SUCCEEDED", "failure": None}}
    value["stream"] = {"presence": "PRESENT", "value": {"acceptedChunkObservationsAtLeast": 1, "callbackAttemptsAtLeast": 1, "callbacksReturnedNormallyAtLeast": 1}}
    value["resources"] = {"presence": "PRESENT", "value": []}
    value["sourceIssueMask"] = {"presence": "PRESENT", "value": 56}
    value["observerLoss"] = {"presence": "PRESENT", "value": {"observerGlobalDroppedAtLeast": 0,
        "observerGlobalRecordingFailuresAtLeast": 0, "lossAccountingCompleteAsReported": True}}
    return value


def compact(value):
    return json.dumps(value, separators=(",", ":"))


def transcript(primary=None, partial=None, release=None, code=-1, stream=None):
    primary = header() if primary is None else primary
    fields = {REC.HEADER: compact(primary)}
    if partial is not None:
        fields[REC.PARTIAL] = compact(partial)
    if release is not None:
        fields[REC.RELEASE] = compact(release)
    rows = ["INSTRUMENTATION_RESULT: " + key + "=" + value for key, value in fields.items()]
    rows += ["INSTRUMENTATION_RESULT: stream=", stream or (REC.STREAM_FAILURE if code == 0 else REC.STREAM_NOT_ATTEMPTED if primary["phase"] in {"PREPARE_REFUSED", "EXECUTE_NOT_ATTEMPTED"} else REC.STREAM_OK),
             "", "INSTRUMENTATION_CODE: " + str(code)]
    return "\n".join(rows)


class Adb(BASE_TEST.FakeAdb):
    def __call__(self, *command):
        if command == REC.instrumentation_command():
            self.commands.append(command)
            self.on_instrument()
            self.after = True
            return self.output
        return super().__call__(*command)


class SingleAttemptCaptureTest(unittest.TestCase):
    def setUp(self):
        self.fixture = BASE_TEST.PhysicalBaselineRecorderTest(methodName="runTest")
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.adb = Adb(self.fixture.hashes)
        self.adb.output = transcript()
        self.host_boot = "00000000-0000-4000-8000-000000000010"
        self.clock_mark = 0
        def read_ns():
            self.clock_mark += 1_000_000_000
            return self.clock_mark
        self.clock = REC.CLOCK.HostClock(read_ns, lambda: self.host_boot)
        # Simulate the platform factory in HOST fixtures only. Not a CLI/input clock.
        self.clock.metadata.update(clockSource="CLOCK_BOOTTIME", evidence="HOST_CLOCK_ONLY")
        patches = (mock.patch.object(REC.PREFLIGHT, "git", side_effect=self.fixture.git),
                   mock.patch.object(REC.CLOCK.HostClock, "linux", return_value=self.clock))
        for patch in patches:
            patch.start(); self.addCleanup(patch.stop)

    def record(self, **kwargs):
        f = self.fixture
        return REC.record(self.adb, kwargs.pop("serial", "EXPLICIT_PHYSICAL_SERIAL"), kwargs.pop("target", "S21"),
                          f.source, f.manifest, f.app, f.test, f.output, **kwargs)

    def issued(self):
        return sum(command == REC.instrumentation_command() for command in self.adb.commands)

    def report_text(self):
        return (self.fixture.output / "f5-single-attempt.json").read_text()

    def test_real_labelled_fixture_no_profile_is_capture_only_not_owner_run_or_qualification(self):
        report = self.record()
        self.assertEqual("COMPLETE", report["captureIntegrity"])
        self.assertEqual("PREPARE_REFUSED", report["capture"]["programOutcome"])
        self.assertIsNone(report["capture"]["header"]["ownerCallInvoked"])
        self.assertIsNone(report["capture"]["partial"]); self.assertIsNone(report["capture"]["releaseControl"])
        self.assertEqual(1, self.issued()); self.assertTrue(report["possibleTechnicalBootstrapWrites"])
        for name in REC.FLAGS | {"modelReadyAssociationProven", "inferenceLatencyMeasured", "continuityProven", "automaticReplayPermitted", "installPerformed", "deviceSettingsChanged"}:
            self.assertIs(report[name], False)
        self.assertEqual("NOT_QUALIFIED", report["qualification"])
        self.assertNotIn("deviceMutated", report); self.assertNotIn("modelInputsUsed", report)
        self.assertNotIn("EXPLICIT_PHYSICAL_SERIAL", self.report_text())
        self.assertNotIn("ORION_PRIVATE_DEVICE_SERIAL", self.report_text())
        self.assertNotIn(str(self.fixture.source), self.report_text())

    def test_partial_and_original_observation_header_stay_paired_with_separate_release(self):
        self.adb.output = transcript(header("OBSERVED"), projection(), header("RELEASED"))
        report = self.record()
        self.assertEqual("COMPLETE", report["captureIntegrity"])
        self.assertEqual("OBSERVED", report["capture"]["header"]["phase"])
        self.assertTrue(report["capture"]["header"]["partialPresent"])
        self.assertEqual("RELEASED", report["capture"]["releaseControl"]["phase"])
        self.assertFalse(report["capture"]["releaseControl"]["partialPresent"])
        self.assertEqual("INCOMPLETE", report["capture"]["partial"]["sourceStateAsReported"])
        self.assertEqual(1, self.issued())

    def test_runner_failure_is_unknown_and_never_synthetic_no_attempt(self):
        self.adb.output = transcript(runner_failure(), code=0)
        report = self.record()
        self.assertEqual("INCOMPLETE", report["captureIntegrity"])
        self.assertEqual("UNKNOWN", report["invocationOutcome"])
        self.assertNotIn("prepareCalls", report["capture"]["header"])
        self.assertIsNone(report["capture"]["header"]["ownerCallInvoked"])
        self.assertEqual(1, self.issued())

    def test_driver_not_attempted_and_missing_observation_stay_distinct(self):
        for primary, control in [(header("EXECUTE_NOT_ATTEMPTED"), None),
                                 (header("OBSERVATION_UNAVAILABLE"), header("RELEASE_NOT_AVAILABLE"))]:
            value = REC.receipts(transcript(primary, release=control))
            self.assertIsNone(value["partial"])
            self.assertEqual(primary["phase"], value["programOutcome"])

    def test_offline_emulator_transcript_is_unqualified_but_recorder_denies_emulator_before_instrument(self):
        parsed = REC.receipts(transcript(header(environment="ANDROID_EMULATOR")))
        self.assertEqual("ANDROID_EMULATOR", parsed["header"]["environment"])
        self.adb.answers[("shell", "getprop", "ro.kernel.qemu")] = "1"
        report = self.record()
        self.assertEqual("REJECTED", report["captureIntegrity"]); self.assertEqual(0, self.issued())

    def test_emulator_serial_and_wrong_s21_and_fake_labels_refuse_before_instrument(self):
        report = self.record(serial="emulator-5554")
        self.assertEqual("EXPLICIT_PHYSICAL_SERIAL_REQUIRED", report["failureCode"])
        self.assertEqual([], self.adb.commands)

    def test_wrong_s21_model_is_refused_before_invocation(self):
        self.adb.answers[("shell", "getprop", "ro.product.model")] = "OTHER_PHONE"
        report = self.record()
        self.assertEqual("TARGET_MODEL_MISMATCH", report["failureCode"]); self.assertEqual(0, self.issued())

    def test_fake_build_label_is_refused_without_persisting_raw_label(self):
        sentinel = "fake_SECRET_DEVICE_LABEL"
        self.adb.answers[("shell", "getprop", "ro.hardware")] = sentinel
        report = self.record()
        self.assertEqual("SYNTHETIC_DEVICE_LABEL", report["failureCode"]); self.assertEqual(0, self.issued())
        self.assertNotIn(sentinel, self.report_text())

    def test_installed_hash_mismatch_prevents_invocation(self):
        for key in self.adb.answers:
            if key[:2] == ("shell", "sha256sum"):
                self.adb.answers[key] = "c" * 64 + "  " + key[-1]
        report = self.record()
        self.assertEqual("INSTALLED_APK_MISMATCH", report["failureCode"]); self.assertEqual(0, self.issued())

    def test_invalid_manifests_and_installed_hash_prevent_invocation(self):
        self.fixture.source_record["artifacts"]["model"] = "c" * 64
        self.fixture.save_manifest()
        report = self.record()
        self.assertEqual("REJECTED", report["captureIntegrity"]); self.assertEqual(0, self.issued())

    def test_installed_hash_change_after_single_command_rejects_without_retry(self):
        def change():
            for key in self.adb.answers:
                if key[:2] == ("shell", "sha256sum"):
                    self.adb.answers[key] = "c" * 64 + "  " + key[-1]
        self.adb.on_instrument = change
        report = self.record()
        self.assertEqual("INSTALLED_APK_CHANGED", report["failureCode"])
        self.assertEqual("REJECTED", report["captureIntegrity"]); self.assertEqual(1, self.issued())

    def test_source_and_local_artifact_change_after_command_reject(self):
        self.adb.on_instrument = lambda: self.fixture.app.write_bytes(b"changed")
        report = self.record()
        self.assertEqual("SOURCE_OR_LOCAL_ARTIFACT_CHANGED", report["failureCode"])
        self.assertEqual(1, self.issued())

    def test_source_tree_change_after_command_rejects(self):
        self.adb.on_instrument = lambda: (self.fixture.source / "seed.kt").write_text("changed")
        report = self.record()
        self.assertEqual("SOURCE_TREE_DIRTY", report["failureCode"]); self.assertEqual(1, self.issued())

    def test_android_crossboot_is_rejected(self):
        self.adb.after_boot_id = "00000000-0000-4000-8000-000000000002"
        report = self.record()
        self.assertEqual("ANDROID_BOOT_OR_WINDOW_CHANGED", report["failureCode"]); self.assertEqual(1, self.issued())

    def test_android_uptime_rollback_rejects_the_program_window(self):
        original = self.adb.__call__
        def adb(*command):
            if command == ("shell", "cat", "/proc/uptime") and previous.after:
                return "99.00 200.00"
            return original(*command)
        previous = self.adb
        try:
            self.adb = adb
            report = self.record()
        finally:
            self.adb = previous
        self.assertEqual("ANDROID_BOOT_OR_WINDOW_CHANGED", report["failureCode"])

    def test_host_crossboot_during_invocation_is_incomplete_unknown(self):
        self.adb.on_instrument = lambda: setattr(self, "host_boot", "00000000-0000-4000-8000-000000000020")
        report = self.record()
        self.assertEqual("HOST_BOOT_CHANGED", report["failureCode"])
        self.assertEqual("INCOMPLETE", report["captureIntegrity"]); self.assertEqual("UNKNOWN", report["invocationOutcome"])
        self.assertEqual(1, self.issued())

    def test_host_clock_rollback_and_suspend_gap_never_become_continuity_or_retry(self):
        self.adb.on_instrument = lambda: setattr(self, "clock_mark", -1_000_000_000)
        report = self.record()
        self.assertEqual("HOST_CLOCK_REGRESSED", report["failureCode"]); self.assertEqual(1, self.issued())

    def test_host_gap_during_single_invocation_is_incomplete_not_24h(self):
        self.adb.on_instrument = lambda: setattr(self, "clock_mark", 400_000_000_000)
        report = self.record()
        self.assertEqual("HOST_PROGRAM_GAP", report["failureCode"])
        self.assertEqual("INCOMPLETE", report["captureIntegrity"]); self.assertEqual(1, self.issued())
        self.assertFalse(report["continuityProven"])

    def test_host_gap_after_return_before_final_integrity_check_is_not_hidden(self):
        original = REC.PREFLIGHT.source_identity
        calls = 0
        def source(*args, **kwargs):
            nonlocal calls
            calls += 1
            result = original(*args, **kwargs)
            if calls == 2:
                self.clock_mark = 400_000_000_000
            return result
        with mock.patch.object(REC.PREFLIGHT, "source_identity", side_effect=source):
            report = self.record()
        self.assertEqual("HOST_PROGRAM_GAP", report["failureCode"])
        self.assertEqual("INCOMPLETE", report["captureIntegrity"]); self.assertEqual(1, self.issued())

    def test_synthetic_clock_and_unavailable_clock_fail_before_device_calls(self):
        report = self.record(clock=REC.CLOCK.HostClock(lambda: 1))
        self.assertEqual("REAL_HOST_CLOCK_REQUIRED", report["failureCode"]); self.assertEqual([], self.adb.commands)

    def test_unknown_clock_metadata_is_not_persisted(self):
        self.clock.metadata["secret"] = "SECRET_CLOCK_SENTINEL"
        report = self.record()
        self.assertEqual("REJECTED", report["captureIntegrity"])
        self.assertNotIn("hostTiming", report); self.assertNotIn("SECRET_CLOCK_SENTINEL", self.report_text())
        self.assertEqual([], self.adb.commands)

    def test_clock_factory_unavailable_never_falls_back(self):
        with mock.patch.object(REC.CLOCK.HostClock, "linux", side_effect=REC.CLOCK.HostTimingError("CLOCK_BOOTTIME_UNAVAILABLE")):
            report = self.record()
        self.assertEqual("CLOCK_BOOTTIME_UNAVAILABLE", report["failureCode"]); self.assertEqual([], self.adb.commands)

    def test_timeout_and_interrupt_are_unknown_and_sentinel_is_not_reported(self):
        sentinel = "SECRET_STDOUT_STDERR_SERIAL_PATH"
        def timeout():
            raise subprocess.TimeoutExpired(sentinel, 1, output=sentinel, stderr=sentinel)
        self.adb.on_instrument = timeout
        output, error = io.StringIO(), io.StringIO()
        with mock.patch("sys.stdout", output), mock.patch("sys.stderr", error):
            report = self.record()
        self.assertEqual("INCOMPLETE", report["captureIntegrity"]); self.assertEqual("UNKNOWN", report["invocationOutcome"])
        self.assertEqual(1, self.issued())
        self.assertNotIn(sentinel, self.report_text() + output.getvalue() + error.getvalue())

    def test_interruption_does_not_reissue_or_infer_android_termination(self):
        self.adb.on_instrument = lambda: (_ for _ in ()).throw(KeyboardInterrupt())
        report = self.record()
        self.assertEqual("INTERRUPTED", report["failureCode"]); self.assertEqual("UNKNOWN", report["invocationOutcome"])
        self.assertEqual(1, self.issued()); self.assertEqual("INCOMPLETE", report["captureIntegrity"])

    def test_unrecognized_stdout_and_exception_text_never_persist(self):
        sentinel = "SECRET_STDOUT_STDERR_SERIAL_PATH"
        self.adb.output = transcript() + "\n" + sentinel
        report = self.record()
        self.assertEqual("REJECTED", report["captureIntegrity"])
        self.assertNotIn(sentinel, self.report_text()); self.assertNotIn("capture", report)

    def test_foreign_rejected_exception_is_mapped_to_a_fixed_code(self):
        self.adb.on_instrument = lambda: (_ for _ in ()).throw(REC.Rejected("SECRET_SENTINEL"))
        report = self.record()
        self.assertEqual("INPUT_OR_OBSERVATION_UNAVAILABLE", report["failureCode"])
        self.assertNotIn("SECRET_SENTINEL", self.report_text())

    def test_runner_failure_with_emulator_release_control_is_not_a_physical_capture(self):
        control = header("RELEASE_NOT_AVAILABLE", environment="ANDROID_EMULATOR")
        control.update(ownerCallInvoked=None, readCalls=0)
        output = transcript(runner_failure(), release=control, code=0)
        self.assertIsNotNone(REC.receipts(output)["releaseControl"])  # Offline software parsing only.
        self.adb.output = output
        report = self.record()
        self.assertEqual("RUNNER_ENVIRONMENT_MISMATCH", report["failureCode"])
        self.assertNotIn("capture", report); self.assertEqual(1, self.issued())

    def test_changed_runner_environment_cannot_relabel_emulator_as_physical(self):
        self.adb.output = transcript(header(environment="ANDROID_EMULATOR"))
        report = self.record()
        self.assertEqual("RUNNER_ENVIRONMENT_MISMATCH", report["failureCode"])
        self.assertNotIn("capture", report)

    def test_existing_output_directory_is_never_reused(self):
        self.fixture.output.mkdir()
        with self.assertRaises(FileExistsError):
            self.record()
        self.assertEqual([], self.adb.commands)

    def test_cli_invalid_authority_input_does_not_echo_its_secret_value(self):
        parser = REC.ClosedArgumentParser()
        error = io.StringIO()
        with mock.patch("sys.stderr", error), self.assertRaises(SystemExit):
            parser.parse_args(["--approved-template", "SECRET_CONFIGURATION"])
        self.assertNotIn("SECRET_CONFIGURATION", error.getvalue())
        self.assertIn("CLI_ARGUMENTS_INVALID", error.getvalue())

    def client_factory(self, code, command_check=True):
        real_popen = subprocess.Popen
        children = []
        def create(command, **kwargs):
            if command_check:
                self.assertEqual(["owned-adb", "-s", "EXPLICIT_PHYSICAL_SERIAL"] + list(REC.instrumentation_command()), command)
            self.assertEqual(subprocess.DEVNULL, kwargs["stderr"])
            self.assertEqual(subprocess.DEVNULL, kwargs["stdin"])
            self.assertEqual(subprocess.PIPE, kwargs["stdout"])
            # Owned Python HOST child only. Never an actual ADB/device subprocess.
            child = real_popen([sys.executable, "-u", "-c", code], **kwargs)
            children.append(child)
            return child
        return create, children

    def test_closed_adb_adapter_has_exact_serial_command_no_args_and_one_invocation(self):
        adapter = REC.CaptureAdb("owned-adb", "EXPLICIT_PHYSICAL_SERIAL")
        create, children = self.client_factory("print('closed fixture transport')")
        with mock.patch.object(REC.subprocess, "Popen", side_effect=create) as run:
            self.assertEqual("closed fixture transport", adapter(*REC.instrumentation_command()))
            with self.assertRaises(REC.Rejected): adapter(*REC.instrumentation_command())
            self.assertEqual(1, run.call_count)
        self.assertIsNotNone(children[0].poll())
        for command in [("shell", "pm", "install", "evil.apk"), ("shell", "kill", "1"), ("shell", "settings", "put", "x", "y"),
                        ("push", "file", "/tmp"), REC.instrumentation_command() + ("-e", "approved", "true"), ("shell", "am", "force-stop", "network.orion.qualification")]:
            self.assertFalse(REC.allowed_command(command))

    def test_streaming_transport_stops_at_bound_while_owned_child_is_still_writing(self):
        adapter = REC.CaptureAdb("owned-adb", "EXPLICIT_PHYSICAL_SERIAL")
        create, children = self.client_factory("import os\nwhile True: os.write(1, b'x' * 65536)")
        real_read = REC.os.read
        read_sizes = []
        def read(fd, size):
            block = real_read(fd, size)
            read_sizes.append(len(block))
            return block
        with mock.patch.object(REC.subprocess, "Popen", side_effect=create) as popen, mock.patch.object(REC.os, "read", side_effect=read):
            with self.assertRaisesRegex(REC.Rejected, "ADB_OUTPUT_BOUND_EXCEEDED"):
                adapter(*REC.instrumentation_command())
        self.assertEqual(1, popen.call_count)
        self.assertEqual(REC.MAX_OUTPUT + 1, sum(read_sizes))
        self.assertIsNotNone(children[0].poll())
        self.assertIsNotNone(children[0].returncode)  # Child never finishes writing on its own.

    def test_transport_boottime_deadline_stops_only_owned_host_client_without_second_command(self):
        adapter = REC.CaptureAdb("owned-adb", "EXPLICIT_PHYSICAL_SERIAL")
        create, children = self.client_factory("import time; time.sleep(30)")
        def jumped(command, **kwargs):
            child = create(command, **kwargs)
            self.clock_mark += 400_000_000_000
            return child
        with mock.patch.object(REC.subprocess, "Popen", side_effect=jumped) as popen:
            with self.assertRaisesRegex(REC.Rejected, "ADB_TRANSPORT_TIMEOUT"):
                adapter(*REC.instrumentation_command())
        self.assertEqual(1, popen.call_count); self.assertIsNotNone(children[0].poll())

    def test_transport_clock_failure_after_spawn_closes_only_its_own_host_child(self):
        adapter = REC.CaptureAdb("owned-adb", "EXPLICIT_PHYSICAL_SERIAL")
        create, children = self.client_factory("import time; time.sleep(30)")
        def changed(command, **kwargs):
            child = create(command, **kwargs)
            self.host_boot = "00000000-0000-4000-8000-000000000020"
            return child
        with mock.patch.object(REC.subprocess, "Popen", side_effect=changed) as popen:
            with self.assertRaises(REC.CLOCK.HostTimingError): adapter(*REC.instrumentation_command())
        self.assertEqual(1, popen.call_count); self.assertIsNotNone(children[0].poll())



class SingleAttemptParserTest(unittest.TestCase):
    def reject(self, output):
        with self.assertRaises(REC.Rejected): REC.receipts(output)

    def test_duplicate_unknown_surplus_status_or_missing_framing_is_rejected(self):
        original = transcript()
        header_line = original.splitlines()[0]
        for value in [header_line + "\n" + original, original + "\nINSTRUMENTATION_CODE: -1",
                      original.replace("INSTRUMENTATION_CODE: -1", ""),
                      original.replace("INSTRUMENTATION_RESULT: stream=", "INSTRUMENTATION_RESULT: SECRET=sentinel"),
                      "INSTRUMENTATION_STATUS_CODE: 2\n" + original,
                      original.replace('"schemaVersion":1', '"schemaVersion":1,"schemaVersion":1', 1)]:
            self.reject(value)

    def test_header_types_authority_enums_counts_size_and_unknown_fields_are_closed(self):
        for change in [{"prepareCalls": True}, {"executeCalls": 1}, {"ownerCallInvoked": False},
                       {"phase": []}, {"phase": None}, {"counterScope": "INFERENCES"}, {"sourceAuthenticated": True}, {"phase": "PASS"},
                       {"secret": "SECRET"}, {"refusal": "UNKNOWN_VALUE"}, {"failureCode": "SECRET"},
                       {"environment": "PHYSICAL_DEVICE" * 500}]:
            value = header(); value.update(change); self.reject(transcript(value))

    def test_literal_restart_shape_matches_projector125_and_preserves_its_booleans(self):
        # Literal producer shape, not field names derived from the parser under test.
        restart = json.loads('{"callback":"RESTART","phase":"BEFORE_LOAD","endpointPresent":false,'
            '"snapshotPresent":false,"snapshotLimitationsMask":null,"kind":"RESTART","eligibleAsReported":false,'
            '"block":"HISTORY_UNAVAILABLE","memoryDeathsAsReported":0,"resourceDeathsAsReported":0,'
            '"degradedAsReported":false,"preloadAllowedAsReported":false}')
        value = projection(); value["resources"]["value"] = [restart]
        parsed = REC.receipts(transcript(header("OBSERVED"), value, header("RELEASED")))
        self.assertEqual(restart, parsed["partial"]["resources"]["value"][0])
        for change in [{"degradedAsReported": 0}, {"preloadAllowedAsReported": 1},
                       {"excessiveResourceDeathsAsReported": 0}, {"resourceDeathsAsReported": 65}]:
            broken = copy.deepcopy(value); broken["resources"]["value"][0].update(change)
            self.reject(transcript(header("OBSERVED"), broken, header("RELEASED")))

    def test_native_attempted_cannot_exceed_sampled_when_eog_is_unavailable(self):
        native = {"logicalTerminalAsReported": "CANCELLED", "nativeStateAsReported": "JNI_RETURNED",
                  "nativeFinishAsReported": "CANCELLED", "slotStateAsReported": "RELEASED", "snapshotStateAsReported": "FINAL",
                  "partialWorkAsReported": "UNKNOWN", "completedCallStagesMask": 0, "timeStateAsReported": "UNAVAILABLE",
                  "availableMetricsMask": (1 << 1) | (1 << 3), "apiWallClockDomain": None,
                  "metrics": {"sampledTokens": 1, "callbacksAttempted": 1}}
        value = projection(); value["native"] = {"presence": "PRESENT", "value": native}
        self.assertIsNotNone(REC.receipts(transcript(header("OBSERVED"), value, header("RELEASED")))["partial"])
        self.assertNotIn("eogTokens", native["metrics"])
        native["metrics"]["callbacksAttempted"] = 2
        self.reject(transcript(header("OBSERVED"), value, header("RELEASED")))

    def test_primary_release_retarget_or_no_profile_with_partial_is_rejected(self):
        for value in [transcript(header(), projection()), transcript(header(), release=header("RELEASED")),
                      transcript(header("OBSERVED"), projection()), transcript(header("RELEASED"), projection()),
                      transcript(header("OBSERVED"), projection(), header("OBSERVED"))]: self.reject(value)

    def test_unknown_or_authority_content_in_partial_never_enters_capture(self):
        for change in [{"prompt": "SECRET"}, {"physicalVerified": True}, {"retainedSnapshot": "true"},
                       {"actualTurnEnteredAsReported": 1}, {"sourceStateAsReported": "PASS"}]:
            value = projection(); value.update(change)
            self.reject(transcript(header("OBSERVED"), value, header("RELEASED")))

    def test_sections_are_closed_and_loss_counters_are_independent_lower_bounds(self):
        value = projection()
        value["stream"]["value"].update(acceptedChunkObservationsAtLeast=0, callbackAttemptsAtLeast=1, callbacksReturnedNormallyAtLeast=2)
        self.assertIsNotNone(REC.receipts(transcript(header("OBSERVED"), value, header("RELEASED")))["partial"])
        for replacement in [{"presence": "ABSENT", "value": {}}, {"presence": "PRESENT", "value": {"secret": "SECRET"}},
                            {"presence": "UNKNOWN", "value": None}]:
            broken = projection(); broken["stream"] = replacement
            self.reject(transcript(header("OBSERVED"), broken, header("RELEASED")))

    def test_native_section_is_closed_masked_and_keeps_unknown_states(self):
        native = {"logicalTerminalAsReported": "SUCCEEDED", "nativeStateAsReported": "UNKNOWN",
                  "nativeFinishAsReported": "UNKNOWN", "slotStateAsReported": "UNKNOWN", "snapshotStateAsReported": "UPDATING",
                  "partialWorkAsReported": "UNKNOWN", "completedCallStagesMask": 0, "timeStateAsReported": "UNAVAILABLE",
                  "availableMetricsMask": 0, "apiWallClockDomain": None, "metrics": {}}
        value = projection(); value["native"] = {"presence": "PRESENT", "value": native}
        parsed = REC.receipts(transcript(header("OBSERVED"), value, header("RELEASED")))
        self.assertEqual("UNKNOWN", parsed["partial"]["native"]["value"]["nativeStateAsReported"])
        for change in [{"servicePid": 123}, {"requestId": "SECRET"}, {"availableMetricsMask": 1},
                       {"snapshotStateAsReported": "FINAL"}, {"apiWallClockDomain": "STEADY_CLOCK_NS"},
                       {"metrics": {"promptTokens": 1}}, {"completedCallStagesMask": True}]:
            broken = copy.deepcopy(value); broken["native"]["value"].update(change)
            self.reject(transcript(header("OBSERVED"), broken, header("RELEASED")))

    def test_intrinsic_native_relations_do_not_accept_success_above_submitted_work(self):
        native = {"logicalTerminalAsReported": "SUCCEEDED", "nativeStateAsReported": "JNI_RETURNED",
                  "nativeFinishAsReported": "UNKNOWN", "slotStateAsReported": "RELEASED", "snapshotStateAsReported": "FINAL",
                  "partialWorkAsReported": "UNKNOWN", "completedCallStagesMask": 0, "timeStateAsReported": "UNAVAILABLE",
                  "availableMetricsMask": (1 << 9) | (1 << 10) | (1 << 11) | (1 << 12), "apiWallClockDomain": None,
                  "metrics": {"decodeCalls": 1, "decodeSuccessfulCalls": 1, "decodeSubmittedTokens": 1, "decodeTokensInSuccessfulCalls": 1}}
        value = projection(); value["native"] = {"presence": "PRESENT", "value": native}
        self.assertIsNotNone(REC.receipts(transcript(header("OBSERVED"), value, header("RELEASED")))["partial"])
        value["native"]["value"]["metrics"]["decodeSuccessfulCalls"] = 2
        self.reject(transcript(header("OBSERVED"), value, header("RELEASED")))

    def test_runner_failure_keeps_unknown_primary_even_when_release_has_known_driver_refusal(self):
        control = header("RELEASE_NOT_AVAILABLE")
        control.update(ownerCallInvoked=False, refusal="CORE_NOT_READY", readCalls=0)
        parsed = REC.receipts(transcript(runner_failure(), release=control, code=0))
        self.assertIsNone(parsed["header"]["ownerCallInvoked"])
        self.assertNotIn("prepareCalls", parsed["header"])
        self.assertIs(parsed["releaseControl"]["ownerCallInvoked"], False)

    def test_runner_failure_never_accepts_fake_counters_no_attempt_or_partial(self):
        for change in [{"prepareCalls": 1}, {"ownerCallInvoked": False}, {"environment": "PHYSICAL_DEVICE"},
                       {"failureCode": "SECRET"}, {"phase": "NO_ATTEMPT"}]:
            value = runner_failure(); value.update(change); self.reject(transcript(value, code=0))
        self.reject(transcript(runner_failure(), projection(), code=0))
        self.reject(transcript(runner_failure(), code=-1))

    def test_result_bundle_key_order_and_inline_stream_do_not_change_semantics(self):
        lines = transcript().splitlines()
        output = "\n".join(["INSTRUMENTATION_RESULT: stream=" + REC.STREAM_NOT_ATTEMPTED, lines[0], lines[-1]])
        self.assertEqual(REC.receipts(transcript()), REC.receipts(output))

    def test_nonfinite_negative_overflow_and_oversized_partial_are_rejected(self):
        for number in [-1, 2**63, float("nan"), True]:
            value = projection(); value["observerLoss"]["value"]["observerGlobalDroppedAtLeast"] = number
            self.reject(transcript(header("OBSERVED"), value, header("RELEASED")))
        huge = projection(); huge["secret"] = "x" * 8192
        self.reject(transcript(header("OBSERVED"), huge, header("RELEASED")))

    def test_release_control_counts_or_environment_cannot_borrow_original_partial(self):
        for change in [{"readCalls": 0}, {"ownerCallInvoked": False}, {"partialPresent": True}, {"environment": "UNVERIFIED"}]:
            control = header("RELEASED"); control.update(change)
            self.reject(transcript(header("OBSERVED"), projection(), control))


if __name__ == "__main__":
    unittest.main()
