"""Finite HOST control component, with no operational composition or execution CLI.

Typed ports are trusted composition dependencies, not authenticated provenance or
admission by themselves. A future adapter must preserve the real Core governor,
endpoint ownership and terminal semantics. No adapter is supplied here.
"""
from abc import ABC, abstractmethod
from dataclasses import dataclass, fields
from enum import Enum
from functools import wraps
import os
import threading
import time

import f5_heavy_soak_evidence_protocol as P
import f5_heavy_soak_journal as J
from qualification_host_clock import HostClock

FALSE_FLAGS = ("sourceAuthenticated", "physicalEvidenceVerified", "real24HourDurationProven",
               "autonomyProven", "workloadQualified", "profileQualified", "productionInferenceEnabled",
               "fullF4GateClosed", "fullF5GateClosed", "fullSoakGateClosed", "f6Authorized")


class ControllerError(ValueError):
    def __init__(self):
        super().__init__("CONTROLLER_INITIALIZATION_UNAVAILABLE")


class Check(Enum):
    VALID = "VALID"
    INVALID = "INVALID"


class Effect(Enum):
    PREPARE = "PREPARE"
    WORK = "WORK"
    CLEANUP = "CLEANUP"


class Outcome(Enum):
    FIXTURE_READY = "FIXTURE_READY"
    SUCCEEDED = "SUCCEEDED"
    CANCELLED = "CANCELLED"
    TIMED_OUT = "TIMED_OUT"  # Actual Core terminal only, never a local transport timeout.
    FAILED = "FAILED"
    REFUSED = "REFUSED"
    RESOURCE_EXIT = "RESOURCE_EXIT"
    REMOVED = "REMOVED"
    ABSENT = "ABSENT"
    UNAVAILABLE = "UNAVAILABLE"
    UNKNOWN = "UNKNOWN"


class Refusal(Enum):
    RESOURCE_COOLDOWN = "RESOURCE_COOLDOWN"
    RESOURCE_LIMIT = "RESOURCE_LIMIT"
    RESOURCE_UNAVAILABLE = "RESOURCE_UNAVAILABLE"
    RUNNER_UNAVAILABLE = "RUNNER_UNAVAILABLE"
    CONFIGURATION_UNAVAILABLE = "CONFIGURATION_UNAVAILABLE"
    ADMISSION_REFUSED = "ADMISSION_REFUSED"


class ResourceClass(Enum):
    MEMORY_LIMITER = "MEMORY_LIMITER"
    LOW_MEMORY = "LOW_MEMORY"
    EXCESSIVE_RESOURCE = "EXCESSIVE_RESOURCE"
    UNCLASSIFIED = "UNCLASSIFIED"


@dataclass(frozen=True, slots=True, repr=False)
class Specification:
    """Detached closed plan binding; numbers and hashes are never operating approval."""
    plan_sha256: str
    identity: tuple
    references: tuple
    fixtures: tuple
    duration_millis: int
    maximum_attempts: int
    minimum_interval_millis: int
    cooldown_millis: int
    maximum_deaths: int
    maximum_gap_millis: int

    @classmethod
    def from_plan(cls, plan):
        P.validate_plan(plan)
        limits = plan["limits"]
        return cls(P.plan_digest(plan), tuple(sorted(plan["identity"].items())),
                   tuple(plan[key] for key in ("deviceIdentitySha256", "runnerSha256", "trustedConfigurationSha256")),
                   tuple(plan["ownedFixtureIds"]), limits["targetDurationMillis"], limits["maximumAttempts"],
                   limits["minimumAttemptIntervalMillis"], limits["resourceExitCooldownMillis"],
                   limits["maximumResourceDeaths"], limits["maximumObservationGapMillis"])


@dataclass(frozen=True, slots=True, repr=False)
class Binding:
    specification: Specification
    boot_count: int


@dataclass(frozen=True, slots=True, repr=False)
class DeviceStamp:
    binding: Binding
    boot_count: int
    elapsed_millis: int


@dataclass(frozen=True, slots=True, repr=False)
class Request:
    kind: Effect
    fixture_id: str
    attempt_id: str | None = None


class LivePermit(ABC):
    """Opaque permit whose origin/freshness is checked by the trusted admission port."""


class OwnedHandle(ABC):
    """Opaque single-use handle whose ownership is checked by the trusted producer."""


@dataclass(frozen=True, slots=True, repr=False)
class Grant:
    permit: LivePermit
    handle: OwnedHandle
    decision_sha256: str


@dataclass(frozen=True, slots=True, repr=False)
class EffectResult:
    outcome: Outcome
    stamp: DeviceStamp
    request: Request
    grant: Grant
    reason: Refusal | None = None
    classification: ResourceClass | None = None


@dataclass(frozen=True, slots=True, repr=False)
class ResourceObservation:
    mainPid: int | None
    aiPid: int | None
    mainPssBytes: int | None
    aiPssBytes: int | None
    aggregatePssBytes: int | None
    thermalStatus: int | None
    memoryPressure: str
    serviceState: str
    nativeRuntimeLoaded: bool | None
    actionPendingCount: int | None
    actionUnknownCount: int | None


@dataclass(frozen=True, slots=True, repr=False)
class Observation:
    stamp: DeviceStamp
    resources: ResourceObservation


class ConfigurationPort(ABC):
    @abstractmethod
    def bind(self, specification: Specification) -> Binding | None:
        """Independently verify the exact reviewed configuration/artifacts and live target."""

    @abstractmethod
    def revalidate(self, binding: Binding, specification: Specification, stamp: DeviceStamp) -> Check:
        """Check current binding; matching JSON or a digest is insufficient."""


class AdmissionPort(ABC):
    @abstractmethod
    def acquire(self, binding: Binding, request: Request, stamp: DeviceStamp) -> Grant | Refusal:
        """Fresh real Core decision for this exact effect; never a cached JSON approval."""

    @abstractmethod
    def revalidate(self, grant: Grant, binding: Binding, request: Request, stamp: DeviceStamp) -> Check:
        """Check live permit identity, exact request, resource policy and expiration."""


class ProducerPort(ABC):
    @abstractmethod
    def stamp(self, binding: Binding) -> DeviceStamp:
        """Read actual bound device boot/time, without starting work."""

    @abstractmethod
    def revalidate(self, grant: Grant, binding: Binding, request: Request, stamp: DeviceStamp) -> Check:
        """Read-only ownership/endpoint check of this exact handle, without rebind."""

    @abstractmethod
    def invoke(self, grant: Grant, binding: Binding, request: Request) -> EffectResult:
        """One effect; adapter must atomically consume/recheck its Core authority.

        Transport timeout, disconnect or uncertain outcome is UNKNOWN/an exception,
        never TIMED_OUT. Only an actual bound Core terminal can return TIMED_OUT.
        """

    @abstractmethod
    def observe(self, binding: Binding) -> Observation:
        """Read-only fresh resources/queues; no work, recovery, binding or retry."""


class _Stop(Exception):
    pass


def _serial(method):
    @wraps(method)
    def guarded(self, *args):
        if os.getpid() != self._pid:
            return self._report("HALTED", "OWNER_CHANGED")
        if not self._lock.acquire(blocking=False):
            return self._report("BUSY")
        try:
            if self._halted or self._closed:
                return self._report("UNKNOWN" if self._uncertain else "HALTED", self._failure)
            try:
                return method(self, *args)
            except (KeyboardInterrupt, SystemExit):
                self._halt("INTERRUPTED", unknown=self._pending is not None)
                raise
            except Exception:
                return self._halt("CONTROL_UNAVAILABLE", unknown=self._pending is not None)
        finally:
            self._lock.release()
    return guarded


class HeavySoakController:
    """One new store, one live owner, no open/resume/adopt or execution loop.

    No production dependencies are installed by default. The type contracts permit
    synthetic fixtures; they do not authenticate an adapter or qualify a profile.
    """
    def __init__(self, directory, plan, *, configuration=None, admission=None, producer=None):
        self._pid = os.getpid()
        self._lock = threading.Lock()
        self._closed = self._halted = self._uncertain = False
        self._failure = None
        self._pending = None
        self._started = False
        self._binding = None
        self._configuration, self._admission, self._producer = configuration, admission, producer
        self._clock = None
        self._store = None
        self._entries = 0
        self._state_sha = None
        self._ready, self._intended, self._cleaned, self._attempts = set(), set(), set(), set()
        self._used = []  # Retain references against id reuse, bounded by attempts + owned fixtures.
        self._deaths = 0
        self._resource_fence = self._last_work = self._last_device = None
        self._last_recorded_device = None
        self._host_previous = self._mono_previous = self._mono_before_previous = None
        self._host_session = self._host_boot = None
        self._start = self._last_observation = None
        try:
            # Validate/minimize before constructing files; the store independently freezes the plan.
            self._specification = Specification.from_plan(plan)
            self._store = J.JournalStore(directory, create=True)
            ack = self._store.initialize(plan)
            self._ack(ack, initial=True)
        except Exception:
            if self._store is not None:
                try:
                    self._store.close()
                except Exception:
                    pass
            raise ControllerError() from None

    @property
    def specification(self):
        return self._specification

    @property
    def binding(self):
        return self._binding

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def close(self):
        if os.getpid() != self._pid or not self._lock.acquire(blocking=False):
            return False
        try:
            if not self._closed:
                self._closed = True
                self._halted = True
                self._store.close()
            return True
        finally:
            self._lock.release()

    def _report(self, status, reason=None):
        return {"schemaVersion": 1, "kind": "HEAVY_SOAK_CONTROLLER", "qualification": "NOT_QUALIFIED",
                "result": "UNVERIFIED_INPUT", "environment": "HOST", "status": status, "reason": reason,
                "entriesAcknowledged": self._entries, "attemptsSelected": len(self._attempts),
                **{flag: False for flag in FALSE_FLAGS}}

    def _halt(self, reason, *, unknown=False):
        self._halted, self._uncertain, self._failure = True, unknown, reason
        return self._report("UNKNOWN" if unknown else "HALTED", reason)

    def _require(self, condition):
        if not condition:
            raise _Stop()

    def _ack(self, ack, *, initial=False):
        expected = J.result("INITIALIZED_DURABLE" if initial else "APPENDED_DURABLE",
                            snapshotSha256=None, entries=0, hostClockSource=J.CLOCK_SOURCE,
                            **({"planSha256": self._specification.plan_sha256} if initial else
                               {"previousSnapshotSha256": self._state_sha}))
        self._require(type(ack) is dict and set(ack) == set(expected))
        self._require(all(type(ack[key]) is type(value) and ack[key] == value
                          for key, value in expected.items() if key not in ("snapshotSha256", "entries")))
        P.digest(ack["snapshotSha256"])
        self._require(type(ack["entries"]) is int and ack["entries"] == (0 if initial else self._entries + 1))
        self._require(initial or ack["snapshotSha256"] != self._state_sha)
        self._state_sha = ack["snapshotSha256"]
        self._entries = ack["entries"]

    def _append(self, kind, data, stamp):
        self._validate_stamp(stamp, observation=False)
        self._require(self._entries < P.MAX_ENTRIES)
        ack = self._store.append({"kind": kind, "data": data, "bootCount": stamp.boot_count,
                                  "deviceElapsedMillis": stamp.elapsed_millis}, self._state_sha)
        self._ack(ack)
        self._last_recorded_device = stamp.elapsed_millis

    def _time(self, *, effect=False):
        before = time.monotonic_ns()
        now = self._clock.read_ns()
        after = time.monotonic_ns()
        self._require(type(before) is int and type(after) is int and 0 <= before <= after <= P.MAX_INT)
        meta = self._clock.metadata
        self._require(meta["clockSource"] == "CLOCK_BOOTTIME" and meta["evidence"] == "HOST_CLOCK_ONLY")
        P.identifier(meta["sessionId"]); P.digest(meta["bootIdentitySha256"])
        if self._host_session is None:
            self._host_session, self._host_boot = meta["sessionId"], meta["bootIdentitySha256"]
        self._require(meta["sessionId"] == self._host_session and meta["bootIdentitySha256"] == self._host_boot)
        if self._host_previous is not None:
            self._require(now >= self._host_previous and before >= self._mono_previous)
            # BOOTTIME includes suspend; MONOTONIC does not. The outer MONOTONIC
            # read bracket bounds measurement overhead, without accumulating or
            # relabeling either domain. A detected discontinuity permanently halts.
            self._require(now - self._host_previous <= after - self._mono_before_previous)
        self._host_previous, self._mono_previous, self._mono_before_previous = now, after, before
        if self._started:
            self._require(now - self._last_observation <= self._specification.maximum_gap_millis * 1_000_000)
            if effect:
                self._require(now - self._start < self._specification.duration_millis * 1_000_000)
        return now, after

    def _validate_stamp(self, stamp, *, observation=True):
        self._require(type(stamp) is DeviceStamp and stamp.binding is self._binding)
        P.integer(stamp.boot_count, 0, 2**31 - 1); P.integer(stamp.elapsed_millis)
        self._require(stamp.boot_count == self._binding.boot_count)
        previous = self._last_device if observation else self._last_recorded_device
        if previous is not None:
            self._require(stamp.elapsed_millis >= previous)
        if observation:
            self._last_device = stamp.elapsed_millis

    def _sample(self, *, effect=False):
        self._require(self._started)
        stamp = self._producer.stamp(self._binding)
        self._validate_stamp(stamp)
        host, mono = self._time(effect=effect)
        self._require(self._configuration.revalidate(self._binding, self._specification, stamp) is Check.VALID)
        return stamp, host, mono

    @_serial
    def begin(self):
        self._require(not self._started)
        if not isinstance(self._configuration, ConfigurationPort):
            return self._halt("CONFIGURATION_UNAVAILABLE")
        if not isinstance(self._admission, AdmissionPort) or not isinstance(self._producer, ProducerPort):
            return self._halt("RUNNER_UNAVAILABLE")
        self._require(all(reference is not None for reference in self._specification.references))
        self._binding = self._configuration.bind(self._specification)
        self._require(type(self._binding) is Binding and self._binding.specification == self._specification)
        P.integer(self._binding.boot_count, 0, 2**31 - 1)
        self._clock = HostClock.linux()
        self._require(type(self._clock) is HostClock)
        self._start, _ = self._time()
        stamp = self._producer.stamp(self._binding)
        self._validate_stamp(stamp)
        self._last_observation = self._start
        self._require(self._configuration.revalidate(self._binding, self._specification, stamp) is Check.VALID)
        self._append("RUN_BEGIN", {}, stamp)
        self._started = True
        return self._report("STARTED")

    def _resource_refusal(self, stamp, host):
        if self._deaths >= self._specification.maximum_deaths:
            return Refusal.RESOURCE_LIMIT
        if self._resource_fence is not None:
            old_host, old_device = self._resource_fence
            if min((host - old_host) // 1_000_000, stamp.elapsed_millis - old_device) < self._specification.cooldown_millis:
                return Refusal.RESOURCE_COOLDOWN
        return None

    @_serial
    def prepare_fixture(self, fixture_id):
        P.identifier(fixture_id)
        self._require(fixture_id in self._specification.fixtures and fixture_id not in self._intended)
        self._intended.add(fixture_id)
        return self._effect(Request(Effect.PREPARE, fixture_id))

    @_serial
    def attempt(self, attempt_id, fixture_id):
        P.identifier(attempt_id); P.identifier(fixture_id)
        self._require(fixture_id in self._ready and attempt_id not in self._attempts and
                      len(self._attempts) < self._specification.maximum_attempts)
        self._attempts.add(attempt_id)
        return self._effect(Request(Effect.WORK, fixture_id, attempt_id))

    @_serial
    def cleanup(self, fixture_id):
        P.identifier(fixture_id)
        self._require(fixture_id in self._intended and fixture_id not in self._cleaned)
        return self._effect(Request(Effect.CLEANUP, fixture_id))

    def _effect(self, request):
        self._require(self._entries + 2 <= P.MAX_ENTRIES)
        stamp, host, mono = self._sample(effect=True)
        refusal = self._resource_refusal(stamp, host) if request.kind is Effect.WORK else None
        if request.kind is Effect.WORK and refusal is None and self._last_work is not None:
            old_host, old_mono = self._last_work
            # Additional MONOTONIC guard preserves v1's admitted-intent cadence.
            self._require(min((host - old_host) // 1_000_000, (mono - old_mono) // 1_000_000) >=
                          self._specification.minimum_interval_millis)
        grant = refusal or self._admission.acquire(self._binding, request, stamp)
        self._require(type(grant) in (Grant, Refusal))
        if type(grant) is Refusal and request.kind is not Effect.WORK:
            return self._halt(grant.value)
        if type(grant) is Grant:
            self._require(isinstance(grant.permit, LivePermit) and isinstance(grant.handle, OwnedHandle))
            P.digest(grant.decision_sha256)
            self._require(not any(grant.permit is old.permit or grant.handle is old.handle for old in self._used))
            self._used.append(grant)
        self._pending = request
        kind = {Effect.PREPARE: "FIXTURE_INTENT", Effect.WORK: "WORK_INTENT", Effect.CLEANUP: "CLEANUP_INTENT"}[request.kind]
        data = {"fixtureId": request.fixture_id}
        if request.kind is Effect.WORK:
            data.update(attemptId=request.attempt_id, admissionDecisionSha256=
                        grant.decision_sha256 if type(grant) is Grant else None)
        try:
            self._append(kind, data, stamp)
        except Exception:
            # No effect was issued, but a lost/uncertain ACK is never retried or adopted.
            self._pending = None
            return self._halt("INTENT_ACK_UNAVAILABLE")
        if type(grant) is Refusal:
            fresh, _, _ = self._sample()
            self._append("WORK_RESULT", {"attemptId": request.attempt_id, "outcome": "REFUSED", "reason": grant.value}, fresh)
            self._pending = None
            return self._report("REFUSED", grant.value)
        # These are different finite phases. Nothing can dispatch from an ACK
        # boolean/hash alone; the trusted live dependencies are checked again.
        fresh, _, _ = self._sample(effect=True)
        self._require(self._producer.revalidate(grant, self._binding, request, fresh) is Check.VALID)
        fresh, host, mono = self._sample(effect=True)
        self._require(self._admission.revalidate(grant, self._binding, request, fresh) is Check.VALID)
        host, mono = self._time(effect=True)  # Admission itself cannot consume the remaining window.
        if request.kind is Effect.WORK:
            self._require(self._resource_refusal(fresh, host) is None)
            self._last_work = host, mono
        result = self._producer.invoke(grant, self._binding, request)
        self._require(type(result) is EffectResult and type(result.outcome) is Outcome)
        self._require(result.request is request and result.grant is grant)
        self._validate_stamp(result.stamp)
        # A fresh endpoint/boot observation after return fences a delayed terminal.
        # The actual terminal stamp remains in its own event; no time is invented.
        _, host, _ = self._sample()
        self._require(result.outcome is not Outcome.UNKNOWN)
        if request.kind is Effect.PREPARE:
            self._require(result.outcome is Outcome.FIXTURE_READY and result.reason is None and result.classification is None)
            self._append("FIXTURE_READY", {"fixtureId": request.fixture_id}, result.stamp)
            self._ready.add(request.fixture_id)
        elif request.kind is Effect.CLEANUP:
            self._require(result.outcome in (Outcome.REMOVED, Outcome.ABSENT, Outcome.FAILED, Outcome.UNAVAILABLE) and
                          result.reason is None and result.classification is None)
            self._append("CLEANUP_RESULT", {"fixtureId": request.fixture_id, "outcome": result.outcome.value}, result.stamp)
            if result.outcome in (Outcome.REMOVED, Outcome.ABSENT):
                self._cleaned.add(request.fixture_id); self._ready.discard(request.fixture_id)
            else:
                self._pending = None
                return self._halt("CLEANUP_UNAVAILABLE")
        else:
            if result.outcome is Outcome.RESOURCE_EXIT:
                self._require(type(result.classification) is ResourceClass and result.reason is None)
                self._append("RESOURCE_EXIT", {"attemptId": request.attempt_id,
                             "classification": result.classification.value}, result.stamp)
                self._deaths += 1
                self._resource_fence = host, result.stamp.elapsed_millis
            else:
                self._require(result.outcome in (Outcome.SUCCEEDED, Outcome.CANCELLED, Outcome.TIMED_OUT, Outcome.FAILED,
                                                Outcome.REFUSED) and result.classification is None)
                self._require(type(result.reason) is Refusal if result.outcome is Outcome.REFUSED else result.reason is None)
                self._append("WORK_RESULT", {"attemptId": request.attempt_id, "outcome": result.outcome.value,
                             "reason": result.reason.value if result.reason else "NONE"}, result.stamp)
        self._pending = None
        if host - self._start >= self._specification.duration_millis * 1_000_000:
            return self._halt("WINDOW_ENDED")
        return self._report("RECORDED")

    @_serial
    def observe(self):
        self._sample()
        result = self._producer.observe(self._binding)
        self._require(type(result) is Observation and type(result.resources) is ResourceObservation)
        self._validate_stamp(result.stamp)
        _, host, _ = self._sample()
        data = {field.name: getattr(result.resources, field.name) for field in fields(ResourceObservation)}
        available, queues = P.observation(data)
        self._require(available and queues)
        self._append("OBSERVATION", data, result.stamp)
        self._last_observation = host
        return self._report("RECORDED")

    @_serial
    def finish(self):
        self._require(self._pending is None and self._intended == self._cleaned)
        stamp, _, _ = self._sample()
        self._append("RUN_END", {"outcome": "FINISHED"}, stamp)
        self._halted, self._failure = True, "RUN_FINISHED"
        return self._report("FINISHED")  # Program end only; never duration/workload PASS.

    @_serial
    def interrupt(self):
        self._halted, self._uncertain, self._failure = True, self._pending is not None, "INTERRUPTED"
        # No later terminal/cleanup/resume is issued. Retained intent stays unresolved.
        return self._report("UNKNOWN" if self._uncertain else "HALTED", "INTERRUPTED")
