#!/usr/bin/env python3
"""HOST tests for the host-only ADB harness; no device or ADB commands are executed."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'orion_runtime_harness', Path(__file__).with_name('verify-foundation-runtime.py'))
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


class ControlledProcessDeathTest(unittest.TestCase):
    def test_single_positive_pid_is_signalled_only_under_package_uid(self):
        calls = []
        harness.terminate_debug_main(lambda *args: calls.append(args), '1234')
        self.assertEqual([('shell', 'run-as', 'network.orion', 'kill', '-9', '1234')], calls)

    def test_group_negative_multiple_and_shell_input_never_reach_adb(self):
        for value in ('', '0', '-1', '-1234', '1 2', '1\n2', '123\n', '+123', '1;id', '$(id)'):
            with self.subTest(value=value):
                calls = []
                with self.assertRaises(RuntimeError):
                    harness.terminate_debug_main(lambda *args: calls.append(args), value)
                self.assertEqual([], calls)

    def test_denied_debug_uid_has_no_force_stop_or_root_fallback(self):
        calls = []

        def denied(*args):
            calls.append(args)
            raise RuntimeError('run-as denied')

        with self.assertRaises(RuntimeError):
            harness.terminate_debug_main(denied, '1234')
        self.assertEqual([('shell', 'run-as', 'network.orion', 'kill', '-9', '1234')], calls)


if __name__ == '__main__':
    unittest.main()
