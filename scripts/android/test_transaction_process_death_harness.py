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

    def test_memory_scenario_uses_the_owned_forget_boundary(self):
        fixture = "00000000-0000-0000-0000-000000000001"
        for mode in ["prepare", "verify"]:
            command = RECORDER.instrumentation_command(mode, fixture, "memory")
            self.assertEqual("network.orion.app.memories.OrionMemoryTransactionProcessDeathTest",
                             command[command.index("class") + 1])

    def test_notification_scenario_uses_the_committed_external_claim_boundary(self):
        fixture = "00000000-0000-0000-0000-000000000001"
        for mode in ["prepare", "verify"]:
            command = RECORDER.instrumentation_command(mode, fixture, "notification")
            self.assertEqual("network.orion.app.notifications.OrionNotificationTransactionProcessDeathTest",
                             command[command.index("class") + 1])

    def test_local_scenarios_preserve_the_legacy_room_rollback_proof_labels(self):
        for scenario in ["generic", "task", "reminder", "timer", "memory"]:
            with self.subTest(scenario=scenario):
                proof = RECORDER.scenario_proof(scenario)
                self.assertEqual("kill_during_room_transaction", proof["killCheck"])
                self.assertEqual("committed_present_uncommitted_absent", proof["verificationCheck"])
                self.assertEqual({"checkpointBoundary": "OPEN_ROOM_TRANSACTION"}, proof["metadata"])

    def test_notification_proof_distinguishes_committed_claim_and_synthetic_external_marker(self):
        proof = RECORDER.scenario_proof("notification")
        self.assertEqual("kill_after_committed_external_claim", proof["killCheck"])
        self.assertEqual("uncertain_external_effect_not_replayed", proof["verificationCheck"])
        self.assertEqual("AFTER_COMMITTED_RUNNING_OUTSIDE_ROOM_TRANSACTION", proof["metadata"]["checkpointBoundary"])
        self.assertEqual("SYNTHETIC_DURABLE_MARKER", proof["metadata"]["externalEvidence"])
        self.assertEqual("SEPARATE_PLATFORM_TEST", proof["metadata"]["notificationManagerEvidence"])
        local = RECORDER.scenario_proof("generic")
        self.assertNotEqual(local["killCheck"], proof["killCheck"])
        self.assertNotEqual(local["verificationCheck"], proof["verificationCheck"])

    def test_unknown_scenario_cannot_select_a_checkpoint_or_verification_claim(self):
        for scenario in ["", "arbitrary.class", "notification;echo unsafe", "new_external_boundary"]:
            with self.subTest(scenario=scenario):
                with self.assertRaises(ValueError):
                    RECORDER.scenario_proof(scenario)
        # Every declared instrumentation scenario needs an explicit proof declaration.
        for scenario in RECORDER.SCENARIOS:
            self.assertIn("verificationCheck", RECORDER.scenario_proof(scenario))

    def test_proof_metadata_is_not_shared_mutable_report_state(self):
        proof = RECORDER.scenario_proof("notification")
        proof["killCheck"] = "incorrect_room_claim"
        proof["metadata"]["externalEvidence"] = "incorrect_platform_claim"
        fresh = RECORDER.scenario_proof("notification")
        self.assertEqual("kill_after_committed_external_claim", fresh["killCheck"])
        self.assertEqual("SYNTHETIC_DURABLE_MARKER", fresh["metadata"]["externalEvidence"])


if __name__ == "__main__":
    unittest.main()
