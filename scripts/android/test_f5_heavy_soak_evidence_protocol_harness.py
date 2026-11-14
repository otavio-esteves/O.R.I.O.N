#!/usr/bin/env python3
"""Synthetic offline contracts only; no device, executor, hardware or duration proof."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid

import f5_heavy_soak_evidence_protocol as PROTOCOL

SCRIPT = Path(PROTOCOL.__file__)
PLAN_ID = str(uuid.UUID(int=1))
FIXTURE_ID = str(uuid.UUID(int=2))
HOST_SESSION = str(uuid.UUID(int=3))
OTHER_SESSION = str(uuid.UUID(int=4))


def plan():
    return {"schemaVersion": 1, "kind": "POST_CORTEX_HEAVY_SOAK_PLAN", "planId": PLAN_ID,
            "identity": {"sourceCommit": "1" * 40, **{key: "2" * 64 for key in PROTOCOL.IDENTITY_KEYS - {"sourceCommit"}}},
            "requestedEnvironment": "PHYSICAL_DEVICE", "deviceIdentitySha256": "3" * 64,
            "runnerSha256": "4" * 64, "trustedConfigurationSha256": "5" * 64,
            "limits": {"targetDurationMillis": PROTOCOL.DAY, "maximumAttempts": 3, "minimumAttemptIntervalMillis": 1000,
                       "resourceExitCooldownMillis": 5000, "maximumResourceDeaths": 2,
                       "maximumObservationGapMillis": 3_600_000}, "ownedFixtureIds": [FIXTURE_ID]}


def observation(available=True, queues=True):
    return {"mainPid": 101 if available else None, "aiPid": 202 if available else None,
            "mainPssBytes": 100 if available else None, "aiPssBytes": 200 if available else None,
            "aggregatePssBytes": 300 if available else None, "thermalStatus": 0 if available else None,
            "memoryPressure": "NONE" if available else "UNAVAILABLE", "serviceState": "UNLOADED" if available else "UNAVAILABLE",
            "nativeRuntimeLoaded": True if available else None,
            "actionPendingCount": 1 if queues else None, "actionUnknownCount": 1 if queues else None}


class Journal:
    def __init__(self, selected=None):
        self.plan = selected or plan()
        self.entries = []
        self.host = HOST_SESSION
        self.boot = 7
        self.device_offset = 0
        self.add("RUN_BEGIN", 0, {})

    def add(self, kind, elapsed, data):
        self.entries.append({"sequence": len(self.entries), "hostSessionId": self.host, "hostElapsedMillis": elapsed,
                             "bootCount": self.boot, "deviceElapsedMillis": elapsed + self.device_offset,
                             "kind": kind, "data": data})
        return self

    def owned(self):
        self.add("FIXTURE_INTENT", 0, {"fixtureId": FIXTURE_ID})
        self.add("FIXTURE_READY", 0, {"fixtureId": FIXTURE_ID})
        return self

    def attempt(self, start=1000, number=10, outcome="SUCCEEDED", reason="NONE", resource=None):
        attempt = str(uuid.UUID(int=number))
        self.add("WORK_INTENT", start, {"attemptId": attempt, "fixtureId": FIXTURE_ID, "admissionDecisionSha256": "6" * 64})
        if resource is None:
            self.add("WORK_RESULT", start + 1, {"attemptId": attempt, "outcome": outcome, "reason": reason})
        else:
            self.add("RESOURCE_EXIT", start + 1, {"attemptId": attempt, "classification": resource})
        return self

    def cleanup(self, at, outcome="REMOVED"):
        return self.add("CLEANUP_INTENT", at, {"fixtureId": FIXTURE_ID}).add("CLEANUP_RESULT", at, {"fixtureId": FIXTURE_ID, "outcome": outcome})

    def complete(self):
        self.owned().attempt()
        for hour in range(1, 25):
            self.add("OBSERVATION", hour * 3_600_000, observation())
        return self.cleanup(PROTOCOL.DAY).add("RUN_END", PROTOCOL.DAY, {"outcome": "FINISHED"})

    def value(self):
        return {"schemaVersion": 1, "kind": "POST_CORTEX_HEAVY_SOAK_JOURNAL", "planId": PLAN_ID,
                "planSha256": PROTOCOL.plan_digest(self.plan),
                "identity": copy.deepcopy(self.plan["identity"]), "entries": self.entries}

    def report(self):
        return PROTOCOL.validate(self.plan, self.value())


class HeavySoakEvidenceProtocolTest(unittest.TestCase):
    def assert_unqualified(self, report):
        self.assertEqual("UNVERIFIED_INPUT", report["result"])
        self.assertEqual("NOT_QUALIFIED", report["qualification"])
        for key in ("physicalEvidenceVerified", "real24HourDurationProven", "autonomyProven", "workloadQualified",
                    "profileQualified", "productionInferenceEnabled", "fullF5GateClosed", "fullSoakGateClosed", "commandsDispatched"):
            self.assertIs(report[key], False)

    def rejected(self, selected, journal=None, code=None):
        report = PROTOCOL.validate(selected, journal)
        self.assertEqual("REJECTED", report["structuralStatus"])
        self.assert_unqualified(report)
        if code:
            self.assertEqual(code, report["rejectionCode"])

    def test_consistent_reported_full_day_does_not_prove_time_execution_or_qualification(self):
        report = Journal().complete().report()
        self.assertEqual("VALID", report["structuralStatus"])
        self.assertEqual("CONSISTENT_REPORTED_RECORDS", report["evidenceState"])
        self.assertEqual(PROTOCOL.DAY, report["reported"]["reportedHostSpanMillis"])
        self.assertEqual("REFERENCED_UNVERIFIED", report["trustedConfigurationAvailability"])
        self.assert_unqualified(report)

    def test_plan_without_runner_configuration_target_or_journal_is_explicitly_unavailable(self):
        selected = plan()
        selected.update(runnerSha256=None, trustedConfigurationSha256=None, deviceIdentitySha256=None)
        report = PROTOCOL.validate(selected)
        self.assertEqual("VALID", report["structuralStatus"])
        self.assertEqual("UNAVAILABLE", report["evidenceState"])
        self.assertEqual({"RUNNER_UNAVAILABLE", "TRUSTED_CONFIGURATION_UNAVAILABLE", "PHYSICAL_TARGET_UNAVAILABLE", "JOURNAL_UNAVAILABLE"}, set(report["issues"]))
        self.assert_unqualified(report)

    def test_plan_rejects_unknown_sensitive_fields_and_qualification_claims(self):
        for key in ("prompt", "output", "rawError", "deviceSerial", "qualified", "command", "elapsedProof"):
            selected = plan()
            selected[key] = "PRIVATE_SECRET_SENTINEL"
            with self.subTest(key=key):
                self.rejected(selected, code="FIELDS_INVALID")

    def test_exact_plan_types_bounds_hashes_and_ownership(self):
        mutations = [("schemaVersion", True), ("requestedEnvironment", "ANDROID_EMULATOR"),
                     ("runnerSha256", "synthetic"), ("planId", "0" * 36), ("ownedFixtureIds", []),
                     ("ownedFixtureIds", [FIXTURE_ID, FIXTURE_ID])]
        for key, value in mutations:
            selected = plan(); selected[key] = value
            with self.subTest(key=key, value=value): self.rejected(selected)
        for key, value in [("maximumAttempts", True), ("maximumAttempts", 257), ("targetDurationMillis", PROTOCOL.DAY - 1),
                           ("targetDurationMillis", 8 * PROTOCOL.DAY), ("resourceExitCooldownMillis", 0),
                           ("maximumResourceDeaths", 17), ("maximumObservationGapMillis", 3_600_001)]:
            selected = plan(); selected["limits"][key] = value
            with self.subTest(key=key, value=value): self.rejected(selected)

    def test_source_app_test_model_or_plan_identity_mismatch_is_rejected(self):
        for key in PROTOCOL.IDENTITY_KEYS:
            journal = Journal().value(); journal["identity"][key] = "9" * (40 if key == "sourceCommit" else 64)
            with self.subTest(key=key): self.rejected(plan(), journal, "IDENTITY_MISMATCH")
        journal = Journal().value(); journal["planId"] = str(uuid.UUID(int=99))
        self.rejected(plan(), journal, "IDENTITY_MISMATCH")

    def test_journal_cannot_be_reinterpreted_using_changed_limits_ownership_or_runner(self):
        for change in (lambda selected: selected["limits"].update(maximumAttempts=4),
                       lambda selected: selected.update(runnerSha256="7" * 64),
                       lambda selected: selected["ownedFixtureIds"].append(str(uuid.UUID(int=77)))):
            selected = plan()
            journal = Journal(selected).complete().value()
            change(selected)
            with self.subTest(change=change): self.rejected(selected, journal, "PLAN_CONTENT_MISMATCH")

    def test_missing_end_short_span_missing_work_or_observations_stays_incomplete(self):
        report = Journal().report()
        self.assertEqual("INCOMPLETE", report["evidenceState"])
        self.assertTrue({"END_UNAVAILABLE", "OBSERVATIONS_UNAVAILABLE", "WORK_UNAVAILABLE", "HEAVY_WORK_UNAVAILABLE",
                         "REPORTED_DURATION_INSUFFICIENT_OR_DISCONTINUOUS"} <= set(report["issues"]))
        self.assert_unqualified(report)

    def test_unloaded_wrapper_can_remain_native_resident(self):
        self.assertTrue(observation()["nativeRuntimeLoaded"])
        self.assertEqual((True, True), PROTOCOL.observation(observation()))

    def test_loaded_or_inferencing_without_native_runtime_is_rejected(self):
        for state in ("LOADED", "INFERENCING"):
            journal = Journal().complete()
            journal.entries[5]["data"].update(serviceState=state, nativeRuntimeLoaded=False)
            with self.subTest(state=state):
                self.rejected(journal.plan, journal.value(), "SERVICE_RUNTIME_STATE_INVALID")

    def test_service_transitions_that_can_precede_native_creation_remain_valid(self):
        for state in ("UNLOADED", "LOADING", "UNLOADING"):
            row = observation()
            row.update(serviceState=state, nativeRuntimeLoaded=False)
            with self.subTest(state=state):
                self.assertEqual((True, True), PROTOCOL.observation(row))

    def test_service_states_match_current_get_status_surface(self):
        for state in ("CANCELLING", "FAILED"):
            row = observation()
            row["serviceState"] = state
            with self.subTest(state=state), self.assertRaisesRegex(PROTOCOL.Invalid, "ENUM_INVALID"):
                PROTOCOL.observation(row)

    def test_unavailable_resources_queues_and_unknown_counts_are_explicit(self):
        journal = Journal().complete()
        journal.entries[5]["data"] = observation(False, False)
        report = journal.report()
        self.assertEqual("INCOMPLETE", report["evidenceState"])
        self.assertTrue({"RESOURCE_OBSERVATION_UNAVAILABLE", "QUEUE_OBSERVATION_UNAVAILABLE"} <= set(report["issues"]))
        self.assert_unqualified(report)

    def test_invalid_resource_types_sums_pids_and_queue_counts_are_rejected(self):
        for key, value in [("nativeRuntimeLoaded", "true"), ("aggregatePssBytes", 301), ("mainPid", 202),
                           ("thermalStatus", 7), ("mainPssBytes", 0), ("actionUnknownCount", True),
                           ("actionPendingCount", None), ("serviceState", "SYNTHETIC"), ("aiPssBytes", None)]:
            journal = Journal().complete(); journal.entries[5]["data"][key] = value
            with self.subTest(key=key, value=value): self.rejected(journal.plan, journal.value())

    def test_order_duplicate_terminal_and_entries_after_end_are_rejected(self):
        journal = Journal().complete(); journal.entries[1]["sequence"] = 0
        self.rejected(journal.plan, journal.value(), "NUMBER_INVALID")
        journal = Journal().owned().attempt(); journal.add("WORK_RESULT", 1002, copy.deepcopy(journal.entries[-1]["data"]))
        self.rejected(journal.plan, journal.value(), "WORK_INTENT_MISSING")
        journal = Journal().complete(); journal.add("RUN_END", PROTOCOL.DAY, {"outcome": "FINISHED"})
        self.rejected(journal.plan, journal.value(), "ENTRY_AFTER_END")

    def test_host_device_and_boot_regressions_are_rejected(self):
        for key, value, code in [("hostElapsedMillis", 999, "HOST_CLOCK_REGRESSION"),
                                 ("deviceElapsedMillis", 999, "DEVICE_CLOCK_REGRESSION"), ("bootCount", 6, "BOOT_REGRESSION")]:
            journal = Journal().owned().attempt(); journal.entries[-1][key] = value
            with self.subTest(key=key): self.rejected(journal.plan, journal.value(), code)

    def test_host_session_change_never_sums_durations_or_resumes_old_session(self):
        journal = Journal().complete()
        for event in journal.entries[6:]: event.update(hostSessionId=OTHER_SESSION, hostElapsedMillis=event["hostElapsedMillis"] - 3_600_000)
        report = journal.report()
        self.assertEqual("VALID", report["structuralStatus"])
        self.assertIsNone(report["reported"]["reportedHostSpanMillis"])
        self.assertIn("HOST_SESSION_DISCONTINUITY", report["issues"])
        journal.entries[-1]["hostSessionId"] = HOST_SESSION
        self.rejected(journal.plan, journal.value(), "HOST_SESSION_REUSED")

    def test_device_reboot_does_not_compare_old_device_elapsed_with_new_boot(self):
        journal = Journal().complete()
        for event in journal.entries[6:]: event.update(bootCount=8, deviceElapsedMillis=event["deviceElapsedMillis"] - 3_600_000)
        report = journal.report()
        self.assertEqual("VALID", report["structuralStatus"])
        self.assert_unqualified(report)

    def test_gaps_process_changes_and_interruptions_are_retained_after_later_progress(self):
        journal = Journal().owned().attempt()
        journal.add("OBSERVATION", 2000, observation()).add("INTERRUPTION", 2001, {"reason": "DEVICE_DISCONNECTED"})
        journal.add("OBSERVATION_GAP", 2002, {"reason": "TRANSPORT"})
        changed = observation(); changed["aiPid"] = 203
        journal.add("OBSERVATION", 2003, changed).cleanup(2004).add("RUN_END", 2005, {"outcome": "FINISHED"})
        report = journal.report()
        self.assertTrue({"INTERRUPTED", "OBSERVATION_GAP", "PROCESS_IDENTITY_CHANGED"} <= set(report["issues"]))
        self.assertEqual("INCOMPLETE", report["evidenceState"])

    def test_observation_cadence_gaps_cannot_be_hidden_by_supplied_full_day(self):
        journal = Journal().complete()
        del journal.entries[6]
        for index, event in enumerate(journal.entries): event["sequence"] = index
        self.assertIn("OBSERVATION_GAP", journal.report()["issues"])

    def test_owned_cleanup_requires_preceding_intent_and_rejects_foreign_ids(self):
        journal = Journal().owned().attempt().add("CLEANUP_RESULT", 2000, {"fixtureId": FIXTURE_ID, "outcome": "REMOVED"})
        self.rejected(journal.plan, journal.value(), "CLEANUP_INTENT_MISSING")
        journal = Journal().owned(); journal.entries[1]["data"]["fixtureId"] = str(uuid.UUID(int=99))
        self.rejected(journal.plan, journal.value(), "FOREIGN_FIXTURE")

    def test_failed_cleanup_repeat_and_unfinished_intent_remain_incomplete(self):
        journal = Journal().owned().attempt().cleanup(2000, "FAILED").cleanup(2001).add("RUN_END", 2002, {"outcome": "FINISHED"})
        report = journal.report()
        self.assertIn("CLEANUP_FAILURE_REPORTED", report["issues"])
        self.assertNotIn("OWNED_CLEANUP_INCOMPLETE", report["issues"])
        journal = Journal().owned().attempt()
        self.assertIn("OWNED_CLEANUP_INCOMPLETE", journal.report()["issues"])

    def test_work_without_references_or_with_duplicate_identity_is_rejected(self):
        journal = Journal().owned().attempt(); journal.plan["runnerSha256"] = None
        self.rejected(journal.plan, journal.value(), "WORK_REFERENCES_UNAVAILABLE")
        journal = Journal().owned().attempt().attempt(3000)
        self.rejected(journal.plan, journal.value(), "WORK_OWNERSHIP_OR_ORDER_INVALID")

    def test_refusals_without_runner_never_count_as_reported_heavy_execution(self):
        journal = Journal().owned().attempt(outcome="REFUSED", reason="RUNNER_UNAVAILABLE")
        journal.plan["runnerSha256"] = None
        report = journal.report()
        self.assertEqual("UNAVAILABLE", report["evidenceState"])
        self.assertIn("HEAVY_WORK_UNAVAILABLE", report["issues"])
        self.assert_unqualified(report)

    def test_resource_death_requires_cooldown_then_bounded_refusal(self):
        journal = Journal().owned().attempt(resource="MEMORY_LIMITER")
        journal.attempt(2000, 11, outcome="REFUSED", reason="RESOURCE_COOLDOWN")
        journal.attempt(7000, 12, resource="LOW_MEMORY")
        report = journal.report()
        self.assertEqual("VALID", report["structuralStatus"])
        journal.plan["limits"]["maximumAttempts"] = 4
        journal.attempt(13000, 13, outcome="REFUSED", reason="RESOURCE_LIMIT")
        self.assertEqual("VALID", journal.report()["structuralStatus"])
        journal.entries[-1]["data"].update(outcome="SUCCEEDED", reason="NONE")
        self.rejected(journal.plan, journal.value(), "RESOURCE_REFUSAL_REQUIRED")

    def test_resource_cooldown_and_admitted_work_cadence_are_strict(self):
        journal = Journal().owned().attempt(resource="MEMORY_LIMITER").attempt(6000, 11)
        self.rejected(journal.plan, journal.value(), "RESOURCE_COOLDOWN_REQUIRED")
        journal = Journal().owned().attempt().attempt(1002, 11)
        self.rejected(journal.plan, journal.value(), "WORK_CADENCE_INVALID")

    def test_exact_cooldown_boundary_is_allowed_but_attempt_bound_cannot_expand(self):
        journal = Journal().owned().attempt(resource="MEMORY_LIMITER").attempt(6001, 11)
        self.assertEqual("VALID", journal.report()["structuralStatus"])
        journal.plan["limits"]["maximumAttempts"] = 2
        journal.attempt(8000, 12, outcome="REFUSED", reason="ADMISSION_REFUSED")
        self.rejected(journal.plan, journal.value(), "ATTEMPT_BOUND_EXCEEDED")

    def test_reboot_does_not_clear_resource_history_or_reuse_old_fence(self):
        journal = Journal().owned().attempt(resource="MEMORY_LIMITER")
        journal.boot = 8; journal.device_offset = -9000
        journal.attempt(10000, 11, outcome="REFUSED", reason="RESOURCE_UNAVAILABLE")
        self.assertEqual("VALID", journal.report()["structuralStatus"])
        journal.entries[-1]["data"].update(outcome="SUCCEEDED", reason="NONE")
        self.rejected(journal.plan, journal.value(), "POST_REBOOT_HISTORY_REQUIRED")

    def test_unfinished_work_cannot_receive_terminal_replay_after_interruption(self):
        journal = Journal().owned().attempt(); result = journal.entries.pop(); journal.entries[-1]["sequence"] = len(journal.entries) - 1
        journal.add("INTERRUPTION", 1001, {"reason": "PROCESS_DIED"})
        self.assertIn("UNRESOLVED_WORK_AT_INTERRUPTION", journal.report()["issues"])
        journal.add("WORK_RESULT", 1002, result["data"])
        self.rejected(journal.plan, journal.value(), "WORK_INTENT_MISSING")

    def test_file_size_symlink_duplicate_fields_nonfinite_and_malformed_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); source = root / 'input.json'
            for data in ['{"x":1,"x":2}', '{"x":NaN}', '', 'x' * (PROTOCOL.MAX_BYTES + 1)]:
                source.write_text(data)
                with self.subTest(data=data[:20]), self.assertRaises((PROTOCOL.Invalid, ValueError)):
                    PROTOCOL.document(source)
            link = root / 'link.json'; link.symlink_to(source)
            with self.assertRaises(PROTOCOL.Invalid): PROTOCOL.document(link)

    def test_entry_count_bounds_unknown_data_and_zero_proof_booleans(self):
        journal = Journal().value(); journal["entries"] *= PROTOCOL.MAX_ENTRIES + 1
        self.rejected(plan(), journal, "ENTRY_BOUND_INVALID")
        journal = Journal().value(); journal["entries"][0]["data"]["real24h"] = True
        self.rejected(plan(), journal, "FIELDS_INVALID")

    def test_cli_reads_only_files_returns_unverified_and_does_not_echo_input_or_create_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); selected = root / 'plan.json'; journal = root / 'journal.json'
            selected.write_text(json.dumps(plan())); journal.write_text(json.dumps(Journal().complete().value()))
            before = {path.name: path.read_bytes() for path in root.iterdir()}
            result = subprocess.run([sys.executable, '-B', str(SCRIPT), '--plan', str(selected), '--journal', str(journal)],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10, check=False)
            self.assertEqual(0, result.returncode)
            self.assert_unqualified(json.loads(result.stdout))
            self.assertEqual(before, {path.name: path.read_bytes() for path in root.iterdir()})
            value = plan(); value['rawError'] = 'PRIVATE_SECRET_SENTINEL'; selected.write_text(json.dumps(value))
            result = subprocess.run([sys.executable, '-B', str(SCRIPT), '--plan', str(selected)], stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=10, check=False)
            self.assertEqual(2, result.returncode)
            self.assertNotIn(b'PRIVATE_SECRET_SENTINEL', result.stdout + result.stderr)
            self.assert_unqualified(json.loads(result.stdout))


if __name__ == '__main__':
    unittest.main()
