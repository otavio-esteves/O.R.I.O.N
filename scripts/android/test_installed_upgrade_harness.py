#!/usr/bin/env python3
"""HOST contract tests; no ADB or device is contacted."""
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("orion_upgrade_recorder", Path(__file__).with_name("verify-installed-upgrade.py"))
RECORDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RECORDER)


class InstalledUpgradeRecorderTest(unittest.TestCase):
    def test_invalid_fixture_or_mode_cannot_produce_device_command(self):
        fixture = "00000000-0000-0000-0000-000000000001"
        for mode in ("prepare", "verify"):
            command = RECORDER.instrumentation_command(mode, fixture)
            self.assertIn("network.orion.app.UpgradePersistenceTest", command)
            self.assertEqual(fixture, command[command.index("orion_upgrade_fixture") + 1])
        for mode, value in (("reset", fixture), ("prepare", "../fixture"), ("verify", "0-0-0-0-1")):
            with self.assertRaises(ValueError):
                RECORDER.instrumentation_command(mode, value)

    def test_aborted_or_failed_instrumentation_cannot_be_reported_as_success(self):
        RECORDER.verify_instrumentation("\nTime: 1.0\n\nOK (1 test)\n")
        for value in ("", "OK (2 tests)", "prefix OK (1 test)", "OK (1 test)\nFAILURES!!!",
                      "OK (1 test)\nINSTRUMENTATION_FAILED", "OK (1 test)\nProcess crashed"):
            with self.assertRaises(RuntimeError):
                RECORDER.verify_instrumentation(value)

    def test_package_version_must_be_present_and_unambiguous(self):
        self.assertEqual(2, RECORDER.installed_version("  versionCode=2 minSdk=31 targetSdk=37\n"))
        for value in ("", "versionCode=-1", "versionCode=1\nversionCode=2"):
            with self.assertRaises(RuntimeError):
                RECORDER.installed_version(value)


if __name__ == "__main__":
    unittest.main()
