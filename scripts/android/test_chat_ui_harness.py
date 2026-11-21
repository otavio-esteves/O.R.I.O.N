"""No-device protocol, artifact and CI selection regressions; no model download."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
from chat_ui_protocol import EXPECTED, F8_SELECTED, SELECTED, junit_report, parse_instrumentation, test_identities

spec = importlib.util.spec_from_file_location('chat_ui_runner', Path(__file__).with_name('verify-chat-ui.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def transcript(identities=None, terminal=0, expected=EXPECTED):
    identities = sorted(expected) if identities is None else identities
    lines = []
    for index, (cls, method) in enumerate(identities, 1):
        frame = [f'INSTRUMENTATION_STATUS: class={cls}', f'INSTRUMENTATION_STATUS: test={method}',
                 f'INSTRUMENTATION_STATUS: current={index}', f'INSTRUMENTATION_STATUS: numtests={len(expected)}']
        lines += frame + ['INSTRUMENTATION_STATUS_CODE: 1'] + frame + [f'INSTRUMENTATION_STATUS_CODE: {terminal}']
    return '\n'.join(lines + [f'OK ({len(expected)} tests)', 'INSTRUMENTATION_CODE: -1'])


class ChatUiProtocolTest(unittest.TestCase):
    def test_exact_suite_passes_with_all_start_and_terminal_frames(self):
        result = parse_instrumentation(transcript(), 0)
        self.assertTrue(result.successful)
        self.assertEqual(len(EXPECTED), result.summary()['passed'])

    def test_zero_adb_exit_alone_never_proves_tests_passed(self):
        self.assertFalse(parse_instrumentation('INSTRUMENTATION_CODE: -1', 0).successful)

    def test_missing_or_additional_cases_cannot_pass(self):
        cases = sorted(EXPECTED)
        for identities in (cases[:-1], cases + [('network.orion.app.chat.OrionChatRealModelTest', 'requiresGguf')]):
            with self.subTest(count=len(identities)):
                self.assertFalse(parse_instrumentation(transcript(identities), 0).successful)

    def test_failure_error_skip_and_assumption_failure_are_rejected(self):
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code):
                self.assertFalse(parse_instrumentation(transcript(terminal=code), 0).successful)

    def test_duplicate_start_or_terminal_is_rejected(self):
        text = transcript()
        for duplicate in ('INSTRUMENTATION_STATUS_CODE: 1', 'INSTRUMENTATION_STATUS_CODE: 0'):
            self.assertFalse(parse_instrumentation(text.replace(duplicate, duplicate + '\n' + duplicate, 1), 0).successful)

    def test_foreign_terminal_before_any_start_is_rejected(self):
        self.assertFalse(parse_instrumentation(transcript().replace('INSTRUMENTATION_STATUS_CODE: 1', 'INSTRUMENTATION_STATUS_CODE: 0', 1), 0).successful)

    def test_wrong_counts_and_test_sequence_are_rejected(self):
        for key in ('numtests', 'current'):
            text = re.sub(rf'INSTRUMENTATION_STATUS: {key}=\d+', f'INSTRUMENTATION_STATUS: {key}=999', transcript(), count=1)
            self.assertFalse(parse_instrumentation(text, 0).successful)

    def test_crashed_or_truncated_instrumentation_and_missing_summary_fail(self):
        for text in ('INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0',
                     transcript().replace('INSTRUMENTATION_CODE: -1', ''),
                     re.sub(r'OK \(\d+ tests\)', '', transcript()), transcript() + '\nINSTRUMENTATION_CODE: -1'):
            self.assertFalse(parse_instrumentation(text, 0).successful)

    def test_adb_failure_overrides_a_complete_success_stream(self):
        self.assertFalse(parse_instrumentation(transcript(), 1).successful)

    def test_reports_never_copy_raw_stack_or_user_content(self):
        secret = 'private-draft-never-exported'
        result = parse_instrumentation('INSTRUMENTATION_RESULT: shortMsg=' + secret, 0)
        self.assertNotIn(secret, json.dumps(result.summary()) + junit_report(result))

    def test_junit_preserves_passed_cases_when_another_test_fails(self):
        import xml.etree.ElementTree as ET
        text = transcript().replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -2', 1)
        text = re.sub(r'OK \(\d+ tests\)', 'FAILURES!!!', text)
        report = ET.fromstring(junit_report(parse_instrumentation(text, 0)))
        self.assertEqual('1', report.get('failures'))
        self.assertEqual('0', report.get('errors'))

    def test_selected_manifest_matches_all_no_model_chat_methods_and_excludes_real_model(self):
        root = Path(__file__).resolve().parents[2]
        for cls, expected in SELECTED.items():
            source = root / 'app/src/androidTest/kotlin/network/orion/app/chat' / (cls.split('.')[-1] + '.kt')
            self.assertEqual(expected, set(re.findall(r'@Test(?:\([^)]*\))?\s+fun (\w+)', source.read_text())))
        self.assertNotIn('OrionChatRealModelTest', ','.join(SELECTED))

    def test_f8_selection_matches_android_sources_and_rejects_missing_cases(self):
        root = Path(__file__).resolve().parents[2] / 'app/src/androidTest/kotlin'
        for cls, methods in F8_SELECTED.items():
            source = root / (cls.replace('.', '/') + '.kt')
            self.assertEqual(methods, set(re.findall(r'@Test(?:\([^)]*\))?\s+fun (\w+)', source.read_text())))
        expected = test_identities(F8_SELECTED)
        self.assertTrue(parse_instrumentation(transcript(expected=expected), 0, expected).successful)
        self.assertFalse(parse_instrumentation(transcript(sorted(expected)[:-1], expected=expected), 0, expected).successful)

    def test_ci_explicitly_builds_hybrid_probe_and_executes_model_free_suite(self):
        workflow = (Path(__file__).resolve().parents[2] / '.github/workflows/ci.yml').read_text()
        self.assertIn('-PorionHybridChatQualification=true', workflow)
        self.assertIn('chat-ui-no-model:', workflow)
        self.assertIn('verify-chat-ui.py', workflow)
        self.assertIn('--suite f8', workflow)
        self.assertIn('--scenario sentinel_delivery', workflow)
        self.assertIn('needs: [android-build-and-check, chat-ui-no-model]', workflow)
        self.assertIn('test "$ORION_UI_RESULT" = success', workflow)
        self.assertNotIn('continue-on-error', workflow)


class ChatUiRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apks = self.root / 'apks'
        self.apks.mkdir()
        self.hashes = {}
        for name in runner.APK_NAMES.values():
            (self.apks / name).write_bytes(('fixture-' + name).encode())
            self.hashes[name] = hashlib.sha256((self.apks / name).read_bytes()).hexdigest()
        self.manifest = ''.join(f'{digest}  {name}\n' for name, digest in self.hashes.items())
        (self.apks / 'SHA256SUMS').write_text(self.manifest)
        self.calls = []

    def test_artifacts_require_exact_names_unique_hashes_and_no_traversal(self):
        self.assertEqual(self.hashes, runner.verify_artifacts(self.apks))
        for text in (self.manifest + self.manifest, self.manifest.replace('app-debug.apk', '../app-debug.apk'), ''):
            (self.apks / 'SHA256SUMS').write_text(text)
            with self.assertRaises(runner.RunnerFailure):
                runner.verify_artifacts(self.apks)

    def test_changed_or_symlinked_apk_is_rejected(self):
        artifact = self.apks / 'app-debug.apk'
        artifact.write_bytes(b'changed')
        with self.assertRaises(runner.RunnerFailure):
            runner.verify_artifacts(self.apks)
        artifact.unlink()
        artifact.symlink_to(self.apks / 'app-debug-androidTest.apk')
        with self.assertRaises(runner.RunnerFailure):
            runner.verify_artifacts(self.apks)

    def fake_adb(self, command, **kwargs):
        self.calls.append(command)
        args = command[3:]
        out = ''
        if args[:3] == ['shell', 'getprop', 'ro.kernel.qemu']: out = '1'
        elif args[:3] == ['shell', 'getprop', 'sys.boot_completed']: out = '1'
        elif args[:3] == ['emu', 'avd', 'name']: out = 'OrionChatUiCI\r\nOK'
        elif args[:2] == ['shell', 'getprop']: out = '35' if args[-1].endswith('sdk') else 'synthetic'
        elif args[:3] == ['shell', 'getconf', 'PAGE_SIZE']: out = '4096'
        elif args[:1] == ['install']: out = 'Performing Streamed Install\nSuccess'
        elif args[:3] == ['shell', 'pm', 'path']: out = 'package:/data/app/~~synthetic/' + args[-1] + '/base.apk'
        elif args[:2] == ['shell', 'sha256sum']:
            package = args[-1].split('/')[-2]; out = self.hashes[runner.APK_NAMES[package]] + '  base.apk'
        elif args[:3] == ['shell', 'am', 'instrument']: out = transcript()
        elif args[:1] == ['uninstall']: out = 'Success'
        return subprocess.CompletedProcess(command, 0, out, '')

    def execute(self):
        return runner.run(Path('/fixture/adb'), 'emulator-5554', 'OrionChatUiCI', self.apks, self.root / 'report')

    def test_success_verifies_installed_bytes_and_cleans_both_owned_packages(self):
        with patch.object(subprocess, 'run', side_effect=self.fake_adb), patch('builtins.print'):
            self.assertEqual(0, self.execute())
        self.assertTrue(all('-R' in call and '-r' not in call for call in self.calls if call[3:4] == ['install']))
        self.assertEqual(2, sum(call[3:4] == ['uninstall'] for call in self.calls))
        instrument = next(call for call in self.calls if call[3:6] == ['shell', 'am', 'instrument'])
        self.assertIn(','.join(SELECTED), instrument)
        self.assertNotIn('GGUF', str(instrument))
        self.assertEqual('PASS', json.loads((self.root / 'report/summary.json').read_text())['result'])

    def test_timeout_still_cleans_only_installed_owned_packages(self):
        def timeout(command, **kwargs):
            if command[3:6] == ['shell', 'am', 'instrument']:
                raise subprocess.TimeoutExpired(command, 300)
            return self.fake_adb(command, **kwargs)
        with patch.object(subprocess, 'run', side_effect=timeout), patch('builtins.print'):
            self.assertEqual(1, self.execute())
        self.assertEqual(2, sum(call[3:4] == ['uninstall'] for call in self.calls))
        self.assertIn('RUNNER_TIMEOUT', json.loads((self.root / 'report/summary.json').read_text())['errors'])

    def test_non_owned_emulator_cannot_install_or_clean_packages(self):
        def other_owner(command, **kwargs):
            if command[3:6] == ['emu', 'avd', 'name']:
                return subprocess.CompletedProcess(command, 0, 'AnotherProject\nOK', '')
            return self.fake_adb(command, **kwargs)
        with patch.object(subprocess, 'run', side_effect=other_owner), patch('builtins.print'):
            self.assertEqual(1, self.execute())
        self.assertFalse(any(c[3:4] in (['install'], ['uninstall']) for c in self.calls))

    def test_preexisting_app_or_test_package_refuses_before_mutating_emulator(self):
        for package in runner.APK_NAMES:
            with self.subTest(package=package):
                self.calls.clear()

                def preexisting(command, **kwargs):
                    if command[3:7] == ['shell', 'pm', 'list', 'packages']:
                        self.calls.append(command)
                        return subprocess.CompletedProcess(command, 0, 'package:' + package, '')
                    return self.fake_adb(command, **kwargs)

                with patch.object(subprocess, 'run', side_effect=preexisting), patch('builtins.print'):
                    self.assertEqual(1, self.execute())
                summary = json.loads((self.root / 'report/summary.json').read_text())
                self.assertEqual(['PREEXISTING_PACKAGE'], summary['errors'])
                self.assertEqual(0, summary['started'])
                self.assertFalse(any(c[3:4] in (['install'], ['uninstall']) or c[3:5] in
                    (['shell', 'settings'], ['shell', 'am'], ['shell', 'input'], ['shell', 'wm']) for c in self.calls))

    def test_malformed_package_inventory_refuses_install_and_cleanup(self):
        def malformed(command, **kwargs):
            if command[3:7] == ['shell', 'pm', 'list', 'packages']:
                return subprocess.CompletedProcess(command, 0, 'unexpected package inventory', '')
            return self.fake_adb(command, **kwargs)

        with patch.object(subprocess, 'run', side_effect=malformed), patch('builtins.print'):
            self.assertEqual(1, self.execute())
        summary = json.loads((self.root / 'report/summary.json').read_text())
        self.assertEqual(['INVALID_PACKAGE_INVENTORY'], summary['errors'])
        self.assertFalse(any(c[3:4] in (['install'], ['uninstall']) for c in self.calls))

    def test_partial_install_failure_cleans_only_the_package_installed_by_this_run(self):
        def denied(command, **kwargs):
            if command[3:4] == ['install'] and command[-1].endswith('androidTest.apk'):
                return subprocess.CompletedProcess(command, 1, '', 'private fixture install failure')
            return self.fake_adb(command, **kwargs)
        with patch.object(subprocess, 'run', side_effect=denied), patch('builtins.print'):
            self.assertEqual(1, self.execute())
        self.assertEqual([['uninstall', 'network.orion']], [c[3:] for c in self.calls if c[3:4] == ['uninstall']])
        self.assertNotIn('private fixture', (self.root / 'report/summary.json').read_text())

    def test_failed_cleanup_keeps_the_job_failed_even_when_all_tests_pass(self):
        def cleanup_failed(command, **kwargs):
            if command[3:4] == ['uninstall']:
                return subprocess.CompletedProcess(command, 1, '', 'private fixture cleanup failure')
            return self.fake_adb(command, **kwargs)
        with patch.object(subprocess, 'run', side_effect=cleanup_failed), patch('builtins.print'):
            self.assertEqual(1, self.execute())
        summary = json.loads((self.root / 'report/summary.json').read_text())
        self.assertEqual(len(EXPECTED), summary['passed'])
        self.assertIn('CLEANUP_FAILED', summary['errors'])
        self.assertEqual('FAIL', summary['result'])

    def test_missing_installed_hash_is_infrastructure_failure_before_test_execution(self):
        def no_hash(command, **kwargs):
            if command[3:5] == ['shell', 'sha256sum']:
                return subprocess.CompletedProcess(command, 0, '', '')
            return self.fake_adb(command, **kwargs)
        with patch.object(subprocess, 'run', side_effect=no_hash), patch('builtins.print'):
            self.assertEqual(1, self.execute())
        summary = json.loads((self.root / 'report/summary.json').read_text())
        self.assertIn('INSTALLED_HASH_MISMATCH', summary['errors'])
        self.assertFalse(any(c[3:6] == ['shell', 'am', 'instrument'] for c in self.calls))


if __name__ == '__main__':
    unittest.main()
