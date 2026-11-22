#!/usr/bin/env python3
"""HOST-only, no-model UI runner. Builds stay in Docker; never downloads a GGUF."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

from android_apk_pair import APK_NAMES, RunnerFailure, verify_artifacts
from chat_ui_protocol import (
    F8_SELECTED, F9_SELECTED, F10_SELECTED, Result, SELECTED, junit_report, parse_instrumentation, test_identities,
)

SUITES = {'chat': SELECTED, 'f8': F8_SELECTED, 'f9': F9_SELECTED, 'f10': F10_SELECTED}


def run(adb_path, serial, avd_name, apk_dir, report_dir, suite="chat"):
    report_dir.mkdir(parents=True, exist_ok=True)
    selected = SUITES[suite]
    expected = test_identities(selected)
    result = Result(expected=expected)
    metadata = {'environment': 'ANDROID_EMULATOR', 'definitiveHardwareEvidence': False}
    installed = []

    def adb(*args, timeout=30):
        response = subprocess.run([str(adb_path), '-s', serial, *args], capture_output=True, text=True, timeout=timeout)
        if response.returncode != 0:
            raise RunnerFailure('ADB_COMMAND_FAILED')
        return response.stdout.strip()

    try:
        if not re.fullmatch(r'emulator-[0-9]{4,5}', serial) or not re.fullmatch(r'[A-Za-z0-9_-]{1,96}', avd_name):
            raise RunnerFailure('INVALID_EMULATOR_SELECTION')
        hashes = verify_artifacts(apk_dir)
        metadata['apkHashes'] = hashes
        if adb('shell', 'getprop', 'ro.kernel.qemu') != '1' or adb('emu', 'avd', 'name').splitlines() != [avd_name, 'OK']:
            raise RunnerFailure('EMULATOR_OWNERSHIP_MISMATCH')
        if adb('shell', 'getprop', 'sys.boot_completed') != '1':
            raise RunnerFailure('EMULATOR_NOT_BOOTED')
        packages = adb('shell', 'pm', 'list', 'packages', '-u', 'network.orion').splitlines()
        if any(not re.fullmatch(r'package:[A-Za-z0-9_.]+', line) for line in packages):
            raise RunnerFailure('INVALID_PACKAGE_INVENTORY')
        if any(f'package:{package}' in packages for package in APK_NAMES):
            raise RunnerFailure('PREEXISTING_PACKAGE')
        for prop, key in [('ro.build.version.sdk', 'api'), ('ro.product.cpu.abi', 'abi'), ('ro.build.fingerprint', 'buildFingerprint')]:
            value = adb('shell', 'getprop', prop)
            if not re.fullmatch(r'[A-Za-z0-9_./:;,+\-]{1,255}', value):
                raise RunnerFailure('INVALID_RUNTIME_METADATA')
            metadata[key] = value
        metadata['pageSize'] = int(adb('shell', 'getconf', 'PAGE_SIZE'))
        for setting in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
            adb('shell', 'settings', 'put', 'global', setting, '0')
        adb('shell', 'settings', 'put', 'system', 'screen_off_timeout', '2147483647')
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        adb('shell', 'wm', 'dismiss-keyguard')
        for package, filename in APK_NAMES.items():
            # Package Manager -R refuses replacement even if a package appears after preflight.
            if adb('install', '--no-incremental', '-R', '-t', str(apk_dir / filename), timeout=90).splitlines()[-1:] != ['Success']:
                raise RunnerFailure('INSTALL_FAILED')
            installed.append(package)
            location = adb('shell', 'pm', 'path', package)
            if not re.fullmatch(r'package:/data/app/[A-Za-z0-9_./=+~\-]+/base\.apk', location):
                raise RunnerFailure('INSTALLED_PACKAGE_MISMATCH')
            proof = adb('shell', 'sha256sum', location.removeprefix('package:')).split()
            if len(proof) != 2 or proof[0] != hashes[filename]:
                raise RunnerFailure('INSTALLED_HASH_MISMATCH')
        if suite == "f8":
            adb('shell', 'pm', 'grant', 'network.orion', 'android.permission.POST_NOTIFICATIONS')
        response = subprocess.run([str(adb_path), '-s', serial, 'shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', ','.join(selected), 'network.orion.test/androidx.test.runner.AndroidJUnitRunner'],
            capture_output=True, text=True, timeout=300)
        result = parse_instrumentation(response.stdout + '\n' + response.stderr, response.returncode, expected)
    except RunnerFailure as failure:
        result.errors.append(str(failure))  # Fixed technical codes only.
    except subprocess.TimeoutExpired:
        result.errors.append('RUNNER_TIMEOUT')
    except (OSError, ValueError, UnicodeError):
        result.errors.append('RUNNER_INFRASTRUCTURE_FAILURE')
    finally:
        for package in reversed(installed):
            try:
                adb('shell', 'am', 'force-stop', package)
                if adb('uninstall', package, timeout=60) != 'Success':
                    raise RunnerFailure('UNINSTALL_FAILED')
            except (RunnerFailure, subprocess.TimeoutExpired, OSError):
                result.errors.append('CLEANUP_FAILED')
        summary = metadata | result.summary()
        (report_dir / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
        (report_dir / 'junit.xml').write_text(junit_report(result))
    # Raw instrumentation output and exception text may include drafts: never print/export them.
    print(json.dumps(result.summary(), sort_keys=True))
    return 0 if result.successful else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True, type=Path)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd-name', required=True, help='Explicit task-owned AVD, never an arbitrary connected device')
    parser.add_argument('--apk-dir', required=True, type=Path)
    parser.add_argument('--report-dir', required=True, type=Path)
    parser.add_argument('--suite', choices=tuple(SUITES), default='chat')
    args = parser.parse_args()
    return run(args.adb, args.serial, args.avd_name, args.apk_dir, args.report_dir, args.suite)


if __name__ == '__main__':
    sys.exit(main())
