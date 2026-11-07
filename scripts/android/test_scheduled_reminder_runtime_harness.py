#!/usr/bin/env python3
"""HOST contract checks only: no device or process is contacted."""
import importlib.util
from pathlib import Path
import unittest

SOURCE = Path(__file__).with_name("verify-scheduled-reminder-runtime.py")
SPEC = importlib.util.spec_from_file_location("orion_scheduled_runtime", SOURCE)
RECORDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RECORDER)


class ScheduledRuntimeContractTest(unittest.TestCase):
    fixture = "00000000-0000-0000-0000-000000000001"

    def test_fixture_modes_select_only_the_explicit_production_runtime_fixture(self):
        for mode in ("prepare", "verify"):
            command = RECORDER.instrumentation_command(mode, self.fixture)
            self.assertEqual(RECORDER.TEST_CLASS, command[command.index("class") + 1])
            self.assertEqual(mode, command[command.index("orion_scheduled_mode") + 1])
            self.assertEqual(self.fixture, command[command.index("orion_scheduled_fixture") + 1])

    def test_invalid_fixture_or_mode_never_builds_a_device_command(self):
        for mode, fixture in (("default", self.fixture), ("prepare", "../unsafe"), ("verify", "0-0-0-0-1")):
            with self.assertRaises(ValueError):
                RECORDER.instrumentation_command(mode, fixture)

    def test_optional_scenario_is_validated_and_passed_only_as_a_fixed_instrumentation_argument(self):
        self.assertNotIn("orion_scheduled_scenario", RECORDER.instrumentation_command("verify", self.fixture))
        for scenario in RECORDER.SCENARIOS:
            command = RECORDER.instrumentation_command("verify", self.fixture, scenario)
            self.assertEqual(scenario, command[command.index("orion_scheduled_scenario") + 1])
            self.assertEqual("network.orion.test/androidx.test.runner.AndroidJUnitRunner", command[-1])
        for invalid in ("", "force_stop;unsafe", "arbitrary.class"):
            with self.assertRaises(ValueError):
                RECORDER.instrumentation_command("verify", self.fixture, invalid)

    def test_reboot_and_force_stop_are_explicit_distinct_boundaries(self):
        self.assertEqual(["reboot"], RECORDER.lifecycle_command("reboot"))
        self.assertEqual(["shell", "am", "force-stop", "network.orion"], RECORDER.lifecycle_command("force_stop"))
        for scenario in ("", "sigkill", "reboot;unsafe"):
            with self.assertRaises(ValueError):
                RECORDER.lifecycle_command(scenario)

    def test_notification_observation_matches_only_the_exact_package_tag_and_id(self):
        key = "0|network.orion|0|" + self.fixture + "|10123"
        output = "\n".join((key, key, "0|other.package|0|" + self.fixture + "|10000",
                            "0|network.orion|9|" + self.fixture + "|10123",
                            "0|network.orion|0|different-tag|10123", "unrelated output"))
        self.assertEqual([key], RECORDER.matching_notification_keys(output, self.fixture))
        with self.assertRaises(ValueError):
            RECORDER.matching_notification_keys(output, "unsafe")

    def test_failed_instrumentation_cannot_pass_using_an_earlier_ok_line(self):
        self.assertTrue(RECORDER.instrumentation_passed("OK (1 test)\n"))
        for suffix in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed"):
            self.assertFalse(RECORDER.instrumentation_passed("OK (1 test)\n" + suffix))
        self.assertFalse(RECORDER.instrumentation_passed("OK (0 tests)"))

    def test_time_change_uses_only_the_explicit_alarm_service_epoch_command(self):
        self.assertEqual(["shell", "cmd", "alarm", "set-time", "1800000000000"],
                         RECORDER.lifecycle_command("time_change", epoch_millis=1800000000000))
        for invalid in (None, "1800000000000", "1;reboot", -1, 2**63, True):
            with self.assertRaises(ValueError):
                RECORDER.lifecycle_command("time_change", epoch_millis=invalid)
        with self.assertRaises(ValueError):
            RECORDER.lifecycle_command("time_change", epoch_millis=1, zone_id="Asia/Tokyo")

    def test_timezone_change_uses_only_a_bounded_olson_identifier(self):
        self.assertEqual(["shell", "cmd", "alarm", "set-timezone", "America/Sao_Paulo"],
                         RECORDER.lifecycle_command("timezone_change", zone_id="America/Sao_Paulo"))
        for invalid in (None, "", "Asia/Tokyo;reboot", "$(date)", "zone with spaces", "x" * 65):
            with self.assertRaises(ValueError):
                RECORDER.lifecycle_command("timezone_change", zone_id=invalid)
        with self.assertRaises(ValueError):
            RECORDER.lifecycle_command("timezone_change", zone_id="Asia/Tokyo", epoch_millis=1)

    def test_restored_clock_uses_device_baseline_and_monotonic_progress_only(self):
        self.assertEqual(1500000001250, RECORDER.restored_wall_millis(1500000000000, 1.25))
        self.assertEqual(1500000120000, RECORDER.restored_wall_millis(1500000000000, 120))
        self.assertEqual(0, RECORDER.restored_wall_millis(0, 0))
        for elapsed in (-1, float("nan"), float("inf"), 1e308, "1", True):
            with self.assertRaises(ValueError):
                RECORDER.restored_wall_millis(1500000000000, elapsed)
        for initial in (-1, "1500000000000", 2**63, True):
            with self.assertRaises(ValueError):
                RECORDER.restored_wall_millis(initial, 1)
        with self.assertRaises(ValueError):
            RECORDER.restored_wall_millis(2**63 - 1, 1)

    def test_timezone_alternative_is_explicit_distinct_and_safe_to_restore(self):
        self.assertEqual("America/Sao_Paulo", RECORDER.alternate_timezone("Asia/Tokyo"))
        self.assertEqual("Asia/Tokyo", RECORDER.alternate_timezone("America/Sao_Paulo"))
        self.assertEqual("Asia/Tokyo", RECORDER.alternate_timezone("Etc/UTC"))
        for zone in (None, "", "UTC;reboot"):
            with self.assertRaises(ValueError):
                RECORDER.alternate_timezone(zone)

    def test_all_declared_scenarios_have_one_explicit_command_contract(self):
        self.assertEqual({"reboot", "force_stop", "time_change", "timezone_change", "doze", "background_restriction"}, set(RECORDER.SCENARIOS))
        for scenario in ("reboot", "force_stop", "doze", "background_restriction"):
            with self.assertRaises(ValueError):
                RECORDER.lifecycle_command(scenario, epoch_millis=1)
            with self.assertRaises(ValueError):
                RECORDER.lifecycle_command(scenario, zone_id="Asia/Tokyo")

    def test_power_state_commands_are_explicit_without_privileged_or_whitelist_bypasses(self):
        self.assertEqual(["shell", "cmd", "deviceidle", "force-idle", "deep"], RECORDER.lifecycle_command("doze"))
        self.assertEqual(["shell", "appops", "set", "network.orion", "RUN_ANY_IN_BACKGROUND", "ignore"],
                         RECORDER.lifecycle_command("background_restriction"))
        for scenario in ("doze", "background_restriction"):
            command = RECORDER.lifecycle_command(scenario)
            for forbidden in ("root", "service", "whitelist", "tempwhitelist", "broadcast"):
                self.assertNotIn(forbidden, command)

    def test_disabled_deep_idle_is_explicitly_enabled_then_restored_to_disabled(self):
        state = {"enabled": False}
        calls = []
        def adb(*command):
            calls.append(command)
            if command == ("shell", "cmd", "deviceidle", "enabled", "deep"):
                return "1" if state["enabled"] else "0"
            if command == ("shell", "cmd", "deviceidle", "enable", "deep"):
                state["enabled"] = True
                return ""
            if command == ("shell", "cmd", "deviceidle", "disable", "deep"):
                state["enabled"] = False
                return ""
            self.fail("Unexpected device command")
        original = RECORDER.deep_idle_enabled(adb)
        RECORDER.ensure_deep_idle_enabled(adb, True)
        self.assertTrue(state["enabled"])
        RECORDER.ensure_deep_idle_enabled(adb, original)
        self.assertFalse(state["enabled"])
        self.assertEqual(1, calls.count(("shell", "cmd", "deviceidle", "enable", "deep")))
        self.assertEqual(1, calls.count(("shell", "cmd", "deviceidle", "disable", "deep")))

    def test_already_enabled_idle_needs_no_configuration_mutation_and_denied_enable_fails(self):
        calls = []
        def enabled_adb(*command):
            calls.append(command)
            return "1"
        RECORDER.ensure_deep_idle_enabled(enabled_adb, True)
        self.assertTrue(all(command == ("shell", "cmd", "deviceidle", "enabled", "deep") for command in calls))
        def refused_adb(*command):
            return "0"
        with self.assertRaises(RuntimeError):
            RECORDER.ensure_deep_idle_enabled(refused_adb, True)
        with self.assertRaises(RuntimeError):
            RECORDER.deep_idle_enabled(lambda *command: "unsupported")
        with self.assertRaises(ValueError):
            RECORDER.ensure_deep_idle_enabled(enabled_adb, "true")

    def test_forced_idle_requires_observed_screen_off_and_not_charging(self):
        self.assertTrue(RECORDER.idle_prerequisites("false", "false"))
        for screen, charging in (("true", "false"), ("false", "true"), ("true", "true")):
            self.assertFalse(RECORDER.idle_prerequisites(screen, charging))
        for screen, charging in (("unknown", "false"), ("false", "unsupported"), ("", "")):
            with self.assertRaises(RuntimeError):
                RECORDER.idle_prerequisites(screen, charging)


if __name__ == "__main__":
    unittest.main()
