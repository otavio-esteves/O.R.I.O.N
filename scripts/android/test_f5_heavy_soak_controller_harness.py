#!/usr/bin/env python3
"""Synthetic typed ports and real HOST JournalStore; no Android or qualification."""
from dataclasses import FrozenInstanceError, replace
import json
import multiprocessing
import os
from pathlib import Path
import stat
import tempfile
import threading
import unittest
import uuid
from unittest import mock

import f5_heavy_soak_controller as C
import f5_heavy_soak_journal as STORE
from qualification_host_clock import HostClock
from test_f5_heavy_soak_evidence_protocol_harness import plan, FIXTURE_ID, observation

BOOT = str(uuid.UUID(int=900))


class Time:
    def __init__(self):
        self.ns = self.mono = 1_000_000_000
        self.boot = BOOT

    def advance(self, millis, *, suspend=False):
        self.ns += millis * 1_000_000
        if not suspend:
            self.mono += millis * 1_000_000


def mock_platform_clock(source, *, boot_known=True):
    """Factory-mock fixture only: metadata impersonation never attests a real clock.

    Production has no injectable clock parameter and rejects the same source's
    actual SYNTHETIC_INJECTED/UNVERIFIED_INPUT metadata. Reports stay NOT_QUALIFIED.
    """
    clock = HostClock(lambda: source.ns, (lambda: source.boot) if boot_known else None)
    clock.metadata.update(clockSource="CLOCK_BOOTTIME", evidence="HOST_CLOCK_ONLY")
    return clock


class Permit(C.LivePermit):
    pass


class Handle(C.OwnedHandle):
    pass


class Configuration(C.ConfigurationPort):
    def bind(self, specification):
        return C.Binding(specification, 7)

    def revalidate(self, binding, specification, stamp):
        return C.Check.VALID if binding.specification == specification else C.Check.INVALID


class Admission(C.AdmissionPort):
    def __init__(self):
        self.valid = True
        self.reused = None

    def acquire(self, binding, request, stamp):
        return self.reused or C.Grant(Permit(), Handle(), "6" * 64)

    def revalidate(self, grant, binding, request, stamp):
        return C.Check.VALID if self.valid else C.Check.INVALID


class Producer(C.ProducerPort):
    def __init__(self, clock, directory):
        self.clock, self.directory = clock, directory
        self.effects = []
        self.device_boot = 7
        self.device_offset = 0
        self.outcome = C.Outcome.SUCCEEDED
        self.classification = C.ResourceClass.MEMORY_LIMITER
        self.failure = None
        self.entered = self.release = None
        self.foreign = False
        self.after_intent = None

    def stamp(self, binding):
        return C.DeviceStamp(binding, self.device_boot, self.clock.ns // 1_000_000 + self.device_offset)

    def revalidate(self, grant, binding, request, stamp):
        if self.after_intent:
            self.after_intent()
        return C.Check.INVALID if self.foreign else C.Check.VALID

    def invoke(self, grant, binding, request):
        state = json.loads((self.directory / STORE.STATE).read_bytes())
        self.effects.append((request.kind, state["journal"]["entries"][-1]["kind"]))
        if self.entered:
            self.entered.set()
            self.release.wait(5)
        if self.failure:
            raise self.failure
        outcome = {C.Effect.PREPARE: C.Outcome.FIXTURE_READY,
                   C.Effect.CLEANUP: C.Outcome.REMOVED}.get(request.kind, self.outcome)
        return C.EffectResult(outcome, self.stamp(binding), request, grant,
                              classification=self.classification if outcome is C.Outcome.RESOURCE_EXIT else None)

    def observe(self, binding):
        return C.Observation(self.stamp(binding), C.ResourceObservation(**observation()))


class HeavyControllerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name) / "run"
        self.time = Time()
        self.factory_patch = mock.patch.object(C.HostClock, "linux", side_effect=lambda: mock_platform_clock(self.time))
        self.factory_patch.start()
        self.addCleanup(self.factory_patch.stop)
        self.patch = mock.patch.object(C.time, "monotonic_ns", side_effect=lambda: self.time.mono)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.configuration = Configuration()
        self.admission = Admission()
        self.producer = Producer(self.time, self.directory)
        self.selected = plan()
        self.controller = self.make()
        self.addCleanup(self.controller.close)

    def make(self, **changes):
        values = dict(configuration=self.configuration, admission=self.admission, producer=self.producer)
        values.update(changes)
        return C.HeavySoakController(self.directory, self.selected, **values)

    def start(self):
        self.assertEqual("STARTED", self.controller.begin()["status"])
        self.assertEqual("RECORDED", self.controller.prepare_fixture(FIXTURE_ID)["status"])

    def attempt(self, number=10):
        return self.controller.attempt(str(uuid.UUID(int=number)), FIXTURE_ID)

    def entries(self):
        return json.loads((self.directory / STORE.STATE).read_bytes())["journal"]["entries"]

    def test_real_store_durable_intent_precedes_each_single_typed_effect(self):
        self.start()
        self.assertEqual("RECORDED", self.attempt()["status"])
        self.assertEqual("RECORDED", self.controller.cleanup(FIXTURE_ID)["status"])
        self.assertEqual([(C.Effect.PREPARE, "FIXTURE_INTENT"), (C.Effect.WORK, "WORK_INTENT"),
                          (C.Effect.CLEANUP, "CLEANUP_INTENT")], self.producer.effects)
        kinds = [event["kind"] for event in self.entries()]
        self.assertEqual(["RUN_BEGIN", "FIXTURE_INTENT", "FIXTURE_READY", "WORK_INTENT", "WORK_RESULT",
                          "CLEANUP_INTENT", "CLEANUP_RESULT"], kinds)
        self.assertEqual("FINISHED", self.controller.finish()["status"])
        self.assertEqual("VALID", STORE.PROTOCOL.validate(self.selected,
                         json.loads((self.directory / STORE.STATE).read_bytes())["journal"])["structuralStatus"])

    def test_effect_cannot_run_before_append_has_returned_a_durable_ack(self):
        self.start()
        original = STORE.JournalStore.append
        inside = []
        def append(store, event, expected):
            before = len(self.producer.effects)
            ack = original(store, event, expected)
            if event["kind"] == "WORK_INTENT":
                inside.append(len(self.producer.effects) == before)
            return ack
        with mock.patch.object(STORE.JournalStore, "append", new=append):
            self.assertEqual("RECORDED", self.attempt()["status"])
        self.assertEqual([True], inside)

    def test_lost_ack_and_malformed_ack_never_dispatch_or_replay(self):
        self.start()
        original = STORE.JournalStore.append
        def lost(store, event, expected):
            original(store, event, expected)
            raise OSError("PRIVATE_SECRET_SENTINEL")
        with mock.patch.object(STORE.JournalStore, "append", new=lost):
            value = self.attempt()
        self.assertEqual("HALTED", value["status"])
        self.assertEqual("HALTED", self.attempt()["status"])
        self.assertEqual(1, len(self.producer.effects))
        self.assertEqual("WORK_INTENT", self.entries()[-1]["kind"])
        self.assertNotIn("PRIVATE_SECRET_SENTINEL", json.dumps(value))

    def test_each_invalid_ack_field_halts_before_effect(self):
        original = STORE.JournalStore.append
        for mutation in ({"status": "CONFLICT"}, {"previousSnapshotSha256": "0" * 64},
                         {"entries": True}, {"entries": 900}, {"snapshotSha256": "x"},
                         {"hostClockSource": "CLOCK_BOOTTIME"}, {"qualification": "PASS"},
                         {"extra": "PRIVATE_SECRET_SENTINEL"}):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / "run"
                producer = Producer(self.time, target)
                with C.HeavySoakController(target, plan(), configuration=self.configuration,
                        admission=self.admission, producer=producer) as controller:
                    controller.begin(); controller.prepare_fixture(FIXTURE_ID)
                    def malformed(store, event, expected):
                        ack = original(store, event, expected); ack.update(mutation); return ack
                    with mock.patch.object(STORE.JournalStore, "append", new=malformed):
                        value = controller.attempt(str(uuid.uuid4()), FIXTURE_ID)
                    self.assertEqual("HALTED", value["status"])
                    self.assertEqual(1, len(producer.effects))
                    self.assertNotIn("PRIVATE_SECRET_SENTINEL", json.dumps(value))

    def test_uncertain_intent_commit_and_result_write_failure_leave_pending(self):
        self.start()
        original = STORE.JournalStore.append
        def fail_result(store, event, expected):
            if event["kind"] == "WORK_RESULT":
                raise STORE.CommitUncertain("9" * 64)
            return original(store, event, expected)
        with mock.patch.object(STORE.JournalStore, "append", new=fail_result):
            value = self.attempt()
        self.assertEqual("UNKNOWN", value["status"])
        self.assertEqual("WORK_INTENT", self.entries()[-1]["kind"])
        self.attempt(11)
        self.assertEqual(2, len(self.producer.effects))

    def test_real_file_or_directory_fsync_failure_never_dispatches(self):
        for directory_failure in (False, True):
            with self.subTest(directory_failure=directory_failure), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / "run"; producer = Producer(self.time, target)
                with C.HeavySoakController(target, plan(), configuration=self.configuration,
                        admission=self.admission, producer=producer) as controller:
                    controller.begin(); controller.prepare_fixture(FIXTURE_ID)
                    original = STORE.os.fsync
                    def fail(descriptor):
                        mode = os.fstat(descriptor).st_mode
                        if (stat.S_ISDIR(mode) if directory_failure else stat.S_ISREG(mode)):
                            raise OSError("PRIVATE_SECRET_SENTINEL")
                        return original(descriptor)
                    with mock.patch.object(STORE.os, "fsync", side_effect=fail):
                        value = controller.attempt(str(uuid.uuid4()), FIXTURE_ID)
                    self.assertEqual("HALTED", value["status"])
                    self.assertEqual(1, len(producer.effects))
                    self.assertEqual("HALTED", controller.attempt(str(uuid.uuid4()), FIXTURE_ID)["status"])

    def test_actual_cas_conflict_halts_without_adopting_new_snapshot(self):
        self.start()
        original = STORE.JournalStore.append
        def conflict(store, event, expected):
            original(store, {"kind": "OBSERVATION", "data": observation(), "bootCount": 7,
                             "deviceElapsedMillis": self.time.ns // 1_000_000}, expected)
            return original(store, event, expected)
        with mock.patch.object(STORE.JournalStore, "append", new=conflict):
            self.assertEqual("HALTED", self.attempt()["status"])
        self.assertEqual("OBSERVATION", self.entries()[-1]["kind"])
        self.assertEqual(1, len(self.producer.effects))

    def test_lost_result_ack_preserves_actual_record_but_never_replays(self):
        self.start()
        original = STORE.JournalStore.append
        def lost(store, event, expected):
            ack = original(store, event, expected)
            if event["kind"] == "WORK_RESULT":
                raise OSError("lost terminal ACK")
            return ack
        with mock.patch.object(STORE.JournalStore, "append", new=lost):
            self.assertEqual("UNKNOWN", self.attempt()["status"])
        self.assertEqual("WORK_RESULT", self.entries()[-1]["kind"])
        self.assertEqual("UNKNOWN", self.attempt(11)["status"])
        self.assertEqual(2, len(self.producer.effects))

    def test_transport_timeout_unknown_or_wrong_typed_result_are_not_terminals(self):
        for terminal in (None, C.Outcome.UNKNOWN, C.Outcome.FIXTURE_READY):
            with self.subTest(terminal=terminal), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / "run"; producer = Producer(self.time, target)
                with C.HeavySoakController(target, plan(), configuration=self.configuration,
                        admission=self.admission, producer=producer) as controller:
                    controller.begin(); controller.prepare_fixture(FIXTURE_ID)
                    producer.failure = TimeoutError("PRIVATE_SECRET_SENTINEL") if terminal is None else None
                    producer.outcome = terminal
                    self.assertEqual("UNKNOWN", controller.attempt(str(uuid.uuid4()), FIXTURE_ID)["status"])
                    entries = json.loads((target / STORE.STATE).read_bytes())["journal"]["entries"]
                    self.assertEqual("WORK_INTENT", entries[-1]["kind"])

    def test_only_actual_core_timed_out_result_is_recorded(self):
        self.start(); self.producer.outcome = C.Outcome.TIMED_OUT
        self.assertEqual("RECORDED", self.attempt()["status"])
        self.assertEqual("TIMED_OUT", self.entries()[-1]["data"]["outcome"])

    def test_live_permit_is_revalidated_after_durable_intent(self):
        self.start()
        original = STORE.JournalStore.append
        def revoke(store, event, expected):
            ack = original(store, event, expected)
            if event["kind"] == "WORK_INTENT":
                self.admission.valid = False
            return ack
        with mock.patch.object(STORE.JournalStore, "append", new=revoke):
            self.assertEqual("UNKNOWN", self.attempt()["status"])
        self.assertEqual(1, len(self.producer.effects))
        self.assertEqual("WORK_INTENT", self.entries()[-1]["kind"])

    def test_foreign_or_retained_terminal_cannot_complete_another_attempt(self):
        self.start()
        original = self.producer.invoke
        def foreign(grant, binding, request):
            actual = original(grant, binding, request)
            return C.EffectResult(actual.outcome, actual.stamp,
                                  C.Request(request.kind, request.fixture_id, request.attempt_id), grant)
        with mock.patch.object(self.producer, "invoke", side_effect=foreign):
            self.assertEqual("UNKNOWN", self.attempt()["status"])
        self.assertEqual("WORK_INTENT", self.entries()[-1]["kind"])

    def test_device_reboot_after_intent_does_not_write_old_terminal_or_effect(self):
        self.start()
        self.producer.after_intent = lambda: setattr(self.producer, "device_boot", 8)
        self.assertEqual("UNKNOWN", self.attempt()["status"])
        self.assertEqual("WORK_INTENT", self.entries()[-1]["kind"])
        self.assertEqual(1, len(self.producer.effects))

    def test_device_elapsed_rollback_after_ack_halts_before_effect(self):
        self.start()
        self.producer.after_intent = lambda: setattr(self.producer, "device_offset", -1)
        self.assertEqual("UNKNOWN", self.attempt()["status"])
        self.assertEqual(1, len(self.producer.effects))
        self.assertEqual("WORK_INTENT", self.entries()[-1]["kind"])

    def test_gap_suspend_or_window_expiry_after_intent_never_catches_up(self):
        for duration, suspend in ((3_600_001, False), (1, True), (STORE.PROTOCOL.DAY, False)):
            with self.subTest(duration=duration, suspend=suspend), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / "run"; producer = Producer(self.time, target)
                with C.HeavySoakController(target, plan(), configuration=self.configuration,
                        admission=self.admission, producer=producer) as controller:
                    controller.begin(); controller.prepare_fixture(FIXTURE_ID)
                    producer.after_intent = lambda: self.time.advance(duration, suspend=suspend)
                    self.assertEqual("UNKNOWN", controller.attempt(str(uuid.uuid4()), FIXTURE_ID)["status"])
                    self.assertEqual(1, len(producer.effects))

    def test_resource_cooldown_exact_boundary_and_death_limit(self):
        self.start(); self.producer.outcome = C.Outcome.RESOURCE_EXIT
        self.assertEqual("RECORDED", self.attempt()["status"])
        self.time.advance(4999)
        self.assertEqual("RESOURCE_COOLDOWN", self.attempt(11)["reason"])
        self.time.advance(1)
        self.assertEqual("RECORDED", self.attempt(12)["status"])
        self.assertEqual(3, len(self.producer.effects))

    def test_resource_death_limit_records_refusal_without_more_effects(self):
        target = Path(self.temporary.name) / "limit"
        selected = plan(); selected["limits"]["maximumAttempts"] = 4
        producer = Producer(self.time, target)
        with C.HeavySoakController(target, selected, configuration=self.configuration,
                admission=self.admission, producer=producer) as controller:
            controller.begin(); controller.prepare_fixture(FIXTURE_ID)
            producer.outcome = C.Outcome.RESOURCE_EXIT
            self.assertEqual("RECORDED", controller.attempt(str(uuid.UUID(int=10)), FIXTURE_ID)["status"])
            self.time.advance(5000)
            self.assertEqual("RECORDED", controller.attempt(str(uuid.UUID(int=11)), FIXTURE_ID)["status"])
            value = controller.attempt(str(uuid.UUID(int=12)), FIXTURE_ID)
            self.assertEqual(("REFUSED", "RESOURCE_LIMIT"), (value["status"], value["reason"]))
            self.assertEqual(3, len(producer.effects))
            state = json.loads((target / STORE.STATE).read_bytes())
            self.assertEqual("VALID", STORE.PROTOCOL.validate(selected, state["journal"])["structuralStatus"])

    def test_duplicate_attempt_id_never_repeats_effect(self):
        self.start()
        self.assertEqual("RECORDED", self.attempt()["status"])
        self.assertEqual("HALTED", self.attempt()["status"])
        self.assertEqual(2, len(self.producer.effects))

    def test_reused_permit_or_handle_is_refused_before_a_second_intent(self):
        self.start()
        self.admission.reused = C.Grant(Permit(), Handle(), "6" * 64)
        self.assertEqual("RECORDED", self.attempt()["status"])
        self.time.advance(1000)
        self.assertEqual("HALTED", self.attempt(11)["status"])
        self.assertEqual(2, len(self.producer.effects))
        self.assertEqual(1, sum(item["kind"] == "WORK_INTENT" for item in self.entries()))

    def test_attempt_bound_includes_actual_refusals(self):
        self.start()
        with mock.patch.object(self.admission, "acquire", return_value=C.Refusal.ADMISSION_REFUSED):
            for number in (10, 11, 12):
                self.assertEqual("REFUSED", self.attempt(number)["status"])
            self.assertEqual("HALTED", self.attempt(13)["status"])
        self.assertEqual(1, len(self.producer.effects))
        self.assertEqual(3, sum(item["kind"] == "WORK_INTENT" for item in self.entries()))

    def test_cadence_is_not_caught_up_by_an_immediate_next_attempt(self):
        self.start(); self.attempt()
        self.assertEqual("HALTED", self.attempt(11)["status"])
        self.assertEqual(2, len(self.producer.effects))

    def test_host_boot_change_regression_or_unknown_boot_halts(self):
        self.start()
        for fault in ("boot", "rollback", "unknown"):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / "run"
                source = Time(); producer = Producer(source, target)
                with mock.patch.object(C.HostClock, "linux", return_value=mock_platform_clock(source)), \
                        C.HeavySoakController(target, plan(), configuration=self.configuration,
                        admission=self.admission, producer=producer) as controller:
                    controller.begin(); controller.prepare_fixture(FIXTURE_ID)
                    if fault == "boot":
                        source.boot = str(uuid.UUID(int=901))
                    elif fault == "rollback":
                        source.ns -= 1
                    else:
                        source.boot = None
                    self.assertEqual("HALTED", controller.attempt(str(uuid.uuid4()), FIXTURE_ID)["status"])
                    self.assertEqual(1, len(producer.effects))

    def test_foreign_live_binding_prevents_effect(self):
        self.start()
        self.producer.foreign = True
        self.assertEqual("UNKNOWN", self.attempt()["status"])
        self.assertEqual(1, len(self.producer.effects))

    def test_unknown_host_boot_refuses_before_run_begin_without_zero_stamp(self):
        self.controller.close()
        target = Path(self.temporary.name) / "unknown-clock"
        with mock.patch.object(C.HostClock, "linux", return_value=mock_platform_clock(self.time, boot_known=False)), \
                C.HeavySoakController(target, plan(), configuration=self.configuration,
                admission=self.admission, producer=Producer(self.time, target)) as controller:
            self.assertEqual("HALTED", controller.begin()["status"])
        self.assertEqual([], json.loads((target / STORE.STATE).read_bytes())["journal"]["entries"])

    def test_public_constructor_has_no_clock_override(self):
        target = Path(self.temporary.name) / "override"
        with self.assertRaises(TypeError):
            C.HeavySoakController(target, plan(), clock=HostClock(lambda: self.time.ns))
        self.assertFalse(target.exists())

    def test_synthetic_unavailable_or_wrong_evidence_clock_is_rejected_before_producer(self):
        for metadata in ({"clockSource": "SYNTHETIC_INJECTED", "evidence": "UNVERIFIED_INPUT"},
                         {"clockSource": "UNAVAILABLE", "evidence": "UNAVAILABLE"},
                         {"clockSource": "CLOCK_BOOTTIME", "evidence": "UNVERIFIED_INPUT"},
                         {"clockSource": "CLOCK_BOOTTIME", "evidence": "HOST_CLOCK_ONLY", "sessionId": "invalid"}):
            with self.subTest(metadata=metadata), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / "run"; producer = Producer(self.time, target)
                clock = HostClock(lambda: self.time.ns, lambda: self.time.boot)
                clock.metadata.update(metadata)
                with mock.patch.object(C.HostClock, "linux", return_value=clock) as factory, \
                        mock.patch.object(producer, "stamp", wraps=producer.stamp) as stamp, \
                        C.HeavySoakController(target, plan(), configuration=self.configuration,
                        admission=self.admission, producer=producer) as controller:
                    value = controller.begin()
                    self.assertEqual("HALTED", value["status"])
                    self.assertEqual("HALTED", controller.prepare_fixture(FIXTURE_ID)["status"])
                    self.assertEqual("HALTED", controller.attempt(str(uuid.uuid4()), FIXTURE_ID)["status"])
                    factory.assert_called_once_with()
                    stamp.assert_not_called()
                    self.assertEqual([], producer.effects)
                    for flag in C.FALSE_FLAGS:
                        self.assertIs(False, value[flag])
                self.assertEqual([], json.loads((target / STORE.STATE).read_bytes())["journal"]["entries"])

    def test_platform_clock_factory_failure_refuses_before_producer(self):
        for failure in (OSError("PRIVATE_SECRET_SENTINEL"), RuntimeError("clock unavailable")):
            with self.subTest(failure=type(failure)), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / "run"; producer = Producer(self.time, target)
                with mock.patch.object(C.HostClock, "linux", side_effect=failure), \
                        mock.patch.object(producer, "stamp", wraps=producer.stamp) as stamp, \
                        C.HeavySoakController(target, plan(), configuration=self.configuration,
                        admission=self.admission, producer=producer) as controller:
                    value = controller.begin()
                    self.assertEqual("HALTED", value["status"])
                    self.assertEqual("HALTED", controller.prepare_fixture(FIXTURE_ID)["status"])
                    stamp.assert_not_called()
                    self.assertEqual([], producer.effects)
                    self.assertNotIn("PRIVATE_SECRET_SENTINEL", json.dumps(value))
                self.assertEqual([], json.loads((target / STORE.STATE).read_bytes())["journal"]["entries"])

    def test_interrupt_during_effect_preserves_pending_and_original_exception(self):
        self.start()
        failure = KeyboardInterrupt("PRIVATE_SECRET_SENTINEL")
        self.producer.failure = failure
        with self.assertRaises(KeyboardInterrupt) as raised:
            self.attempt()
        self.assertIs(failure, raised.exception)
        self.assertEqual("UNKNOWN", self.attempt(11)["status"])
        self.assertEqual("WORK_INTENT", self.entries()[-1]["kind"])

    def test_observation_is_typed_bounded_and_cannot_persist_sensitive_fields(self):
        self.start()
        self.assertEqual("RECORDED", self.controller.observe()["status"])
        self.assertEqual("OBSERVATION", self.entries()[-1]["kind"])
        row = observation(); row["serviceState"] = "PRIVATE_SECRET_SENTINEL"
        with mock.patch.object(self.producer, "observe", return_value=C.Observation(
                self.producer.stamp(self.controller.binding), C.ResourceObservation(**row))):
            self.assertEqual("HALTED", self.controller.observe()["status"])
        self.assertNotIn("PRIVATE_SECRET_SENTINEL", (self.directory / STORE.STATE).read_text())

    def test_concurrent_step_cannot_repeat_the_same_attempt(self):
        self.start()
        self.producer.entered, self.producer.release = threading.Event(), threading.Event()
        results = []
        thread = threading.Thread(target=lambda: results.append(self.attempt()))
        thread.start(); self.assertTrue(self.producer.entered.wait(5))
        self.assertEqual("BUSY", self.attempt()["status"])
        self.producer.release.set(); thread.join(5)
        self.assertFalse(thread.is_alive())
        self.assertEqual("RECORDED", results[0]["status"])
        self.assertEqual(2, len(self.producer.effects))

    def test_missing_ports_and_json_hashes_never_grant_execution(self):
        self.controller.close()
        for missing in ("configuration", "admission", "producer"):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as directory:
                self.directory = Path(directory) / "run"
                with self.make(**{missing: None}) as controller:
                    self.assertEqual("HALTED", controller.begin()["status"])
                    self.assertEqual("HALTED", controller.prepare_fixture(FIXTURE_ID)["status"])
        self.assertEqual([], self.producer.effects)

    def test_source_binding_mismatch_cannot_start_a_run(self):
        with mock.patch.object(self.configuration, "bind", side_effect=lambda specification:
                C.Binding(replace(specification, plan_sha256="0" * 64), 7)):
            self.assertEqual("HALTED", self.controller.begin()["status"])
        self.assertEqual([], self.entries())
        self.assertEqual([], self.producer.effects)

    def test_plan_copy_frozen_records_flags_and_sensitive_input(self):
        self.selected["limits"]["maximumAttempts"] = 100
        self.assertEqual(3, self.controller.specification.maximum_attempts)
        self.start()
        with self.assertRaises(FrozenInstanceError):
            self.controller.binding.boot_count = 99
        value = self.controller.attempt("PRIVATE_SECRET_SENTINEL", FIXTURE_ID)
        self.assertNotIn("PRIVATE_SECRET_SENTINEL", json.dumps(value))
        self.assertNotIn("PRIVATE_SECRET_SENTINEL", (self.directory / STORE.STATE).read_text())
        self.assertEqual("NOT_QUALIFIED", value["qualification"])
        for key in C.FALSE_FLAGS:
            self.assertIs(False, value[key])

    def test_no_resume_after_real_process_exit_between_ack_and_effect(self):
        self.controller.close()
        target = Path(self.temporary.name) / "crash"
        def child():
            local_time = Time(); producer = Producer(local_time, target)
            with mock.patch.object(C.HostClock, "linux", return_value=mock_platform_clock(local_time)), \
                    C.HeavySoakController(target, plan(), configuration=Configuration(),
                    admission=Admission(), producer=producer) as controller:
                controller.begin(); controller.prepare_fixture(FIXTURE_ID)
                producer.after_intent = lambda: os._exit(73)
                controller.attempt(str(uuid.UUID(int=10)), FIXTURE_ID)
        process = multiprocessing.get_context("fork").Process(target=child)
        process.start(); process.join(10)
        if process.is_alive():
            process.kill(); process.join()
            self.fail("synthetic crash fixture did not terminate")
        self.assertEqual(73, process.exitcode)
        snapshot = STORE.operation("inspect", target)
        self.assertIn("UNRESOLVED_WORK", snapshot["validation"]["issues"])
        with self.assertRaises(C.ControllerError):
            C.HeavySoakController(target, plan())


if __name__ == "__main__":
    unittest.main()
