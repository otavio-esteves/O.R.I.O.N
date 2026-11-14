#!/usr/bin/env python3
"""Synthetic HOST protocol fixtures only; no Android runtime or limiter evidence."""
import contextlib
import copy
import io
import json
import os
from pathlib import Path
import tempfile
import unittest

import f5_limiter_evidence_protocol as protocol


def fixture():
    receipt = {
        "schemaVersion": 1, "kind": "F5_LIMITER_CORRELATION_INPUT",
        "identity": {"runId": "1" * 32, "sourceCommit": "a" * 40,
                     "appPackage": protocol.PACKAGE, "testPackage": protocol.PACKAGE + ".test",
                     "appApkSha256": "b" * 64, "testApkSha256": "c" * 64,
                     "modelSha256": "d" * 64, "producerSourceSha256": "e" * 64},
        "target": {"apiLevel": 37, "sdkExtension": 0, "buildFingerprintSha256": "f" * 64,
                   "abi": "arm64-v8a", "declaredEnvironment": "PHYSICAL_DEVICE", "dedicatedReason": None},
        "process": {"name": protocol.PACKAGE + ":ai", "oldPid": 42, "mainPid": 41, "bootIdSha256": "2" * 64,
                    "startedElapsedMillis": 100, "lastAliveElapsedMillis": 200, "lastAliveWallMillis": 1000},
        "exit": {"name": protocol.PACKAGE + ":ai", "pid": 42, "windowBootIdSha256": "2" * 64,
                 "correlation": "PAIRED_LIVE_WINDOW", "timestampWallMillis": 1100, "observedWallMillis": 1200,
                 "observedElapsedMillis": 400, "reason": 13, "descriptionEvidence": "OTHER_ANON_SWAP_MARKER",
                 "classification": "MEMORY_LIMITER"},
        "before": {"revision": 1, "cortexState": "HEALTHY", "preloadAllowed": True, "keepAliveMillis": 10},
        "policies": [{"exitEvidenceSha256": "0" * 64, "mainPid": 41, "bootIdSha256": "2" * 64,
                      "observedElapsedMillis": 450, "revision": 2, "cortexState": "DEGRADED",
                      "preloadAllowed": False, "keepAliveMillis": 0, "restartBlock": "COOLDOWN",
                      "governorBlock": "MEMORY_EXIT", "admission": "REFUSED", "heavyWorkIssued": False,
                      "fenceStartedElapsedMillis": 400, "cooldownMillis": 100}]
    }
    return relink(receipt)


def relink(receipt):
    for policy in receipt["policies"]:
        policy["exitEvidenceSha256"] = protocol.exit_evidence_sha256(receipt)
    return receipt


def validate(receipt):
    return protocol.validate_receipt(protocol.parse_receipt(json.dumps(receipt).encode()))


class LimiterEvidenceProtocolTest(unittest.TestCase):
    def assert_status(self, receipt, status):
        result = validate(receipt)
        self.assertEqual(status, result["status"])
        self.assertEqual("UNVERIFIED_INPUT", result["environment"])
        self.assertEqual("NOT_QUALIFIED", result["qualification"])
        for key in ("provenanceAuthenticated", "memoryLimiterVerified", "physicalDeviceVerified", "profileQualified",
                    "productionInferenceEnabled", "fullF5GateClosed", "fullSoakGateClosed"):
            self.assertIs(False, result[key])

    def test_preview_marker_correlates_only_as_unverified_input(self):
        self.assert_status(fixture(), "CONSISTENT_REFUSAL_INPUT")

    def test_dedicated_reason_needs_exact_numeric_match(self):
        value = fixture()
        value["target"]["dedicatedReason"] = 25
        value["exit"].update(reason=25, descriptionEvidence="UNAVAILABLE")
        self.assert_status(relink(value), "CONSISTENT_REFUSAL_INPUT")
        value["exit"].update(reason=26, classification="UNKNOWN")
        self.assert_status(relink(value), "INSUFFICIENT_LIMITER_EVIDENCE")

    def test_ordinary_kills_global_low_memory_crashes_and_resource_exit_are_not_limiter(self):
        for reason in (0, 2, 3, 4, 5, 9, 13):
            with self.subTest(reason=reason):
                value = fixture()
                value["exit"].update(reason=reason, descriptionEvidence="NO_MATCH", classification=protocol.KINDS[reason])
                self.assert_status(relink(value), "INSUFFICIENT_LIMITER_EVIDENCE")

    def test_boolean_assertion_cannot_replace_encoding(self):
        value = fixture()
        value["exit"]["memoryLimiterObserved"] = True
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_classification_cannot_promote_low_memory(self):
        value = fixture()
        value["exit"].update(reason=3, descriptionEvidence="NO_MATCH")
        with self.assertRaisesRegex(protocol.Rejected, "CLASSIFICATION_MISMATCH"):
            validate(value)

    def test_marker_only_applies_to_other_and_dedicated_cannot_shadow_public_reason(self):
        for reason, dedicated in ((3, None), (13, 3), (13, True)):
            value = fixture()
            value["exit"]["reason"] = reason
            value["target"]["dedicatedReason"] = dedicated
            with self.subTest(reason=reason, dedicated=dedicated), self.assertRaises(protocol.Rejected):
                validate(value)

    def test_identity_and_exit_reference_bind_all_inputs(self):
        for section, key, changed in (("identity", "appApkSha256", "9" * 64), ("identity", "modelSha256", "8" * 64),
                                      ("identity", "sourceCommit", "9" * 40), ("target", "apiLevel", 38),
                                      ("process", "startedElapsedMillis", 99), ("exit", "observedWallMillis", 1201)):
            value = fixture()
            value[section][key] = changed
            with self.subTest(key=key), self.assertRaisesRegex(protocol.Rejected, "EXIT_REFERENCE_MISMATCH"):
                validate(value)

    def test_pid_name_package_and_digest_formats_are_strict(self):
        for section, key, changed in (("exit", "pid", 43), ("exit", "name", "network.orion:ai"),
                                      ("identity", "appPackage", "network.orion"), ("identity", "testPackage", "other.test"),
                                      ("identity", "modelSha256", "A" * 64), ("identity", "sourceCommit", "main"),
                                      ("identity", "runId", "../../private")):
            value = fixture()
            value[section][key] = changed
            with self.subTest(key=key), self.assertRaises(protocol.Rejected):
                validate(value)

    def test_current_boot_is_not_an_exit_boot_identifier(self):
        value = fixture()
        value["exit"]["correlation"] = "UNAVAILABLE"
        self.assert_status(relink(value), "INSUFFICIENT_EXIT_TIME_CORRELATION")

    def test_changed_boot_is_unsupported_not_compared_as_same_monotonic_epoch(self):
        for section, key in (("exit", "windowBootIdSha256"), ("policy", "bootIdSha256")):
            value = fixture()
            obj = value["policies"][0] if section == "policy" else value[section]
            obj[key] = "3" * 64
            obj["observedElapsedMillis"] = 1
            self.assert_status(relink(value), "UNSUPPORTED_BOOT_CHANGE")

    def test_absent_public_exit_api_is_unsupported(self):
        value = fixture()
        value["target"]["apiLevel"] = 29
        self.assert_status(relink(value), "UNSUPPORTED_APPLICATION_EXIT_INFO_API")

    def test_time_window_rejects_old_history_wall_rollback_and_pre_live_query(self):
        for key, changed in (("timestampWallMillis", 999), ("timestampWallMillis", 1201),
                             ("observedWallMillis", 900), ("observedElapsedMillis", 199)):
            value = fixture()
            value["exit"][key] = changed
            self.assert_status(relink(value), "INSUFFICIENT_EXIT_TIME_CORRELATION")

    def test_declared_environment_never_authenticates_environment(self):
        for environment in ("PHYSICAL_DEVICE", "ANDROID_EMULATOR", "UNKNOWN"):
            value = fixture()
            value["target"]["declaredEnvironment"] = environment
            self.assert_status(relink(value), "CONSISTENT_REFUSAL_INPUT")

    def test_missing_policy_change_is_insufficient(self):
        for changed in ({"preloadAllowed": True}, {"keepAliveMillis": 1}, {"cortexState": "HEALTHY"},
                        {"admission": "NOT_REQUESTED"}, {"restartBlock": "HISTORY_UNAVAILABLE"}, {"governorBlock": "NONE"}):
            value = fixture()
            value["policies"][0].update(changed)
            self.assert_status(value, "INSUFFICIENT_POLICY_CHANGE")
        value = fixture()
        value["before"].update(cortexState="DEGRADED", preloadAllowed=False, keepAliveMillis=0)
        self.assert_status(value, "INSUFFICIENT_POLICY_CHANGE")

    def test_refusal_by_restart_guard_does_not_require_running_governor(self):
        value = fixture()
        value["policies"][0]["governorBlock"] = "NOT_EVALUATED"
        self.assert_status(value, "CONSISTENT_REFUSAL_INPUT")

    def test_heavy_resumption_cannot_be_approved(self):
        for changed in ({"admission": "ALLOWED"}, {"heavyWorkIssued": True}):
            value = fixture()
            value["policies"][0].update(changed)
            self.assert_status(value, "UNSUPPORTED_HEAVY_RESUMPTION")

    def test_cooldown_boundary_is_exclusive_and_no_overflow_addition(self):
        for elapsed in (499, protocol.LONG_MAX):
            value = fixture()
            policy = value["policies"][0]
            policy.update(observedElapsedMillis=elapsed, cooldownMillis=protocol.LONG_MAX)
            self.assert_status(value, "CONSISTENT_REFUSAL_INPUT")
        value = fixture()
        value["policies"][0]["observedElapsedMillis"] = 500
        with self.assertRaisesRegex(protocol.Rejected, "COOLDOWN_EXPIRED"):
            validate(value)

    def test_fallback_exhaustion_is_only_a_declared_refusal_not_death_count_proof(self):
        value = fixture()
        value["policies"][0].update(restartBlock="FALLBACK_EXHAUSTED", observedElapsedMillis=1000)
        self.assert_status(value, "CONSISTENT_REFUSAL_INPUT")
        value["deathCount"] = 2
        with self.assertRaises(protocol.Rejected):
            validate(value)

    def test_duplicate_or_reordered_policy_is_not_an_independent_exit(self):
        value = fixture()
        value["policies"].append(copy.deepcopy(value["policies"][0]))
        with self.assertRaisesRegex(protocol.Rejected, "POLICY_REVISION_INVALID"):
            validate(value)
        value["policies"][1].update(revision=3, observedElapsedMillis=449)
        with self.assertRaisesRegex(protocol.Rejected, "POLICY_TIME_INVALID"):
            validate(value)
        value["policies"][1]["observedElapsedMillis"] = 451
        self.assert_status(value, "CONSISTENT_REFUSAL_INPUT")

    def test_duplicate_exit_cannot_renew_fence_or_change_cooldown(self):
        for changed in ({"fenceStartedElapsedMillis": 401}, {"cooldownMillis": 101}):
            value = fixture()
            value["policies"].append(copy.deepcopy(value["policies"][0]))
            value["policies"][1].update(revision=3, **changed)
            with self.assertRaisesRegex(protocol.Rejected, "DUPLICATE_EXIT_RENEWED_FENCE"):
                validate(value)

    def test_policy_main_owner_must_match_surviving_main_and_differ_from_ai(self):
        value = fixture()
        value["policies"][0]["mainPid"] = 43
        with self.assertRaisesRegex(protocol.Rejected, "MAIN_PID_MISMATCH"):
            validate(value)
        value = fixture()
        value["process"]["mainPid"] = 42
        with self.assertRaisesRegex(protocol.Rejected, "PROCESS_IDENTITY_INVALID"):
            validate(value)

    def test_policy_count_bounds(self):
        for count in (0, 17):
            value = fixture()
            value["policies"] *= count
            with self.assertRaisesRegex(protocol.Rejected, "POLICY_COUNT_INVALID"):
                validate(value)
        value = fixture()
        for revision in range(3, 18):
            value["policies"].append(dict(value["policies"][0], revision=revision))
        self.assert_status(value, "CONSISTENT_REFUSAL_INPUT")

    def test_unknown_fields_raw_descriptions_paths_and_budgets_are_rejected(self):
        for section, key in ((None, "qualified"), ("exit", "description"), ("exit", "trace"),
                             ("identity", "modelPath"), ("before", "processBudgetBytes")):
            value = fixture()
            (value if section is None else value[section])[key] = "secret"
            with self.subTest(key=key), self.assertRaisesRegex(protocol.Rejected, "FIELDS_INVALID"):
                validate(value)

    def test_wrong_types_and_integer_limits(self):
        for key, changed in (("revision", True), ("revision", -1), ("revision", 2**63),
                             ("preloadAllowed", 0), ("cooldownMillis", 0), ("observedElapsedMillis", None)):
            value = fixture()
            value["policies"][0][key] = changed
            with self.subTest(key=key, changed=changed), self.assertRaises(protocol.Rejected):
                validate(value)

    def test_duplicate_keys_at_root_and_nested_level(self):
        for data in (b'{"schemaVersion":1,"schemaVersion":1}', b'{"exit":{"reason":13,"reason":3}}'):
            with self.assertRaisesRegex(protocol.Rejected, "DUPLICATE_FIELD"):
                protocol.parse_receipt(data)

    def test_nonfinite_float_exponent_and_unbounded_integer_rejected(self):
        for value in (b'NaN', b'Infinity', b'-Infinity', b'1.5', b'1e0', b'9' * 3000, b'9223372036854775808'):
            with self.subTest(value=value[:20]), self.assertRaisesRegex(protocol.Rejected, "NUMBER_INVALID"):
                protocol.parse_receipt(b'{"x":' + value + b'}')

    def test_size_utf8_json_and_depth_are_bounded(self):
        for data in (b'', b' ' * (protocol.MAX_BYTES + 1), b'\xff', b'{', b'{}{}', b'[' * 20 + b']' * 20):
            with self.subTest(data=data[:20]), self.assertRaises(protocol.Rejected):
                protocol.parse_receipt(data)
        # Braces/escaped quotes inside strings do not consume nesting budget.
        self.assertEqual({"x": '["{'}, protocol.parse_receipt(b'{"x":"[\\\"{"}'))

    def test_cli_fixed_output_status_and_no_input_echo(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "receipt.json"
            insufficient = fixture()
            insufficient["exit"].update(reason=3, descriptionEvidence="NO_MATCH", classification="LOW_MEMORY")
            relink(insufficient)
            for value, code, status in ((fixture(), 0, "CONSISTENT_REFUSAL_INPUT"),
                                        (insufficient, 1, "INSUFFICIENT_LIMITER_EVIDENCE"),
                                        ({"rawDescription": "private secret"}, 2, "REJECTED_INPUT")):
                path.write_text(json.dumps(value))
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    self.assertEqual(code, protocol.main([str(path)]))
                result = json.loads(output.getvalue())
                self.assertEqual(status, result["status"])
                self.assertNotIn("private secret", output.getvalue())
                self.assertEqual("UNVERIFIED_INPUT", result["environment"])

    def test_cli_missing_symlink_directory_and_fifo_fail_without_read_or_hang(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "regular").write_text(json.dumps(fixture()))
            (root / "link").symlink_to(root / "regular")
            os.mkfifo(root / "fifo")
            for path in (root / "missing", root / "link", root, root / "fifo"):
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    self.assertEqual(2, protocol.main([str(path)]))
                self.assertNotIn(temporary, output.getvalue())


if __name__ == "__main__":
    unittest.main()
