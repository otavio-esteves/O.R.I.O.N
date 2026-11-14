#!/usr/bin/env python3
"""HOST-only synthetic receipts and source contracts; no Android execution."""
import contextlib
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import re
import tempfile
import unittest

import f5_exit_capture_protocol as protocol

ROOT = Path(__file__).resolve().parents[2]
BRIDGE = ROOT / "app/src/exitEvidenceCaptureTarget/kotlin/network/orion/app/resources/OrionExitEvidenceCaptureBridge.kt"
RUNNER = ROOT / "app/src/exitEvidenceCaptureAndroidTest/java/network/orion/app/compatibility/OrionExitEvidenceCaptureInstrumentation.java"


def record(pid=42, timestamp=10500, reason=13, category="OTHER_ANON_SWAP_MARKER", classification="MEMORY_LIMITER"):
    value = {"pid": pid, "processName": protocol.AI, "timestampWallMillis": timestamp, "reason": reason,
             "descriptionEvidence": category, "classification": classification}
    key = "|".join(str(value[k]) for k in ("pid", "processName", "timestampWallMillis", "reason", "descriptionEvidence", "classification"))
    return dict(value, recordSha256=hashlib.sha256(key.encode()).hexdigest())


def fixture(live=False, died=False):
    value = {"schemaVersion": 1, "kind": "ORION_F5_EXIT_CAPTURE_ONLY", "packageName": protocol.PACKAGE,
             "declaredEnvironment": "ANDROID_EMULATOR", "environment": "ANDROID_EMULATOR",
             "runtimeEvidence": "NON_DEFINITIVE", "qualification": "NOT_QUALIFIED", "mainPid": 41,
             "result": "AI_NOT_AVAILABLE", "correlation": "NO_LIVE_OBSERVATION", "matchedRecordSha256": None,
             "bindingOutcome": "NOT_BOUND", "binderDeathCleanup": "NOT_LINKED", "firstLiveObservedElapsed": None,
             "identity": {"source": "UNAVAILABLE_NOT_EMBEDDED", "appApk": "NOT_CAPTURED", "testApk": "NOT_CAPTURED", "model": "NOT_OBSERVED"},
             "policy": {k: "UNAVAILABLE_NOT_EXPOSED" for k in ("state", "revision", "fence", "cooldown")},
             "config": {"observations": 3, "intervalMillis": 250, "historyLimit": 2},
             "platform": {"apiLevel": 35, "buildFingerprintSha256": "a" * 64, "abi": "x86_64",
                          "bootSource": "SETTINGS_GLOBAL_BOOT_COUNT", "dedicatedReason": None, "dedicatedReasonStatus": "UNAVAILABLE"},
             "window": {"start": {"bootCount": 7, "elapsedMillis": 1000, "wallMillis": 10000},
                        "end": {"bootCount": 7, "elapsedMillis": 2000, "wallMillis": 11000}, "wallClockState": "CONTINUITY_NOT_PROVABLE"},
             "liveObservations": [], "historyBefore": {"status": "AVAILABLE", "queriedCount": 0, "records": []},
             "historyAfter": {"status": "AVAILABLE", "queriedCount": 0, "records": []}, "cleanup": "NOT_REGISTERED",
             **{key: False for key in protocol.FLAGS}}
    if live or died:
        if not died:
            value["config"]["observations"] = 1
        value.update(bindingOutcome="CONNECTED", binderDeathCleanup="ALREADY_DEAD" if died else "UNLINKED", cleanup="UNBOUND",
                     firstLiveObservedElapsed=1110, result="AI_PROCESS_DIED" if died else "AI_STILL_ALIVE",
                     correlation="WINDOW_MATCH_CANDIDATE" if died else "LIVE_PROCESS_NOT_DEAD")
        value["liveObservations"] = [{"pid": 42, "processName": protocol.AI, "bootCount": 7, "readStartedElapsed": 1100,
                                      "readCompletedElapsed": 1110, "sampledElapsed": 1105, "observedWallMillis": 10110,
                                      "serviceState": "UNLOADED", "nativeRuntimeLoaded": False, "nativeValidationCompleted": False}]
    if died:
        event = record()
        value["historyAfter"] = {"status": "AVAILABLE", "queriedCount": 1, "records": [event]}
        value["matchedRecordSha256"] = event["recordSha256"]
    return value


def validate(value):
    return protocol.validate_json(json.dumps(value).encode())


class ExitCaptureProtocolTest(unittest.TestCase):
    def assert_valid(self, value):
        result = validate(value)
        self.assertEqual("UNVERIFIED_INPUT", result["environment"])
        self.assertEqual("NOT_QUALIFIED", result["qualification"])
        self.assertEqual("NON_DEFINITIVE", result["runtimeEvidence"])
        for flag in protocol.FLAGS:
            self.assertIs(result[flag], False)
        return result

    def test_absent_live_and_paired_death_are_unverified_captures(self):
        for value in (fixture(), fixture(live=True), fixture(died=True)):
            self.assertEqual("CONSISTENT_CAPTURE_METADATA", self.assert_valid(value)["status"])

    def test_source_model_policy_and_authority_cannot_be_claimed(self):
        for section, key, changed in (("policy", "fence", 400), ("policy", "revision", 1),
                                      ("identity", "model", "a" * 64), ("identity", "source", "main"),
                                      (None, "profileQualified", True), (None, "memoryLimiterVerified", True),
                                      (None, "policyMutationIssued", True)):
            value = fixture()
            (value[section] if section else value)[key] = changed
            with self.subTest(key=key), self.assertRaises(protocol.Rejected):
                validate(value)

    def test_physical_build_candidate_is_not_physical_verification(self):
        value = fixture()
        value.update(declaredEnvironment="PHYSICAL_DEVICE", environment="PHYSICAL_BUILD_CANDIDATE")
        value["platform"]["abi"] = "aarch64"
        self.assert_valid(value)
        value["platform"]["abi"] = "x86_64"
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_false_binding_and_connection_timeout_retain_history_only(self):
        for outcome in ("NOT_BOUND", "CONNECTION_TIMEOUT", "NO_ENDPOINT"):
            value = fixture()
            value["bindingOutcome"] = outcome
            self.assert_valid(value)
            value["liveObservations"] = fixture(live=True)["liveObservations"]
            value["firstLiveObservedElapsed"] = 1110
            with self.assertRaises(protocol.Rejected):
                validate(value)

    def test_dedicated_reason_and_other_marker_use_current_classifier(self):
        value = fixture(died=True)
        event = record(reason=25, category="UNAVAILABLE")
        value["platform"].update(dedicatedReason=25, dedicatedReasonStatus="EXPOSED")
        value["historyAfter"]["records"] = [event]
        value["matchedRecordSha256"] = event["recordSha256"]
        self.assert_valid(value)
        value["platform"]["dedicatedReason"] = 3
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_ordinary_signalled_low_memory_and_crash_capture_does_not_prove_limiter(self):
        for reason in (2, 3, 4, 5, 9):
            value = fixture(died=True)
            event = record(reason=reason, category="NO_MATCH", classification=protocol.KINDS[reason])
            value["historyAfter"]["records"] = [event]
            value["matchedRecordSha256"] = event["recordSha256"]
            self.assert_valid(value)

    def test_sanitized_marker_cannot_relabel_non_other_reason(self):
        value = fixture(died=True)
        value["historyAfter"]["records"] = [record(reason=3)]
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_old_record_is_not_new_paired_evidence(self):
        value = fixture(died=True)
        value["historyBefore"] = copy.deepcopy(value["historyAfter"])
        value.update(correlation="NO_MATCHING_NEW_EXIT", matchedRecordSha256=None)
        self.assert_valid(value)
        value["correlation"] = "WINDOW_MATCH_CANDIDATE"
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_changed_classification_of_existing_incident_is_not_a_new_death(self):
        value = fixture(died=True)
        value["historyBefore"] = {"status": "AVAILABLE", "queriedCount": 1,
                                  "records": [record(category="UNAVAILABLE", classification="OTHER_SYSTEM")]}
        value.update(correlation="NO_MATCHING_NEW_EXIT", matchedRecordSha256=None)
        self.assert_valid(value)
        value["historyAfter"]["records"].append(value["historyBefore"]["records"][0])
        value["historyAfter"]["queriedCount"] = 2
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_multiple_matching_records_are_explicitly_ambiguous(self):
        value = fixture(died=True)
        value["historyAfter"]["records"].append(record(timestamp=10501))
        value["historyAfter"]["queriedCount"] = 2
        value.update(correlation="AMBIGUOUS_EXIT_RECORDS", matchedRecordSha256=None)
        self.assert_valid(value)

    def test_pid_mismatch_or_outside_wall_window_is_not_a_match(self):
        for event in (record(pid=43), record(timestamp=10109), record(timestamp=11001)):
            value = fixture(died=True)
            value["historyAfter"]["records"] = [event]
            value.update(correlation="NO_MATCHING_NEW_EXIT", matchedRecordSha256=None)
            self.assert_valid(value)

    def test_history_overflow_is_not_complete_even_when_ai_record_matches(self):
        value = fixture(died=True)
        value["historyAfter"].update(status="TRUNCATED", queriedCount=3)
        value.update(correlation="HISTORY_INSUFFICIENT", matchedRecordSha256=None)
        self.assert_valid(value)
        value["historyAfter"]["queriedCount"] = 2
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_history_unavailable_retains_no_partial_or_fake_empty_success(self):
        value = fixture(died=True)
        value["historyBefore"].update(status="UNAVAILABLE", queriedCount=None)
        value.update(correlation="HISTORY_INSUFFICIENT", matchedRecordSha256=None)
        self.assert_valid(value)
        value["historyBefore"]["queriedCount"] = 0
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_duplicate_records_and_digest_tampering_are_rejected(self):
        value = fixture(died=True)
        value["historyAfter"]["records"] *= 2
        value["historyAfter"]["queriedCount"] = 2
        with self.assertRaises(protocol.Rejected):
            validate(value)
        value = fixture(died=True)
        value["historyAfter"]["records"][0]["pid"] = 43
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_boot_missing_and_boot_change_never_compare_end_to_old_epoch(self):
        for boot, result in ((None, "BOOT_UNAVAILABLE"), (8, "BOOT_CHANGED")):
            value = fixture(died=True)
            value["window"]["end"].update(bootCount=boot, elapsedMillis=1)
            value.update(result=result, correlation="BOOT_INSUFFICIENT", matchedRecordSha256=None)
            self.assert_valid(value)

    def test_wrong_live_boot_pid_name_first_observation_or_stale_sample_is_rejected(self):
        for key, changed in (("bootCount", 8), ("pid", 41), ("processName", "network.orion:ai"),
                             ("sampledElapsed", 1099), ("readCompletedElapsed", 2100), ("pid", True)):
            value = fixture(live=True)
            value["liveObservations"][0][key] = changed
            with self.subTest(key=key), self.assertRaises(protocol.Rejected):
                validate(value)
        value = fixture(live=True)
        value["firstLiveObservedElapsed"] = 1000
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_replacement_pid_never_continues_the_old_live_session(self):
        value = fixture(live=True)
        value["config"]["observations"] = 2
        value["liveObservations"].append(dict(value["liveObservations"][0], pid=43, readStartedElapsed=1200,
                                               sampledElapsed=1205, readCompletedElapsed=1210))
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_result_counts_and_binding_cleanup_cannot_claim_an_unobserved_live_process(self):
        for field, changed in (("result", "AI_STILL_ALIVE"), ("binderDeathCleanup", "UNLINKED")):
            value = fixture()
            value[field] = changed
            with self.assertRaises(protocol.Rejected):
                validate(value)
        value = fixture(live=True)
        value["config"]["observations"] = 2
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_backward_wall_step_is_explicit_and_blocks_candidate(self):
        value = fixture(died=True)
        value["liveObservations"][0]["observedWallMillis"] = 9000
        value["window"]["wallClockState"] = "BACKWARD_STEP_OBSERVED"
        value.update(correlation="WALL_WINDOW_INSUFFICIENT", matchedRecordSha256=None)
        self.assert_valid(value)
        value["window"]["wallClockState"] = "CONTINUITY_NOT_PROVABLE"
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_native_wrapper_is_observed_without_claiming_model_residency(self):
        value = fixture(live=True)
        value["liveObservations"][0].update(nativeRuntimeLoaded=True, nativeValidationCompleted=True)
        self.assert_valid(value)
        value["liveObservations"][0]["nativeRuntimeLoaded"] = False
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_loaded_and_inferencing_states_require_both_native_flags(self):
        for state in ("LOADED", "INFERENCING"):
            for runtime, validation in ((False, False), (False, True), (True, False), (True, True)):
                value = fixture(live=True)
                value["liveObservations"][0].update(serviceState=state, nativeRuntimeLoaded=runtime,
                                                    nativeValidationCompleted=validation)
                with self.subTest(state=state, runtime=runtime, validation=validation):
                    if runtime and validation:
                        self.assert_valid(value)
                    else:
                        with self.assertRaises(protocol.Rejected):
                            validate(value)

    def test_rejection_metadata_preserves_unknown_environment_and_false_authority(self):
        value = {"schemaVersion": 1, "kind": "ORION_F5_EXIT_CAPTURE_REJECTION", "environment": "UNVERIFIED",
                 "qualification": "NOT_QUALIFIED", "runtimeEvidence": "NON_DEFINITIVE", "result": "REJECTED",
                 "failureCode": "CAPTURE_TIMEOUT", "cleanup": "UNBOUND", **{k: False for k in protocol.FLAGS}}
        self.assertEqual("CONSISTENT_REJECTION_METADATA", self.assert_valid(value)["status"])
        value["failureCode"] = "private exception"
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_counts_and_numbers_are_bounded_and_not_boolean(self):
        for section, key, changed in (("config", "observations", 11), ("config", "historyLimit", 17),
                                      ("config", "intervalMillis", 99), ("config", "observations", True),
                                      ("platform", "apiLevel", 38), ("platform", "dedicatedReason", True)):
            value = fixture()
            value[section][key] = changed
            with self.subTest(key=key), self.assertRaises(protocol.Rejected):
                validate(value)

    def test_unknown_raw_content_and_fake_policy_fields_are_rejected(self):
        for section, key in ((None, "limiterV1Consistent"), ("identity", "modelPath"), ("policy", "qualifiedPlans")):
            value = fixture()
            (value[section] if section else value)[key] = "secret"
            with self.assertRaises(protocol.Rejected):
                validate(value)
        value = fixture(died=True)
        value["historyAfter"]["records"][0]["description"] = "raw description"
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_duplicate_nonfinite_float_utf8_complexity_and_size_rejections(self):
        for data in (b'{"x":1,"x":2}', b'{"x":{"a":1,"a":2}}', b'{"x":NaN}', b'{"x":1e999}',
                     b'{"x":1.0}', b'\xff', b'{}{}', b' ' * 65537, b'[' * 20 + b']' * 20):
            with self.subTest(data=data[:20]), self.assertRaises(protocol.Rejected):
                protocol.validate_json(data)

    def test_cli_fixed_errors_regular_file_only_no_content_echo(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "receipt.json"
            path.write_text(json.dumps(fixture()))
            for selected, expected in ((path, 0), (root / "missing", 2), (root, 2)):
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    self.assertEqual(expected, protocol.main([str(selected)]))
                self.assertNotIn(temporary, output.getvalue())
            (root / "link").symlink_to(path)
            os.mkfifo(root / "fifo")
            for selected in (root / "link", root / "fifo"):
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(2, protocol.main([str(selected)]))
            path.write_text('{"secret":"DO_NOT_ECHO"}')
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(2, protocol.main([str(path)]))
            self.assertNotIn("DO_NOT_ECHO", output.getvalue())


class ExitCaptureSourceContractTest(unittest.TestCase):
    def test_only_existing_read_methods_and_no_trace_or_business_dependencies(self):
        text = BRIDGE.read_text()
        self.assertEqual({"getProtocolInfo", "getStatus", "healthCheck"}, set(re.findall(r'service\.([A-Za-z]+)\(', text)))
        for forbidden in ("getTraceInputStream", "setProcessStateSummary", "OrionDatabase", "OrionCortexRuntimeOwner",
                          "OrionMinimumResourceGovernor", "qualifiedPlans", "getRunningAppProcesses"):
            self.assertNotIn(forbidden, text)
        self.assertIn('getHistoricalProcessExitReasons(TARGET, 0, limit + 1).take(limit + 1)', text)
        self.assertIn('OrionProcessExitClassifier.classify(', text)
        self.assertNotRegex(text, r'put\("(?:description|trace)"')

    def test_no_autostart_or_rebind_and_late_callbacks_cannot_replace_endpoint(self):
        text = RUNNER.read_text()
        self.assertEqual(1, text.count('context.bindService(intent, connection, 0)'))
        for forbidden in ('Context.BIND_AUTO_CREATE', '.startService(', '.startForegroundService('):
            self.assertNotIn(forbidden, text)
        self.assertIn('acceptingConnection.compareAndSet(true, false)', text)
        self.assertIn('final IBinder pinnedEndpoint', text)
        self.assertIn('if (bindAttempted)', text)
        self.assertIn('context.unbindService(connection)', text)
        self.assertIn('pending.get(30, TimeUnit.SECONDS)', text)
        self.assertLess(text.index('getMethod("validate"'), text.index('context.bindService('))

    def test_live_death_keeps_old_window_and_closes_history_without_rebinding(self):
        text = BRIDGE.read_text()
        self.assertIn('binder.linkToDeath(recipient, 0)', text)
        self.assertIn('binder).unlinkToDeath(recipient, 0)', text)
        self.assertIn('pinnedPid != null && pinnedPid != pid', text)
        self.assertIn('if (readEnd.boot != firstBoot)', text)
        self.assertIn('liveEnded.isCanceled', text)
        self.assertLess(text.index('val before = history('), text.index('service.getProtocolInfo()'))
        self.assertGreater(text.index('val after = history('), text.index('unlinkToDeath'))
        self.assertIn('"firstLiveObservedElapsed"', text)
        self.assertNotIn('"startedElapsedMillis"', text)
        self.assertIn('"SETTINGS_GLOBAL_BOOT_COUNT"', text)
        self.assertIn('require(state !in setOf("LOADED", "INFERENCING") || runtime && validation)', text)

    def test_qualification_scope_keep_and_framework_runner_types(self):
        keep = (BRIDGE.parents[5] / 'proguard-rules.pro').read_text()
        self.assertIn('OrionExitEvidenceCaptureBridge', keep)
        self.assertIn('android.os.CancellationSignal, android.os.CancellationSignal', keep)
        self.assertNotIn('network.orion.ai.', RUNNER.read_text().split('import ')[-1].split('/**')[0])


if __name__ == "__main__":
    unittest.main()
