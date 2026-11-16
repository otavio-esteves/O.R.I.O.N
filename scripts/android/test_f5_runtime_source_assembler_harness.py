#!/usr/bin/env python3
"""Synthetic closed SOURCE/HOST metadata; no acquisition or qualification evidence."""
import copy
import json
import unittest

import f5_runtime_source_assembler as assembler
import test_f5_runtime_evidence_protocol_harness as fixtures


def encoded(value):
    return json.dumps(value, ensure_ascii=True, separators=(",", ":")).encode("ascii")


def capsule():
    document = fixtures.fixture()
    source = {key: copy.deepcopy(document[key]) for key in
              ("schemaVersion", "candidate", "load", "inference", "unload", "memory")}
    source["kind"] = "ORION_MAIN_RUNTIME_CAPTURE"
    source["mainIdentity"] = {key: document["identity"][key] for key in
                              ("appPackage", "testPackage", "modelSha256", "modelSizeBytes")}
    source["session"] = {key: copy.deepcopy(document["session"][key]) for key in
                         ("bootCount", "mainPid", "aiPid", "startedElapsed", "completedElapsed",
                          "maximumSampleAgeMillis", "processInventory")}
    for chunk in source["inference"]["chunks"]:
        del chunk["requestIdSha256"]
    host = {key: document["session"][key] for key in
            ("bootCount", "bootIdSha256", "startedElapsed", "completedElapsed", "api", "abi",
             "pageSizeBytes", "deviceBuildSha256", "totalMemoryBytes")}
    host["declaredEnvironment"] = document["declaredEnvironment"]
    return source, host, document


def status():
    value = {"schemaVersion": 1, "kind": "ORION_RUNTIME_CAPTURE_STATUS", "qualification": "NOT_QUALIFIED",
             "recordState": "V1_SOURCE", "captureIntegrity": "COMPLETE", "failureCode": None,
             "ownerCallInvoked": None, "droppedAtLeast": 0, "recordingFailuresAtLeast": 0,
             "lossAccountingComplete": True}
    value.update({key: False for key in assembler.APPROVAL_FLAGS})
    return value


class RuntimeSourceAssemblerTest(unittest.TestCase):
    def setUp(self):
        self.source, self.host, self.document = capsule()
        self.status = status()
        self.expected = fixtures.identity()

    def assemble(self, source=None, header=None, expected=None, host=None):
        return assembler.assemble_json(encoded(self.source if source is None else source),
                                       encoded(self.status if header is None else header),
                                       encoded(self.expected if expected is None else expected),
                                       encoded(self.host if host is None else host))

    def incomplete(self, reason="PHASE_MISSED"):
        self.status.update(captureIntegrity="INCOMPLETE", failureCode=reason,
                           droppedAtLeast=1, lossAccountingComplete=False)

    def reject(self, **kwargs):
        with self.assertRaises(assembler.Rejected) as caught:
            self.assemble(**kwargs)
        self.assertNotIn("SECRET_SENTINEL", str(caught.exception))

    def test_complete_mapping_is_exact_unchanged_v1_and_never_authority(self):
        result = self.assemble()
        self.assertEqual(self.document, json.loads(result.runtime_json))
        self.assertEqual(self.status, json.loads(result.status_json))
        report = json.loads(result.report_json)
        self.assertEqual("ASSEMBLED_COMPLETE_METADATA", report["result"])
        self.assertEqual("CONSISTENT_CYCLE_METADATA", report["runtimeValidation"]["result"])
        self.assertEqual("UNVERIFIED_INPUT", report["evidenceTrust"])
        self.assertEqual("NOT_QUALIFIED", report["qualification"])
        for key in assembler.APPROVAL_FLAGS:
            self.assertIs(report[key], False)
        self.assertTrue(result.report_json.isascii())

    def test_missing_phase_is_absent_not_repaired(self):
        self.source["memory"].pop(3)
        self.incomplete()
        result = self.assemble()
        self.assertEqual(6, len(json.loads(result.runtime_json)["memory"]))
        self.assertEqual(["DURING_INFERENCE"], json.loads(result.report_json)["runtimeValidation"]["missingMemoryPhases"])

    def test_status_loss_remains_incomplete_even_when_v1_rows_are_complete(self):
        self.incomplete()
        report = json.loads(self.assemble().report_json)
        self.assertEqual("ASSEMBLED_INCOMPLETE_METADATA", report["result"])
        self.assertEqual("CONSISTENT_CYCLE_METADATA", report["runtimeValidation"]["result"])
        self.assertEqual("INCOMPLETE", report["captureIntegrity"])

    def test_unobserved_inference_unload_and_memory_are_not_fabricated(self):
        self.source.update(inference=None, unload=None, memory=[])
        self.incomplete()
        output = json.loads(self.assemble().runtime_json)
        self.assertIsNone(output["inference"])
        self.assertIsNone(output["unload"])
        self.assertEqual([], output["memory"])

    def test_zero_bytes_and_null_errors_are_preserved(self):
        self.source["inference"]["chunks"][0]["textDeltaBytes"] = 0
        output = json.loads(self.assemble().runtime_json)
        self.assertEqual(0, output["inference"]["chunks"][0]["textDeltaBytes"])
        self.assertIsNone(output["inference"]["chunks"][0]["errorCode"])
        self.source["inference"]["chunks"][0]["toolDeltaBytes"] = None
        self.reject()

    def test_no_load_refusal_requires_actual_false_owner_and_no_source(self):
        self.status.update(recordState="NO_LOAD_REFUSAL", captureIntegrity="UNAVAILABLE",
                           failureCode="NO_QUALIFIED_PROFILE", ownerCallInvoked=False)
        result = assembler.assemble_json(None, encoded(self.status))
        self.assertIsNone(result.runtime_json)
        self.assertEqual("NO_LOAD_REFUSAL", json.loads(result.report_json)["result"])
        for owner in (None, True, 0, "false"):
            with self.subTest(owner=owner), self.assertRaises(assembler.Rejected):
                assembler.assemble_json(None, encoded(dict(self.status, ownerCallInvoked=owner)))
        self.reject()

    def test_unavailable_status_needs_no_invented_independent_model(self):
        self.status.update(recordState="NO_V1_RECORD", captureIntegrity="UNAVAILABLE", failureCode="INTERRUPTED")
        result = assembler.assemble_json(None, encoded(self.status))
        self.assertIsNone(result.runtime_json)
        self.assertIsNone(json.loads(result.status_json)["ownerCallInvoked"])
        self.reject()

    def test_nullable_failure_is_retained_without_inventing_a_reason(self):
        self.status.update(recordState="NO_LOAD_REFUSAL", captureIntegrity="UNAVAILABLE", ownerCallInvoked=False)
        output = assembler.assemble_json(None, encoded(self.status))
        self.assertIsNone(json.loads(output.status_json)["failureCode"])
        self.status = status()
        self.incomplete()
        self.status["failureCode"] = None
        self.assertIsNone(json.loads(self.assemble().status_json)["failureCode"])

    def test_closed_incomplete_reasons_are_not_limited_to_current_encoder(self):
        self.incomplete("CAPTURE_LOSS")
        self.source["memory"].pop()
        result = self.assemble()
        self.assertEqual("CAPTURE_LOSS", json.loads(result.status_json)["failureCode"])
        self.assertEqual("ASSEMBLED_INCOMPLETE_METADATA", json.loads(result.report_json)["result"])

    def test_source_requires_independent_metadata_and_cannot_claim_no_owner_call(self):
        for expected, host in ((None, encoded(self.host)), (encoded(self.expected), None)):
            with self.assertRaises(assembler.Rejected):
                assembler.assemble_json(encoded(self.source), encoded(self.status), expected, host)
        self.status["ownerCallInvoked"] = False
        self.reject()

    def test_all_approval_flags_require_exact_false(self):
        for flag in assembler.APPROVAL_FLAGS:
            for invalid in (True, 0, None, "false"):
                with self.subTest(flag=flag, value=invalid):
                    self.reject(header=dict(self.status, **{flag: invalid}))

    def test_status_closed_types_reasons_and_integrity_consistency(self):
        for patch in ({"schemaVersion": True}, {"kind": "SECRET_SENTINEL"}, {"qualification": "QUALIFIED"},
                      {"failureCode": "SECRET_SENTINEL"}, {"recordState": "UNKNOWN"},
                      {"captureIntegrity": "UNAVAILABLE"}, {"droppedAtLeast": True},
                      {"recordingFailuresAtLeast": -1}, {"lossAccountingComplete": 1},
                      {"droppedAtLeast": 1}, {"failureCode": "PHASE_MISSED"}):
            with self.subTest(patch=patch):
                self.reject(header=dict(self.status, **patch))
        self.incomplete()
        self.reject(header=dict(self.status, lossAccountingComplete=True))

    def test_complete_status_cannot_hide_missing_v1_phase(self):
        self.source["memory"].pop()
        self.reject()

    def test_unconfirmed_unload_status_requires_unobserved_unload(self):
        self.incomplete("UNLOAD_UNCONFIRMED")
        self.reject()
        self.source["unload"] = None
        self.source["memory"] = self.source["memory"][:5]
        self.assemble()

    def test_independent_model_size_hash_packages_and_artifacts_are_strict(self):
        for key in ("modelSha256", "modelSizeBytes", "appPackage", "testPackage"):
            source = copy.deepcopy(self.source)
            source["mainIdentity"][key] = 42 if key == "modelSizeBytes" else "f" * 64
            with self.subTest(key=key):
                self.reject(source=source)
        for key in ("sourceCommit", "sourceManifestSha256", "appApkSha256", "testApkSha256"):
            with self.subTest(key=key):
                self.reject(expected=dict(self.expected, **{key: "0" * (40 if key == "sourceCommit" else 64)}))
        self.reject(expected=dict(self.expected, modelSizeBytes=True))

    def test_independent_inputs_are_closed_even_for_no_record_status(self):
        self.status.update(recordState="NO_V1_RECORD", captureIntegrity="UNAVAILABLE", failureCode="SOURCE_UNAVAILABLE")
        for expected, host in ((encoded(dict(self.expected, SECRET_SENTINEL=True)), None),
                               (None, encoded(dict(self.host, SECRET_SENTINEL=True)))):
            with self.assertRaises(assembler.Rejected):
                assembler.assemble_json(None, encoded(self.status), expected, host)

    def test_host_boot_window_platform_and_extra_main_data_are_not_repairs(self):
        for patch in ({"bootCount": 8}, {"startedElapsed": 1001}, {"completedElapsed": 7999},
                      {"api": 38}, {"api": True}, {"abi": "unknown"}, {"pageSizeBytes": 8192},
                      {"deviceBuildSha256": "0" * 64}, {"totalMemoryBytes": 1},
                      {"mainPid": 123}, {"candidate": self.source["candidate"]}):
            with self.subTest(patch=patch):
                self.reject(host=dict(self.host, **patch))
        self.host.update(startedElapsed=900, completedElapsed=9000)
        self.assertEqual(1000, json.loads(self.assemble().runtime_json)["session"]["startedElapsed"])

    def test_unknown_missing_and_extra_keys_rejected_at_each_closed_level(self):
        paths = [(), ("mainIdentity",), ("session",), ("candidate",), ("load",), ("inference",),
                 ("inference", "chunks", 0), ("unload",), ("memory", 0)]
        for path in paths:
            for missing in (False, True):
                source = copy.deepcopy(self.source)
                node = source
                for part in path:
                    node = node[part]
                if missing:
                    del node[next(iter(node))]
                else:
                    node["SECRET_SENTINEL"] = "SECRET_SENTINEL"
                with self.subTest(path=path, missing=missing):
                    self.reject(source=source)
        self.reject(header=dict(self.status, extra=False))
        self.reject(expected=dict(self.expected, extra=False))

    def test_main_integer_fields_never_accept_boolean_or_float(self):
        for path in [("schemaVersion",), ("session", "bootCount"), ("session", "mainPid"),
                     ("candidate", "threads"), ("load", "startedElapsed"),
                     ("inference", "inputBytes"), ("inference", "chunks", 0, "sequence"),
                     ("memory", 0, "mainPssBytes")]:
            for invalid in (True, 1.0):
                source = copy.deepcopy(self.source); node = source
                for part in path[:-1]:
                    node = node[part]
                node[path[-1]] = invalid
                with self.subTest(path=path, invalid=invalid):
                    self.reject(source=source)

    def test_invalid_phase_terminal_stream_and_native_facts_delegate_to_v1(self):
        changes = [lambda s: s["memory"][0].update(phase="UNKNOWN"),
                   lambda s: s["memory"][0].update(mainPssBytes=0),
                   lambda s: s["memory"][0].update(aggregatePssBytes=1),
                   lambda s: s["memory"][-1].update(nativeRuntimeLoaded=False),
                   lambda s: s["inference"]["chunks"][0].update(sequence=1),
                   lambda s: s["inference"]["chunks"][-1].update(receivedElapsed=4999),
                   lambda s: s["inference"]["chunks"][0].update(textDeltaBytes=65537),
                   lambda s: s["inference"].update(terminal="UNKNOWN"),
                   lambda s: s["inference"].update(failureCode="SECRET_SENTINEL"),
                   lambda s: s["unload"].update(completedElapsed=9000)]
        for change in changes:
            source = copy.deepcopy(self.source); change(source)
            self.reject(source=source)

    def test_memory_array_limit_and_factored_chunk_shape_are_explicit(self):
        self.source["memory"] = [copy.deepcopy(self.source["memory"][0]) for _ in range(129)]
        self.incomplete()
        self.reject()
        source, _, _ = capsule()
        source["inference"]["chunks"][0]["requestIdSha256"] = source["inference"]["requestIdSha256"]
        self.reject(source=source)

    def test_callback_timeout_and_main_timeout_remain_different_original_facts(self):
        self.source["inference"]["terminal"] = "TIMED_OUT"
        self.source["inference"]["chunks"][-1]["finishReason"] = "TIMED_OUT"
        self.assertEqual("TIMED_OUT", json.loads(self.assemble().report_json)["runtimeValidation"]["inferenceTerminal"])
        self.source["inference"].update(terminalSource="MAIN_LIFECYCLE", chunks=[])
        self.reject()
        self.source["inference"]["terminalElapsed"] = 5500
        self.source["memory"] = self.source["memory"][:4]
        self.incomplete()
        self.assemble()

    def test_process_death_is_incomplete_without_unload_or_later_ai_rows(self):
        self.source["inference"].update(terminal="PROCESS_DIED", terminalSource="MAIN_LIFECYCLE", chunks=[])
        self.source["unload"] = None
        self.source["memory"] = self.source["memory"][:4]
        self.incomplete()
        report = json.loads(self.assemble().report_json)
        self.assertEqual("PROCESS_DIED", report["runtimeValidation"]["inferenceTerminal"])

    def test_duplicate_keys_and_nonfinite_values_are_rejected_without_echo(self):
        for field in ("source", "status", "expected", "host"):
            values = [encoded(self.source), encoded(self.status), encoded(self.expected), encoded(self.host)]
            index = ("source", "status", "expected", "host").index(field)
            values[index] = b'{"SECRET_SENTINEL":0,"SECRET_SENTINEL":1}'
            with self.subTest(field=field), self.assertRaises(assembler.Rejected) as caught:
                assembler.assemble_json(*values)
            self.assertNotIn("SECRET_SENTINEL", str(caught.exception))
        for number in (b"NaN", b"Infinity", b"1e999"):
            data = encoded(self.source).replace(b'"temperature":0.0', b'"temperature":' + number)
            with self.assertRaises(assembler.Rejected):
                assembler.assemble_json(data, encoded(self.status), encoded(self.expected), encoded(self.host))

    def test_source_and_status_ascii_utf8_size_and_json_bounds(self):
        source = encoded(self.source); header = encoded(self.status)
        self.assertLess(len(source), assembler.MAX_SOURCE_BYTES)
        assembler.assemble_json(source + b" " * (assembler.MAX_SOURCE_BYTES - len(source)),
                                header + b" " * (assembler.MAX_STATUS_BYTES - len(header)),
                                encoded(self.expected), encoded(self.host))
        for bad in (source + b" " * assembler.MAX_SOURCE_BYTES, b"", b"\xff", b'"\xc3\xa9"',
                    source + b"{}", b"[" * 14 + b"0" + b"]" * 14,
                    encoded([None] * 4098), b'{"x":"\\ud800"}'):
            with self.subTest(size=len(bad)), self.assertRaises(assembler.Rejected):
                assembler.assemble_json(bad, header, encoded(self.expected), encoded(self.host))
        with self.assertRaises(assembler.Rejected):
            assembler.assemble_json(source, header + b" " * assembler.MAX_STATUS_BYTES,
                                    encoded(self.expected), encoded(self.host))

    def test_input_bytes_are_not_mutated_and_output_contains_no_unmapped_material(self):
        values = tuple(map(encoded, (self.source, self.status, self.expected, self.host)))
        before = tuple(values)
        output = assembler.assemble_json(*values)
        self.assertEqual(before, values)
        for name in ("requestId", "prompt", "path", "ownerCallInvoked"):
            self.assertNotIn(name, json.loads(output.runtime_json))
        self.assertLessEqual(len(output.runtime_json), assembler.MAX_OUTPUT_BYTES)
        self.assertIsInstance(output.runtime_json, bytes)
        self.assertLessEqual(len(output.report_json), assembler.MAX_REPORT_BYTES)


if __name__ == "__main__":
    unittest.main()
