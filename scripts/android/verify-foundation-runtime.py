#!/usr/bin/env python3
"""Host-only F1 Android checks. Build APKs in Docker before invoking this script."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--environment', required=True, choices=['ANDROID_EMULATOR', 'PHYSICAL_DEVICE'])
    parser.add_argument('--adb', default=shutil.which('adb'))
    parser.add_argument('--evidence-dir', type=Path, required=True)
    args = parser.parse_args()
    if not args.adb:
        parser.error('Pass the host ADB executable with --adb.')
    root = Path(__file__).resolve().parents[2]
    artifacts = [root / 'app/build/outputs/apk/debug/app-debug.apk',
                 root / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    if not all(path.is_file() for path in artifacts):
        parser.error('Build :app:assembleDebug :app:assembleDebugAndroidTest in Docker first.')
    args.evidence_dir.mkdir(parents=True, exist_ok=True)

    def adb(*command, check=True):
        result = subprocess.run([args.adb, '-s', args.serial, *command], capture_output=True,
                                text=True, timeout=180, check=check)
        return result.stdout.strip()

    emulated = adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
    if emulated != (args.environment == 'ANDROID_EMULATOR'):
        parser.error('Declared environment does not match device ro.kernel.qemu.')
    report = {
        'environment': args.environment,
        'androidRuntimeEvidence': 'NON_DEFINITIVE' if emulated else 'DEFINITIVE',
        'apiLevel': adb('shell', 'getprop', 'ro.build.version.sdk'),
        'androidBuild': adb('shell', 'getprop', 'ro.build.fingerprint'),
        'pageSize': adb('shell', 'getconf', 'PAGE_SIZE'),
        'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
        'workingTreeDirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=root, text=True)),
        'artifactSha256': {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in artifacts},
        'checks': {},
    }

    def instrument(name, runtime_only=False):
        command = ['shell', 'am', 'instrument', '-w']
        if runtime_only:
            command += ['-e', 'class', 'network.orion.app.FoundationRuntimeTest']
        output = adb(*command, 'network.orion.test/androidx.test.runner.AndroidJUnitRunner')
        (args.evidence_dir / (name + '.txt')).write_text(output + '\n')
        match = re.search(r'OK \((\d+) tests?\)', output)
        if not match or 'FAILURES!!!' in output or 'INSTRUMENTATION_FAILED' in output:
            raise RuntimeError('Instrumentation failed: ' + name)
        report['checks'][name] = {'result': 'PASS', 'tests': int(match.group(1))}

    def launch():
        adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN',
            '-c', 'android.intent.category.LAUNCHER', '-n', 'network.orion/network.orion.app.MainActivity')

    def pid():
        return adb('shell', 'pidof', 'network.orion', check=False)

    def nodes():
        location = '/sdcard/orion_f1_ui.xml'
        adb('shell', 'uiautomator', 'dump', location)
        try:
            return list(ET.fromstring(adb('shell', 'cat', location)).iter('node'))
        finally:
            adb('shell', 'rm', '-f', location)

    def find_text(text):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            for node in nodes():
                if node.get('text') == text:
                    return node
            time.sleep(0.5)
        raise RuntimeError('Expected UI text was not found: ' + text)

    try:
        for artifact in artifacts:
            adb('install', '-r', '-t', str(artifact))
        instrument('initial_instrumentation')
        launch()
        node = find_text('Diagnóstico')
        bounds = [int(value) for value in re.findall(r'\d+', node.get('bounds', ''))]
        if len(bounds) != 4:
            raise RuntimeError('Navigation node has no valid bounds.')
        adb('shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
        find_text('Contrato IPC: 1.0')
        before = pid()
        if not before:
            raise RuntimeError('Main process is absent before recreation test.')
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        time.sleep(1)
        adb('shell', 'am', 'kill', 'network.orion')
        deadline = time.monotonic() + 15
        while pid() and time.monotonic() < deadline:
            time.sleep(0.5)
        if pid():
            raise RuntimeError('Android did not kill the background process.')
        launch()
        after = pid()
        if not after or before == after:
            raise RuntimeError('Main process was not recreated.')
        find_text('Contrato IPC: 1.0')
        report['checks']['process_recreation_and_navigation'] = {'result': 'PASS', 'beforePid': before, 'afterPid': after}
        instrument('after_process_recreation', runtime_only=True)
        adb('shell', 'am', 'force-stop', 'network.orion')
        time.sleep(1)
        if pid():
            raise RuntimeError('Package unexpectedly running after Force Stop.')
        launch()
        instrument('after_allowed_force_stop_return', runtime_only=True)
        report['result'] = 'PASS'
    except Exception as error:
        report['result'] = 'FAIL'
        report['failureType'] = type(error).__name__
        raise
    finally:
        (args.evidence_dir / 'runtime-report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
