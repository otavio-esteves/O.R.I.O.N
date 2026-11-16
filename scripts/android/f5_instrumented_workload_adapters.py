"""Concrete fixed-command HOST adapters for the qualification-only v2 instrumentation.

No ADB command/profile/template is accepted from a plan. Local timeout never kills
an Android process or proves that its Core invocation terminated.
"""
import hashlib
import fcntl
import importlib.util
import os
from pathlib import Path
import re
import selectors
import stat
import subprocess
import threading
import uuid
from typing import NamedTuple

import f5_instrumented_workload_protocol as P
from qualification_host_clock import HostClock

PACKAGE = 'network.orion.qualification'
TEST_PACKAGE = PACKAGE + '.test'
RUNNER = TEST_PACKAGE + '/network.orion.app.compatibility.OrionCortexInstrumentedWorkloadInstrumentation'
CHECKPOINT = 'orionWorkloadCheckpoint'
MAX_OUTPUT = 256 * 1024


class RuntimeArtifacts(NamedTuple):
    runtime_json: bytes | None
    status_json: bytes
    report_json: bytes


class TargetLock:
    """Cooperative HOST exclusion; never an Android lease or proof of remote termination."""
    def __init__(self, identity_sha256):
        P.digest(identity_sha256)
        path = '/tmp/orion-workload-target-'+str(os.getuid())+'-'+identity_sha256+'.lock'
        self.fd = os.open(path,os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW|os.O_NONBLOCK,0o600)
        try:
            meta = os.fstat(self.fd)
            P.require(stat.S_ISREG(meta.st_mode) and meta.st_nlink == 1 and meta.st_uid == os.getuid() and
                      stat.S_IMODE(meta.st_mode) == 0o600, 'TARGET_LOCK_INVALID')
            fcntl.flock(self.fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BaseException:
            os.close(self.fd); self.fd = None; raise
    def close(self):
        if self.fd is not None: os.close(self.fd); self.fd = None


def _module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result


PREFLIGHT = _module('orion_workload_preflight', 'prepare-f5-physical-qualification.py')


def instrumentation_command(operation, run_id, fixture_id, session_id=None, attempt_id=None):
    P.require(operation in {'OPEN', 'BOOTSTRAP', 'WORK', 'OBSERVE', 'CLEANUP'}, 'OPERATION_INVALID')
    P.identifier(run_id); P.identifier(fixture_id)
    if operation != 'OPEN': P.identifier(session_id)
    else: P.require(session_id is None)
    if operation in {'WORK','BOOTSTRAP'}: P.identifier(attempt_id)
    else: P.require(attempt_id is None)
    args = ['shell', 'am', 'instrument', '--no-restart', '-w', '-r', '-e', 'orionOperation', operation,
            '-e', 'orionRunId', run_id, '-e', 'orionFixtureId', fixture_id]
    if session_id is not None: args += ['-e', 'orionSessionId', session_id]
    if attempt_id is not None: args += ['-e', 'orionAttemptId', attempt_id]
    return tuple(args + [RUNNER])


def allowed_command(command):
    if command in {('get-state',), ('shell', 'getconf', 'PAGE_SIZE'), ('shell', 'pidof', PACKAGE),
                   ('shell', 'settings', 'get', 'global', 'boot_count'), ('shell','am','help')}:
        return True
    if len(command) == 3 and command[:2] == ('shell', 'getprop'):
        return command[2] in {'ro.kernel.qemu', 'ro.boot.qemu', 'ro.build.fingerprint', 'ro.hardware',
                              'ro.build.version.sdk', 'ro.product.cpu.abi', 'ro.product.manufacturer', 'ro.product.model', 'ro.serialno'}
    if len(command) == 3 and command[:2] == ('shell', 'cat'):
        match = re.fullmatch(r'/proc/([0-9]{1,10})/stat',command[2])
        return command[2] in {'/proc/uptime', '/proc/sys/kernel/random/boot_id','/proc/meminfo'} or \
               match is not None and 1 <= int(match[1]) <= 2**31-1
    if len(command) == 4 and command[:3] == ('shell', 'pm', 'path'):
        return command[3] in {PACKAGE, TEST_PACKAGE}
    if len(command) == 3 and command[:2] == ('shell', 'sha256sum'):
        return re.fullmatch(r'/data/app/[A-Za-z0-9_./=+~\-]+/base\.apk', command[2]) is not None and '..' not in Path(command[2]).parts
    if len(command) > 7 and command[:6] == ('shell', 'am', 'instrument', '--no-restart', '-w', '-r'):
        tail = command[6:-1]
        if command[-1] != RUNNER or len(tail) % 3: return False
        values = {}
        for i in range(0, len(tail), 3):
            if tail[i] != '-e' or tail[i+1] in values: return False
            values[tail[i+1]] = tail[i+2]
        try:
            actual = instrumentation_command(values['orionOperation'], values['orionRunId'], values['orionFixtureId'],
                                             values.get('orionSessionId'), values.get('orionAttemptId'))
            return actual == command
        except (KeyError, ValueError): return False
    return False


class TransportUnknown(RuntimeError):
    def __init__(self): super().__init__('TRANSPORT_UNAVAILABLE_OR_UNKNOWN')


class FixedAdb:
    def __init__(self, executable, serial, transport_timeout_millis):
        P.require(type(serial) is str and re.fullmatch(r'[A-Za-z0-9._:-]{1,128}', serial) is not None and
                  not serial.startswith('emulator-'), 'EXPLICIT_PHYSICAL_SERIAL_REQUIRED')
        P.integer(transport_timeout_millis, 1, 180000)
        self._prefix = [str(executable), '-s', serial]
        self.serial = serial
        self._timeout = transport_timeout_millis
        self._instrument = threading.Lock()
        self._uncertain = False

    @property
    def uncertain(self): return self._uncertain

    def __call__(self, *command):
        command = tuple(command); P.require(allowed_command(command), 'ADB_COMMAND_NOT_ALLOWED')
        effect = command[:3] == ('shell', 'am', 'instrument')
        if effect:
            P.require(not self._uncertain and self._instrument.acquire(blocking=False), 'INSTRUMENTATION_BUSY_OR_UNKNOWN')
        process = selector = None
        original = cleanup_error = result = None
        try:
            selector = selectors.DefaultSelector()
            clock = HostClock.linux(); start = clock.read_ns()
            deadline = start + (self._timeout if effect else min(self._timeout, 10000)) * 1000000
            data = bytearray()
            process = subprocess.Popen(self._prefix + list(command), stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            os.set_blocking(process.stdout.fileno(), False); selector.register(process.stdout, selectors.EVENT_READ)
            eof = False
            while not eof or process.poll() is None:
                now = clock.read_ns()
                if now >= deadline: raise TransportUnknown()
                ready = selector.select(min(0.1, (deadline-now)/1000000000))
                if ready and not eof:
                    try: chunk = os.read(process.stdout.fileno(), min(16384, MAX_OUTPUT + 1 - len(data)))
                    except BlockingIOError: continue
                    if not chunk: eof = True; selector.unregister(process.stdout)
                    else:
                        if len(data) + len(chunk) > MAX_OUTPUT: raise TransportUnknown()
                        data.extend(chunk)
            if process.returncode != 0: raise TransportUnknown()
            result = data.decode('utf-8', errors='strict').strip()
        except BaseException as error:
            original = error
            if effect: self._uncertain = True
        finally:
            def clean(action):
                nonlocal cleanup_error
                try: action()
                except BaseException as error:
                    if cleanup_error is None: cleanup_error = error
            if selector is not None: clean(selector.close)
            if process is not None:
                # Only the local client, never Android/ADB server/process group. Every cleanup is independent.
                def stop_client():
                    try:
                        if process.poll() is None: process.kill()
                    except ProcessLookupError: pass
                clean(stop_client)
                clean(lambda:process.wait(timeout=1))
                if process.stdout is not None: clean(process.stdout.close)
            if effect:
                if cleanup_error is not None: self._uncertain = True
                clean(self._instrument.release)
                if cleanup_error is not None: self._uncertain = True
        if original is not None: raise original
        if cleanup_error is not None: raise cleanup_error
        return result


def receipt(output, operation, run_id, fixture_id, session_id=None, attempt_id=None):
    """Exact one closed terminal Bundle. No test status, raw errors or surplus output accepted."""
    P.require(type(output) is str and 0 < len(output.encode('utf-8')) <= MAX_OUTPUT, 'OUTPUT_BOUND_INVALID')
    lines = output.splitlines(); results = {}; code = None
    for line in lines:
        if line.startswith('INSTRUMENTATION_RESULT: '):
            P.require(code is None, 'RESULT_AFTER_TERMINAL')
            key, sep, value = line[24:].partition('=')
            P.require(sep and key not in results, 'RESULT_FRAMING_INVALID'); results[key] = value
        elif line.startswith('INSTRUMENTATION_CODE: '):
            P.require(code is None and line == 'INSTRUMENTATION_CODE: -1', 'RESULT_FRAMING_INVALID'); code = -1
        else: P.require(line == '', 'SURPLUS_OUTPUT')
    P.require(code == -1 and set(results) in ({CHECKPOINT, 'stream'}, {CHECKPOINT,'stream','orionRuntimeCaptureStatus'},
                  {CHECKPOINT, 'stream', 'orionRuntimeCaptureSource', 'orionRuntimeCaptureStatus'}),
              'RESULT_FRAMING_INVALID')
    P.require(results['stream'] == 'ORION_WORKLOAD_CHECKPOINT', 'RESULT_FRAMING_INVALID')
    value = P.parse(results[CHECKPOINT].encode('utf-8'), 16384)
    P.checkpoint(value, operation, run_id, fixture_id, attempt_id)
    if session_id is not None: P.require(value['sessionId'] == session_id, 'OWNER_CHANGED')
    source = results.get('orionRuntimeCaptureSource')
    P.require((source is not None) == (value['runtimeSourceSha256'] is not None), 'SOURCE_REFERENCE_INVALID')
    if source is not None:
        P.require(source.isascii() and len(source) <= 96*1024, 'SOURCE_BOUND_INVALID')
        P.require(P.sha(source.encode('ascii')) == value['runtimeSourceSha256'], 'SOURCE_REFERENCE_INVALID')
    return value, source, results.get('orionRuntimeCaptureStatus')


class BoundDevice:
    """Actual source/APK/device pins. Revalidation never installs, starts or replaces a process."""
    def __init__(self, adb, plan, target, source_root, source_manifest, app_apk, test_apk):
        self.adb = adb; self.plan = plan; self.target = target
        self.source_root, self.source_manifest = Path(source_root), Path(source_manifest)
        self.apks = {'app': Path(app_apk), 'test': Path(test_apk)}
        self._identity = None; self._stamp = None
        self._stamp_lock = threading.Lock()
        self.revalidate(full=True)
        help_text = self.adb('shell','am','help')
        P.require(help_text.isascii() and len(help_text) <= 128*1024 and
                  re.search(r'(?m)^\s+--no-restart\b',help_text) is not None, 'NO_RESTART_UNSUPPORTED')

    def revalidate(self, full=False):
        source, artifacts = PREFLIGHT.source_identity(self.source_root, self.source_manifest, required_artifacts=frozenset({'app','test'}))
        expected = self.plan['identity']
        P.require(source['commit'] == expected['sourceCommit'] and source['manifestSha256'] == expected['sourceManifestSha256'], 'SOURCE_CHANGED')
        for key, package in [('app', PACKAGE), ('test', TEST_PACKAGE)]:
            digest = PREFLIGHT.sha_file(self.apks[key], 1024**3)
            P.require(digest == artifacts[key] == expected[key+'Sha256'] and PREFLIGHT.installed_hash(self.adb, package) == digest, 'APK_CHANGED')
        actual = PREFLIGHT.physical_identity(self.adb, self.adb.serial, self.target)
        selected = {k:actual[v] for k,v in [('deviceIdentitySha256','deviceIdentitySha256'), ('buildFingerprintSha256','fingerprintSha256'),
                                           ('apiLevel','api'), ('abi','abi'), ('pageSizeBytes','pageSizeBytes')]}
        P.require(selected == self.plan['target'], 'TARGET_CHANGED')
        if self._identity is None: self._identity = actual
        else: P.require(actual == self._identity, 'TARGET_CHANGED')
        return self.stamp()

    def stamp(self):
        with self._stamp_lock:
            return self._read_stamp()

    def _read_stamp(self):
        first = self.adb('shell','cat','/proc/sys/kernel/random/boot_id')
        P.identifier(first)
        boot = self.adb('shell','settings','get','global','boot_count')
        pid = self.adb('shell','pidof',PACKAGE)
        uptime = self.adb('shell','cat','/proc/uptime')
        P.require(re.fullmatch(r'[0-9]{1,10}',boot) is not None and re.fullmatch(r'[0-9]{1,10}',pid) is not None, 'OWNER_UNAVAILABLE')
        match = re.fullmatch(r'([0-9]{1,13})\.([0-9]{1,3}) [0-9]{1,15}\.[0-9]{1,3}',uptime)
        P.require(match is not None and first == self.adb('shell','cat','/proc/sys/kernel/random/boot_id'), 'BOOT_CHANGED')
        lower = int(match[1])*1000+int(match[2].ljust(3,'0'))
        value = dict(bootCount=int(boot),deviceElapsedMillis=lower,
                     deviceElapsedUpperMillis=lower+10**(3-len(match[2]))-1,mainPid=int(pid))
        P.stamp(value)
        stat_text = self.adb('shell','cat','/proc/'+pid+'/stat')
        stat_match = re.fullmatch(r'([0-9]+) \([^\n]*\) (.+)',stat_text)
        P.require(stat_match is not None and stat_match[1] == pid, 'MAIN_INSTANCE_UNAVAILABLE')
        fields = stat_match[2].split()
        P.require(len(fields) >= 20 and re.fullmatch(r'[0-9]{1,20}',fields[19]) is not None, 'MAIN_INSTANCE_UNAVAILABLE')
        start_ticks = int(fields[19]); P.integer(start_ticks,1)
        if self._stamp is None: self._stamp = (first,dict(value),start_ticks)
        else:
            old_boot, old, old_start = self._stamp
            P.require(first == old_boot and value['bootCount'] == old['bootCount'] and value['mainPid'] == old['mainPid'] and
                      value['deviceElapsedMillis'] >= old['deviceElapsedMillis'] and start_ticks == old_start, 'OWNER_CHANGED')
            self._stamp = (first,dict(value),start_ticks)
        return value

    def total_memory(self):
        text = self.adb('shell','cat','/proc/meminfo')
        values = re.findall(r'(?m)^MemTotal:\s+([0-9]{1,12}) kB$',text)
        P.require(len(values) == 1, 'TOTAL_MEMORY_UNAVAILABLE')
        total = int(values[0])*1024
        P.integer(total,64*1024**2,1024**4)
        return total


class InstrumentedProducer:
    def __init__(self, device): self.device = device; self.session = None
    def stamp(self): return self.device.stamp()
    def revalidate(self): return self.device.revalidate()
    def call(self, operation, attempt_id=None):
        plan = self.device.plan
        before = self.device.revalidate()
        total = self.device.total_memory()
        args = instrumentation_command(operation, plan['runId'], plan['ownedFixtureId'], self.session, attempt_id)
        output = self.device.adb(*args)
        value, source, status = receipt(output, operation, plan['runId'], plan['ownedFixtureId'], self.session, attempt_id)
        after_window = self.device.stamp()
        P.require(self.device.total_memory() == total, 'TOTAL_MEMORY_CHANGED')
        after = self.device.revalidate()
        P.require(value['stamp']['bootCount'] == before['bootCount'] == after['bootCount'] and
                  value['stamp']['mainPid'] == before['mainPid'] == after['mainPid'] and
                  before['deviceElapsedMillis'] <= value['stamp']['deviceElapsedMillis'] <= after['deviceElapsedUpperMillis'], 'OWNER_CHANGED')
        P.require(value['stamp']['deviceElapsedUpperMillis'] == value['stamp']['deviceElapsedMillis'], 'MAIN_CLOCK_NOT_EXACT')
        no_selection = value['outcome'] == 'REFUSED' and value['ownerCallInvoked'] is False and \
                       value['modelSha256'] is None and value['configurationSha256'] is None and source is None
        P.require(no_selection or value['modelSha256'] == plan['identity']['modelSha256'] and
                  value['configurationSha256'] == plan['identity']['configurationSha256'], 'CONFIGURATION_CHANGED')
        artifacts = None
        if status is not None:
            # Public #158 seam validates the closed SOURCE and the independently bound actual HOST window.
            import f5_runtime_source_assembler as assembler
            identity = plan['identity']
            expected = None if identity['modelSha256'] is None else dict(sourceCommit=identity['sourceCommit'],
                sourceManifestSha256=identity['sourceManifestSha256'],appPackage=PACKAGE,testPackage=TEST_PACKAGE,
                appApkSha256=identity['appSha256'],testApkSha256=identity['testSha256'],
                modelSha256=identity['modelSha256'],modelSizeBytes=identity['modelSizeBytes'])
            host = dict(declaredEnvironment='PHYSICAL_DEVICE',bootCount=before['bootCount'],
                bootIdSha256=P.sha(self.device._stamp[0].encode('ascii')),startedElapsed=before['deviceElapsedMillis'],
                completedElapsed=after_window['deviceElapsedUpperMillis'],api=plan['target']['apiLevel'],
                abi=plan['target']['abi'],pageSizeBytes=plan['target']['pageSizeBytes'],
                deviceBuildSha256=plan['target']['buildFingerprintSha256'],totalMemoryBytes=total)
            assembled = assembler.assemble_json(source.encode('ascii') if source is not None else None,
                status.encode('ascii'),P.encoded(expected) if expected is not None else None,P.encoded(host))
            header = P.parse(assembled.status_json,1024)
            P.require(header['ownerCallInvoked'] is None or value['ownerCallInvoked'] is None or
                      header['ownerCallInvoked'] == value['ownerCallInvoked'], 'OWNER_CALL_MISMATCH')
            artifacts = RuntimeArtifacts(assembled.runtime_json,assembled.status_json,assembled.report_json)
        if operation == 'OPEN': self.session = value['sessionId']
        return value, artifacts
