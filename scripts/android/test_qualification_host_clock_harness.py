#!/usr/bin/env python3
"""Pure HOST clocks/windows; no device, real suspend or qualification evidence."""
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("host_clock_test", Path(__file__).with_name("qualification_host_clock.py"))
CLOCK = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(CLOCK)
BOOT_A = "00000000-0000-0000-0000-000000000001"
BOOT_B = "00000000-0000-0000-0000-000000000002"


class HostClockTest(unittest.TestCase):
    def test_linux_source_requires_boottime_and_pins_hashed_boot(self):
        with patch.object(CLOCK, "linux_boot_identity", return_value=BOOT_A), \
                patch.object(CLOCK.time, "clock_gettime_ns", return_value=42) as read:
            clock = CLOCK.HostClock.linux()
            self.assertEqual(42, clock.read_ns())
            read.assert_called_with(CLOCK.time.CLOCK_BOOTTIME)
        self.assertEqual("CLOCK_BOOTTIME", clock.metadata["clockSource"])
        self.assertEqual("NOT_QUALIFIED", clock.metadata["qualification"])
        self.assertNotIn(BOOT_A, json.dumps(clock.metadata))
        self.assertEqual(64, len(clock.metadata["bootIdentitySha256"]))

    def test_missing_boottime_or_unavailable_source_never_falls_back(self):
        with patch.object(CLOCK.sys, "platform", "unsupported"), \
                patch.object(CLOCK.time, "monotonic", side_effect=AssertionError("Fallback")):
            with self.assertRaisesRegex(CLOCK.HostTimingError, "CLOCK_BOOTTIME_UNAVAILABLE"):
                CLOCK.HostClock.linux()
        for error in (OSError("SECRET"), NotImplementedError("SECRET")):
            with patch.object(CLOCK, "linux_boot_identity", return_value=BOOT_A), \
                    patch.object(CLOCK.time, "clock_gettime_ns", side_effect=error), \
                    self.assertRaisesRegex(CLOCK.HostTimingError, "HOST_CLOCK_UNAVAILABLE"):
                CLOCK.HostClock.linux()

    def test_invalid_or_regressed_time_poison_the_session_without_continuity(self):
        for bad in (True, -1, 1.0, float("nan"), float("inf"), 2**63):
            clock = CLOCK.HostClock(lambda: bad)
            with self.subTest(bad=bad), self.assertRaisesRegex(CLOCK.HostTimingError, "INVALID_CLOCK_VALUE"):
                clock.read_ns()
        now = [5]; clock = CLOCK.HostClock(lambda: now[0]); clock.read_ns(); now[0] = 4
        with self.assertRaisesRegex(CLOCK.HostTimingError, "HOST_CLOCK_REGRESSED"): clock.read_ns()
        now[0] = 6
        with self.assertRaisesRegex(CLOCK.HostTimingError, "HOST_CLOCK_REGRESSED"): clock.read_ns()

    def test_boot_change_or_unavailable_boot_never_compares_new_boot_elapsed(self):
        boot = [BOOT_A]; now = [100]
        clock = CLOCK.HostClock(lambda: now[0], lambda: boot[0]); clock.read_ns()
        boot[0] = BOOT_B; now[0] = 1
        with self.assertRaisesRegex(CLOCK.HostTimingError, "HOST_BOOT_CHANGED"): clock.read_ns()
        for invalid in (None, "SECRET", BOOT_A.upper() + "/invalid"):
            with self.assertRaises(CLOCK.HostTimingError): CLOCK.HostClock(lambda: 1, lambda: invalid).read_ns()

    def test_injected_seconds_api_is_synthetic_and_rejects_nonfinite_inputs(self):
        clock = CLOCK.clock_for(lambda: 1.5)
        self.assertEqual(1_500_000_000, clock.read_ns())
        self.assertEqual("SYNTHETIC_INJECTED", clock.metadata["clockSource"])
        self.assertEqual("UNVERIFIED_INPUT", clock.metadata["evidence"])
        self.assertIsNone(clock.metadata["bootIdentitySha256"])
        for bad in (float("nan"), float("inf"), -1, True):
            with self.assertRaises(CLOCK.HostTimingError): CLOCK.clock_for(lambda: bad).read_ns()
        with self.assertRaises(CLOCK.HostTimingError): CLOCK.clock_for(lambda: 0, clock)

    def test_source_errors_or_unknown_failure_codes_never_export_raw_text(self):
        def fail(): raise RuntimeError("SECRET source path")
        clock = CLOCK.HostClock(fail)
        with self.assertRaisesRegex(CLOCK.HostTimingError, "^HOST_CLOCK_UNAVAILABLE$"):
            clock.read_ns()
        self.assertEqual("HOST_CLOCK_UNAVAILABLE", str(CLOCK.HostTimingError("SECRET source path")))

    def test_suspend_is_visible_even_when_active_monotonic_is_frozen(self):
        active = [0]; boot = [0]
        clock = CLOCK.HostClock(lambda: boot[0])
        window = CLOCK.ObservationWindow(clock, 100, 5)
        window.start_sample(); window.complete_sample()
        active[0] += 5; boot[0] += 65 * CLOCK.NANOS
        self.assertEqual(5, active[0])
        window.start_sample()
        self.assertEqual({"START_TO_START": 1}, window.gaps)
        with self.assertRaisesRegex(CLOCK.HostTimingError, "HOST_OBSERVATION_GAP"): window.require_effect()

    def test_gap_inside_read_and_after_final_sample_are_explicit(self):
        now = [0]; window = CLOCK.ObservationWindow(CLOCK.HostClock(lambda: now[0]), 10, 5)
        window.start_sample(); now[0] = 11 * CLOCK.NANOS; window.complete_sample()
        self.assertEqual({"READ_IN_PROGRESS": 1}, window.gaps)
        now[0] += 11 * CLOCK.NANOS; window.end_check()
        self.assertEqual(1, window.gaps["END_WINDOW"])
        self.assertEqual(11 * CLOCK.NANOS, window.elapsed_ns)  # Not inflated by identity work.

    def test_nanosecond_boundaries_and_exact_deadline_are_not_rounded_into_admission(self):
        now = [0]; window = CLOCK.ObservationWindow(CLOCK.HostClock(lambda: now[0]), 10, 5)
        window.start_sample(); now[0] = 10 * CLOCK.NANOS - 1
        self.assertEqual(now[0], window.require_effect())
        now[0] += 1
        with self.assertRaisesRegex(CLOCK.HostTimingError, "WINDOW_ENDED_BEFORE_EFFECT"): window.require_effect()
        window.complete_sample(); self.assertEqual({}, window.gaps)
        now[0] += 10 * CLOCK.NANOS + 1; window.end_check()
        self.assertEqual({"END_WINDOW": 1}, window.gaps)


if __name__ == "__main__": unittest.main()
