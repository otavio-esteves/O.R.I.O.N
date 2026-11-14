#!/usr/bin/env python3
"""Synthetic offline metadata fixtures; no Android execution or physical evidence."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("f5_runtime_evidence_protocol.py")
SPEC = importlib.util.spec_from_file_location("orion_runtime_evidence", SCRIPT)
PROTOCOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROTOCOL)


def identity():
    return {"sourceCommit": "1" * 40, "sourceManifestSha256": "2" * 64,
            "appPackage": "network.orion.qualification", "testPackage": "network.orion.qualification.test",
            "appApkSha256": "3" * 64, "testApkSha256": "4" * 64,
            "modelSha256": "5" * 64, "modelSizeBytes": 1024**3}


def fixture():
    phases = ("BEFORE_LOAD", "DURING_LOAD", "AFTER_LOAD", "DURING_INFERENCE", "AFTER_TERMINAL",
              "DURING_UNLOAD", "AFTER_UNLOAD")
    states = ("UNLOADED", "LOADING", "LOADED", "INFERENCING", "LOADED", "UNLOADING", "UNLOADED")
    rows = []
    for index, (phase, state) in enumerate(zip(phases, states)):
        start = 1100 + 1000 * index
        # Inference occupies 4000..5000; subsequent phases begin at 5100.
        rows.append({"phase": phase, "startedElapsed": start, "completedElapsed": start + 10,
                     "bootCount": 7, "mainPid": 123, "aiPid": 456, "mainSampledElapsed": start,
                     "aiSampledElapsed": start + 5, "mainPssBytes": 64 * 1024**2,
                     "aiPssBytes": 128 * 1024**2, "aggregatePssBytes": 192 * 1024**2,
                     "mainNativeIncluded": True, "aiNativeIncluded": True,
                     "source": "SELF_DEBUG_MEMORY_INFO_PSS", "serviceState": state,
                     "nativeRuntimeLoaded": index != 0, "nativeValidationCompleted": index >= 2})
    return {"schemaVersion": 1, "kind": "ORION_F5_RUNTIME_EVIDENCE",
            "declaredEnvironment": "SYNTHETIC_INPUT", "identity": identity(),
            "session": {"bootCount": 7, "bootIdSha256": "6" * 64, "mainPid": 123, "aiPid": 456,
                        "startedElapsed": 1000, "completedElapsed": 8000, "maximumSampleAgeMillis": 1000,
                        "processInventory": ["network.orion.qualification", "network.orion.qualification:ai"],
                        "api": 35, "abi": "arm64-v8a", "pageSizeBytes": 4096,
                        "deviceBuildSha256": "7" * 64, "totalMemoryBytes": 8 * 1024**3},
            "candidate": {"contextTokens": 2048, "threads": 2, "maxTokens": 32, "temperature": 0.0},
            "load": {"requestIdSha256": "8" * 64, "modelSha256": "5" * 64,
                     "startedElapsed": 2000, "completedElapsed": 3000, "terminal": "SUCCEEDED"},
            "inference": {"requestIdSha256": "9" * 64, "transportRequestIdSha256": "a" * 64,
                          "modelSha256": "5" * 64, "startedElapsed": 4000, "terminalElapsed": 5000,
                          "deadlineElapsed": 5500, "inputBytes": 32, "terminal": "SUCCEEDED",
                          "terminalSource": "CALLBACK", "failureCode": None,
                          "chunks": [
                              {"requestIdSha256": "9" * 64, "sequence": 0, "receivedElapsed": 4200,
                               "textDeltaBytes": 8, "toolDeltaBytes": 0, "isFinal": False,
                               "finishReason": None, "errorCode": None},
                              {"requestIdSha256": "9" * 64, "sequence": 1, "receivedElapsed": 5000,
                               "textDeltaBytes": 0, "toolDeltaBytes": 0, "isFinal": True,
                               "finishReason": "SUCCEEDED", "errorCode": None}]},
            "unload": {"startedElapsed": 6000, "completedElapsed": 7000, "result": "UNLOADED"},
            "memory": rows}


class RuntimeEvidenceProtocolTest(unittest.TestCase):
    def setUp(self):
        self.data = fixture()

    def validate(self, value=None, expected=None):
        return PROTOCOL.validate_json(json.dumps(self.data if value is None else value).encode(),
                                      json.dumps(identity() if expected is None else expected).encode())

    def reject(self, mutate, code=None):
        mutate(self.data)
        with self.assertRaises(PROTOCOL.Rejected) as caught:
            self.validate()
        if code:
            self.assertEqual(code, str(caught.exception))

    def test_complete_metadata_never_becomes_observed_or_qualified(self):
        result = self.validate()
        self.assertEqual("CONSISTENT_CYCLE_METADATA", result["result"])
        self.assertEqual("UNVERIFIED_INPUT", result["evidenceTrust"])
        self.assertEqual("NOT_QUALIFIED", result["qualification"])
        self.assertEqual("SYNTHETIC_INPUT", result["declaredEnvironment"])
        self.assertTrue(result["successfulInferenceReported"])
        self.assertEqual(8, result["streamedContentBytes"])
        self.assertEqual("UNAVAILABLE_NO_PRODUCER", result["tokenMetrics"])
        self.assertEqual("UNAVAILABLE_NO_PRODUCER", result["prefillDecodeMetrics"])
        for name in ("provenanceAuthenticated", "physicalObservationVerified", "profileQualified",
                     "productionInferenceEnabled", "fullF5GateClosed", "fullSoakGateClosed"):
            self.assertIs(result[name], False)

    def test_declared_physical_label_is_also_unverified(self):
        self.data["declaredEnvironment"] = "PHYSICAL_DEVICE"
        self.assertFalse(self.validate()["physicalObservationVerified"])

    def test_exact_expected_identity_including_each_artifact_and_model_size(self):
        for field in identity():
            with self.subTest(field=field):
                expected = identity()
                expected[field] = expected[field] + 1 if type(expected[field]) is int else "f" * len(expected[field])
                with self.assertRaises(PROTOCOL.Rejected):
                    self.validate(expected=expected)

    def test_zero_hash_wrong_package_and_model_identity_rejected(self):
        for mutate in (lambda d: d["identity"].update(modelSha256="0" * 64),
                       lambda d: d["identity"].update(testPackage="network.orion.test"),
                       lambda d: d["load"].update(modelSha256="f" * 64),
                       lambda d: d["inference"].update(modelSha256="f" * 64)):
            self.data = fixture()
            self.reject(mutate)

    def test_missing_phase_is_incomplete_without_fabricated_reading(self):
        self.data["memory"].pop(3)
        result = self.validate()
        self.assertEqual("CONSISTENT_INCOMPLETE_METADATA", result["result"])
        self.assertEqual(["DURING_INFERENCE"], result["missingMemoryPhases"])
        self.assertNotIn("peakPssBytes", result)

    def test_zero_samples_and_unfinished_after_load_are_preserved_as_incomplete(self):
        self.data.update(inference=None, unload=None, memory=[])
        result = self.validate()
        self.assertEqual("CONSISTENT_INCOMPLETE_METADATA", result["result"])
        self.assertFalse(result["successfulInferenceReported"])

    def test_failed_load_cannot_have_inference_or_loaded_phase(self):
        self.reject(lambda d: d["load"].update(terminal="FAILED"))
        self.data = fixture()
        self.data["load"]["terminal"] = "FAILED"
        self.data.update(inference=None, unload=None, memory=self.data["memory"][:2])
        self.assertEqual("CONSISTENT_INCOMPLETE_METADATA", self.validate()["result"])

    def test_failed_cancelled_and_timed_out_inference_are_not_success(self):
        for terminal in ("FAILED", "CANCELLED", "TIMED_OUT"):
            self.data = fixture()
            inference = self.data["inference"]
            inference.update(terminal=terminal, terminalSource="MAIN_LIFECYCLE", chunks=inference["chunks"][:1])
            if terminal == "TIMED_OUT":
                inference["deadlineElapsed"] = 4900
            result = self.validate()
            self.assertFalse(result["successfulInferenceReported"])
            self.assertEqual(terminal, result["inferenceTerminal"])

    def test_process_death_ends_attempt_without_unload_or_postdeath_ai_reading(self):
        self.data["inference"].update(terminal="PROCESS_DIED", terminalSource="MAIN_LIFECYCLE",
                                      chunks=self.data["inference"]["chunks"][:1])
        with self.assertRaises(PROTOCOL.Rejected):
            self.validate()
        self.data.update(unload=None, memory=self.data["memory"][:4])
        self.assertEqual("CONSISTENT_INCOMPLETE_METADATA", self.validate()["result"])
        self.data["memory"][-1]["completedElapsed"] = 5001
        with self.assertRaises(PROTOCOL.Rejected):
            self.validate()

    def test_unload_failure_cannot_claim_post_unload_state(self):
        self.reject(lambda d: d["unload"].update(result="FAILED"))
        self.data["memory"].pop()
        self.assertEqual("CONSISTENT_INCOMPLETE_METADATA", self.validate()["result"])

    def test_sequences_identity_duplicate_and_terminal_finality_rejected(self):
        mutations = (lambda d: d["inference"]["chunks"][0].update(sequence=1),
                     lambda d: d["inference"]["chunks"][1].update(sequence=0),
                     lambda d: d["inference"]["chunks"][0].update(requestIdSha256="e" * 64),
                     lambda d: d["inference"]["chunks"].append(copy.deepcopy(d["inference"]["chunks"][-1])),
                     lambda d: d["inference"]["chunks"][0].update(isFinal=True, finishReason="SUCCEEDED"),
                     lambda d: d["inference"]["chunks"][-1].update(finishReason="FAILED"),
                     lambda d: d["inference"].update(terminalSource="MAIN_LIFECYCLE"),
                     lambda d: d["inference"].update(transportRequestIdSha256="8" * 64))
        for mutate in mutations:
            self.data = fixture()
            self.reject(mutate)

    def test_late_callback_and_invalid_deadline_rejected(self):
        for mutate in (lambda d: d["inference"]["chunks"][-1].update(receivedElapsed=5001),
                       lambda d: d["inference"].update(deadlineElapsed=5000),
                       lambda d: d["inference"].update(deadlineElapsed=4000),
                       lambda d: d["inference"].update(deadlineElapsed=124001),
                       lambda d: d["inference"].update(terminal="TIMED_OUT")):
            self.data = fixture()
            self.reject(mutate)

    def test_time_boot_pid_inventory_and_aggregate_are_bound(self):
        mutations = (lambda d: d["memory"][0].update(bootCount=8),
                     lambda d: d["memory"][0].update(aiPid=457),
                     lambda d: d["session"].update(aiPid=123),
                     lambda d: d["session"]["processInventory"].append("network.orion.qualification:voice"),
                     lambda d: d["memory"][0].update(aggregatePssBytes=1),
                     lambda d: d["memory"][0].update(aiPssBytes=0),
                     lambda d: d["memory"][0].update(mainSampledElapsed=1099),
                     lambda d: d["memory"][0].update(aiSampledElapsed=1111),
                     lambda d: d["memory"][0].update(completedElapsed=2100),
                     lambda d: d["memory"][0].update(phase="AFTER_UNLOAD"),
                     lambda d: d["memory"].reverse(),
                     lambda d: d["load"].update(completedElapsed=4001),
                     lambda d: d["unload"].update(startedElapsed=4999))
        for mutate in mutations:
            self.data = fixture()
            self.reject(mutate)

    def test_native_coverage_and_resident_wrapper_are_distinct(self):
        self.assertTrue(self.data["memory"][-1]["nativeRuntimeLoaded"])
        for field in ("mainNativeIncluded", "aiNativeIncluded"):
            self.data = fixture()
            self.reject(lambda d: d["memory"][2].update({field: False}))
        self.data = fixture()
        self.reject(lambda d: d["memory"][-1].update(nativeRuntimeLoaded=False))
        self.data = fixture()
        self.reject(lambda d: d["memory"][1].update(serviceState="LOADED"))

    def test_types_bounds_and_content_fields_are_closed(self):
        mutations = (lambda d: d.update(qualifiedPlans=[]),
                     lambda d: d["inference"].update(prompt="private prompt"),
                     lambda d: d["inference"]["chunks"][0].update(textDelta="private output"),
                     lambda d: d["load"].update(error="raw failure"),
                     lambda d: d.update(schemaVersion=True),
                     lambda d: d["candidate"].update(threads=True),
                     lambda d: d["candidate"].update(contextTokens=32769),
                     lambda d: d["candidate"].update(maxTokens=4097),
                     lambda d: d["candidate"].update(temperature=True),
                     lambda d: d["candidate"].update(temperature=10**500),
                     lambda d: d["inference"].update(actualTokens=8),
                     lambda d: d["memory"][0].update(mainPssBytes=1.0),
                     lambda d: d["memory"][0].update(mainNativeIncluded=1),
                     lambda d: d["inference"]["chunks"][0].update(textDeltaBytes=65537),
                     lambda d: d["inference"].update(failureCode="private failure"),
                     lambda d: d["inference"].update(chunks=[{}] * (PROTOCOL.MAX_CHUNKS + 1)),
                     lambda d: d.update(memory=[{}] * (PROTOCOL.MAX_SAMPLES + 1)))
        for mutate in mutations:
            self.data = fixture()
            self.reject(mutate)

    def test_duplicate_keys_nonfinite_encoding_depth_and_size_guards(self):
        cases = (b'{"a":1,"a":2}', b'{"x":{"a":1,"a":2}}', b'NaN', b'Infinity', b'-Infinity',
                 b'1e999', b'\xff', b'{} trailing', b'[' * 40 + b'0' + b']' * 40,
                 b' ' * (PROTOCOL.MAX_DOCUMENT_BYTES + 1), b'')
        for raw in cases:
            with self.subTest(raw=raw[:40]):
                with self.assertRaises(PROTOCOL.Rejected):
                    PROTOCOL.validate_json(raw, json.dumps(identity()).encode())

    def test_wrong_primitive_or_container_types_are_rejections_without_tracebacks(self):
        def paths(value, prefix=()):
            if type(value) is dict:
                for key, child in value.items():
                    yield prefix + (key,)
                    yield from paths(child, prefix + (key,))
            elif type(value) is list:
                for index, child in enumerate(value[:1]):
                    yield prefix + (index,)
                    yield from paths(child, prefix + (index,))
        for path in paths(fixture()):
            for replacement in (None, True, -1, 1.5, "untrusted", [], {}):
                data = fixture()
                target = data
                for key in path[:-1]:
                    target = target[key]
                target[path[-1]] = replacement
                try:
                    self.validate(value=data)
                except PROTOCOL.Rejected:
                    pass

    def test_cli_reads_only_supplied_json_and_never_echoes_rejected_content(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence, expected = root / "evidence.json", root / "expected.json"
            evidence.write_text(json.dumps(self.data))
            expected.write_text(json.dumps(identity()))
            command = [sys.executable, str(SCRIPT), "--evidence", str(evidence), "--expected-identity", str(expected)]
            result = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("UNVERIFIED_INPUT", json.loads(result.stdout)["evidenceTrust"])
            evidence.write_text('{"prompt":"SECRET_MARKER_DO_NOT_ECHO"}')
            result = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(2, result.returncode)
            self.assertNotIn("SECRET_MARKER", result.stdout + result.stderr)
            self.assertFalse(json.loads(result.stdout)["schemaValid"])
            link = root / "symlink.json"
            link.symlink_to(expected)
            result = subprocess.run(command[:-1] + [str(link)], capture_output=True, text=True, check=False)
            self.assertEqual(2, result.returncode)


if __name__ == "__main__":
    unittest.main()
