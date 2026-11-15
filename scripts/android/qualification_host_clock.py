"""Suspend-aware HOST qualification timing; unrelated to heavy journal v1 clocks."""
import hashlib
import math
from pathlib import Path
import sys
import time
import uuid

NANOS = 1_000_000_000
FAILURES = frozenset({"CLOCK_BOOTTIME_UNAVAILABLE", "HOST_BOOT_UNAVAILABLE",
                      "HOST_BOOT_CHANGED", "INVALID_CLOCK_VALUE", "HOST_CLOCK_REGRESSED",
                      "HOST_CLOCK_UNAVAILABLE", "AMBIGUOUS_CLOCK_SOURCE", "INVALID_CLOCK_SOURCE",
                      "HOST_OBSERVATION_GAP", "WINDOW_ENDED_BEFORE_EFFECT"})


class HostTimingError(RuntimeError):
    def __init__(self, code):
        self.code = code if type(code) is str and code in FAILURES else "HOST_CLOCK_UNAVAILABLE"
        super().__init__(self.code)  # Closed technical code only; no raw source/error data.


def seconds_ns(seconds):
    if type(seconds) not in (int, float) or not math.isfinite(seconds) or seconds < 0:
        raise HostTimingError("INVALID_CLOCK_VALUE")
    return round(seconds * NANOS)


def linux_boot_identity():
    value = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    if str(uuid.UUID(value)) != value:
        raise ValueError("Invalid boot identity.")
    return value


def unavailable_metadata():
    return {"schemaVersion": 1, "clockSource": "UNAVAILABLE",
            "bootIdentitySha256": None, "sessionId": str(uuid.uuid4()),
            "evidence": "UNAVAILABLE", "qualification": "NOT_QUALIFIED"}


class HostClock:
    def __init__(self, read_ns, boot_identity=None):
        # Any externally supplied source is synthetic, even if it calls BOOTTIME.
        self._read = read_ns
        self._boot = boot_identity
        self._identity = None
        self.last_ns = None
        self.failure = None
        self.metadata = unavailable_metadata()
        self.metadata.update(clockSource="SYNTHETIC_INJECTED", evidence="UNVERIFIED_INPUT")

    @classmethod
    def linux(cls):
        if sys.platform != "linux" or not hasattr(time, "CLOCK_BOOTTIME") or not hasattr(time, "clock_gettime_ns"):
            raise HostTimingError("CLOCK_BOOTTIME_UNAVAILABLE")
        clock = cls(lambda: time.clock_gettime_ns(time.CLOCK_BOOTTIME), linux_boot_identity)
        clock.metadata.update(clockSource="CLOCK_BOOTTIME", evidence="HOST_CLOCK_ONLY")
        clock.read_ns()  # Fail before any device operation; no fallback.
        return clock

    def read_ns(self):
        if self.failure:
            raise HostTimingError(self.failure)
        try:
            if self._boot is not None:
                identity = self._boot()
                # Strict UUID input; retain only its hash in public metadata.
                if type(identity) is not str or str(uuid.UUID(identity)) != identity:
                    raise HostTimingError("HOST_BOOT_UNAVAILABLE")
                digest = hashlib.sha256(identity.encode()).hexdigest()
                if self._identity is not None and digest != self._identity:
                    raise HostTimingError("HOST_BOOT_CHANGED")
                self._identity = digest
                self.metadata["bootIdentitySha256"] = digest
            value = self._read()
            if type(value) is not int or not 0 <= value <= 2**63 - 1:
                raise HostTimingError("INVALID_CLOCK_VALUE")
            if self.last_ns is not None and value < self.last_ns:
                raise HostTimingError("HOST_CLOCK_REGRESSED")
            self.last_ns = value
            return value
        except HostTimingError as error:
            self.failure = error.code
            raise
        except Exception:
            self.failure = "HOST_CLOCK_UNAVAILABLE"
            raise HostTimingError(self.failure) from None


def clock_for(monotonic=None, clock=None):
    if monotonic is not None and clock is not None:
        raise HostTimingError("AMBIGUOUS_CLOCK_SOURCE")
    if clock is not None:
        if not isinstance(clock, HostClock):
            raise HostTimingError("INVALID_CLOCK_SOURCE")
        return clock
    if monotonic is not None:
        return HostClock(lambda: seconds_ns(monotonic()))
    return HostClock.linux()


class ObservationWindow:
    """Cadence includes reads, sleep and endpoint checks; never extrapolate coverage."""
    def __init__(self, clock, duration, interval):
        self.clock = clock
        self.start_ns = clock.read_ns()
        self.deadline_ns = self.start_ns + seconds_ns(duration)
        self.maximum_gap_ns = seconds_ns(interval) * 2
        self.previous_start_ns = self.start_ns
        self.sample_start_ns = self.start_ns
        self.last_completed_ns = self.start_ns
        self.gaps = {}

    def gap(self, reason):
        self.gaps[reason] = self.gaps.get(reason, 0) + 1

    def start_sample(self):
        now = self.clock.read_ns()
        if now - self.previous_start_ns > self.maximum_gap_ns:
            self.gap("START_TO_START")
        self.previous_start_ns = self.sample_start_ns = now
        return now

    def complete_sample(self):
        now = self.clock.read_ns()
        if now - self.sample_start_ns > self.maximum_gap_ns:
            self.gap("READ_IN_PROGRESS")
        self.last_completed_ns = now
        return now

    def end_check(self):
        now = self.clock.read_ns()
        if now - self.last_completed_ns > self.maximum_gap_ns:
            self.gap("END_WINDOW")
        return now

    def require_effect(self):
        now = self.clock.read_ns()
        if now - self.sample_start_ns > self.maximum_gap_ns:
            self.gap("READ_IN_PROGRESS")
        if self.gaps:
            raise HostTimingError("HOST_OBSERVATION_GAP")
        if now >= self.deadline_ns:
            raise HostTimingError("WINDOW_ENDED_BEFORE_EFFECT")
        return now

    @property
    def elapsed_ns(self):
        return self.last_completed_ns - self.start_ns

    def remaining_seconds(self):
        return max(0, self.deadline_ns - self.clock.read_ns()) / NANOS

    def update_report(self, report):
        report.update(samplingGaps=sum(self.gaps.values()), samplingGapReasons=dict(self.gaps),
                      elapsedNanoseconds=self.elapsed_ns)

