#!/usr/bin/env python3
"""HOST contract checks; no adb/device/process is contacted."""
import importlib.util
from pathlib import Path
import unittest


SOURCE = Path(__file__).with_name("verify-transaction-process-death.py")
SPEC = importlib.util.spec_from_file_location("orion_transaction_recorder", SOURCE)
RECORDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RECORDER)


class TransactionRecorderContractTest(unittest.TestCase):
    def test_invalid_or_ambiguous_pid_cannot_reach_adb(self):
        for value in ["", "0", "-1", "1 2", "123\n", "123;echo unsafe", "$(echo 123)"]:
            calls = []
            with self.assertRaises(RuntimeError):
                RECORDER.terminate_debug_main(lambda *args: calls.append(args), value)
            self.assertEqual([], calls)

    def test_kill_uses_exact_debug_uid_pid_without_force_stop_or_root(self):
        calls = []
        RECORDER.terminate_debug_main(lambda *args: calls.append(args), "123")
        self.assertEqual([("shell", "run-as", "network.orion", "kill", "-9", "123")], calls)

    def test_fixture_and_mode_are_validated_before_device_command(self):
        fixture = "00000000-0000-0000-0000-000000000001"
        for mode in ["prepare", "verify"]:
            command = RECORDER.instrumentation_command(mode, fixture)
            self.assertIn("network.orion.app.TransactionProcessDeathTest", command)
            self.assertEqual(fixture, command[command.index("orion_transaction_fixture") + 1])
        for mode, bad_fixture in [("unknown", fixture), ("prepare", "unsafe/fixture"), ("verify", "0-0-0-0-1")]:
            with self.assertRaises(ValueError):
                RECORDER.instrumentation_command(mode, bad_fixture)

    def test_task_scenario_selects_only_the_owned_task_fixture(self):
        fixture = "00000000-0000-0000-0000-000000000001"
        for mode in ["prepare", "verify"]:
            command = RECORDER.instrumentation_command(mode, fixture, "task")
            self.assertIn("network.orion.app.tasks.OrionTaskTransactionProcessDeathTest", command)
            self.assertNotIn("network.orion.app.TransactionProcessDeathTest", command)
        for scenario in ["", "arbitrary.class", "task;echo unsafe"]:
            with self.assertRaises(ValueError):
                RECORDER.instrumentation_command("prepare", fixture, scenario)

    def test_reminder_scenario_uses_the_owned_reminder_boundary(self):
        fixture = "00000000-0000-0000-0000-000000000001"
        for mode in ["prepare", "verify"]:
            command = RECORDER.instrumentation_command(mode, fixture, "reminder")
            self.assertEqual("network.orion.app.reminders.OrionReminderTransactionProcessDeathTest",
                             command[command.index("class") + 1])
            self.assertNotIn("network.orion.app.tasks.OrionTaskTransactionProcessDeathTest", command)

    def test_timer_scenario_uses_the_owned_timer_boundary(self):
        fixture = "00000000-0000-0000-0000-000000000001"
        for mode in ["prepare", "verify"]:
            command = RECORDER.instrumentation_command(mode, fixture, "timer")
            self.assertEqual("network.orion.app.timers.OrionTimerTransactionProcessDeathTest",
                             command[command.index("class") + 1])


if __name__ == "__main__":
    unittest.main()
