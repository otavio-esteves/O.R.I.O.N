#!/usr/bin/env python3
"""One fixed qualification capture on an allocated target. Always NOT_QUALIFIED; no replay."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import selectors
import stat
import subprocess

import f5_runtime_source_assembler as ASM


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


SINGLE = module("orion_runtime_readonly_helpers", "record-f5-single-attempt.py")
BASE, CLOCK = SINGLE.BASE, SINGLE.CLOCK
PREFLIGHT = BASE.PREFLIGHT
Rejected, require = BASE.Rejected, BASE.require
PACKAGES = BASE.PACKAGES
FLAGS = ASM.APPROVAL_FLAGS
RUNNER = PACKAGES["test"] + "/network.orion.app.compatibility.OrionCortexRuntimeCaptureInstrumentation"
STATUS_KEY, SOURCE_KEY = "orionRuntimeCaptureStatus", "orionRuntimeCaptureSource"
STREAMS = {"ORION_RUNTIME_SOURCE", "ORION_RUNTIME_REFUSED", "ORION_RUNTIME_UNAVAILABLE"}
MAX_OUTPUT = 128 * 1024
TRANSPORT_TIMEOUT_SECONDS = 180  # Defensive HOST transport bound, never request duration/calibration.
BOOT_COUNT_COMMAND = ("shell", "settings", "get", "global", "boot_count")
TARGETS = ("S21", "API37", "API35_EMULATOR", "API37_EMULATOR")
OUTPUT_NAMES = {"capture-report.json", "capture-status.json", "assembly-report.json", "runtime-v1.json"}
FAILURE_CODES = SINGLE.FAILURE_CODES | frozenset({
    "TARGET_SIGNALS_INVALID", "BOOT_COUNT_UNAVAILABLE", "EXPECTED_IDENTITY_MISMATCH", "EXPECTED_IDENTITY_CHANGED",
    "EXPECTED_IDENTITY_REQUIRED", "SOURCE_OR_STATUS_INVALID", "FRAME_INVALID", "FRAME_CODE_MISMATCH",
    "PUBLICATION_UNAVAILABLE", "RUNNER_UNAVAILABLE", "OWNER_INVOCATION_UNAVAILABLE", "INTERRUPTED"})


def instrumentation_command():
    return ("shell", "am", "instrument", "-w", "-r", RUNNER)


def allowed_command(command):
    return command == instrumentation_command() or command == BOOT_COUNT_COMMAND or (
        command[:3] != ("shell", "am", "instrument") and BASE.allowed_command(command))


class CaptureAdb:
    """Closed read queries and one consumed fixed instrumentation command. No Android cleanup."""
    def __init__(self, executable, serial):
        require(type(executable) is str and bool(executable) and type(serial) is str and
                PREFLIGHT.SERIAL.fullmatch(serial), "EXPLICIT_PHYSICAL_SERIAL_REQUIRED")
        self.prefix = [executable, "-s", serial]
        self.issued = False

    def __call__(self, *command):
        require(allowed_command(command), "ADB_COMMAND_NOT_ALLOWED")
        instrument = command == instrumentation_command()
        if instrument:
            require(not self.issued, "SECOND_INVOCATION_FORBIDDEN")
            self.issued = True
        timing = CLOCK.HostClock.linux()  # No fallback and no child before a real suspend-aware source.
        deadline = timing.read_ns() + (TRANSPORT_TIMEOUT_SECONDS if instrument else 10) * CLOCK.NANOS
        process = None
        selector = selectors.DefaultSelector()
        original_error = None
        cleanup_error = None
        result = None
        try:
            data = bytearray()
            require(timing.read_ns() < deadline, "ADB_TRANSPORT_TIMEOUT")
            process = subprocess.Popen(self.prefix + list(command), stdin=subprocess.DEVNULL,
                                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            output = process.stdout
            require(output is not None, "ADB_OBSERVATION_UNAVAILABLE")
            os.set_blocking(output.fileno(), False)
            selector.register(output, selectors.EVENT_READ)
            eof = False
            while True:
                now = timing.read_ns()
                require(now < deadline, "ADB_TRANSPORT_TIMEOUT")
                events = selector.select(min(0.1, (deadline - now) / CLOCK.NANOS))
                if events and not eof:
                    try:
                        block = os.read(output.fileno(), min(16 * 1024, MAX_OUTPUT + 1 - len(data)))
                    except BlockingIOError:
                        continue
                    if block:
                        require(len(data) + len(block) <= MAX_OUTPUT, "ADB_OUTPUT_BOUND_EXCEEDED")
                        data.extend(block)
                    else:
                        eof = True
                        selector.unregister(output)
                if eof and process.poll() is not None:
                    break
            require(process.returncode == 0, "ADB_OBSERVATION_UNAVAILABLE")
            text = data.decode("utf-8", errors="strict")
            result = text if instrument else text.strip()
        except BaseException as error:
            original_error = error
        finally:
            try:
                selector.close()
            except BaseException as error:
                cleanup_error = error
            # Kill only this local HOST client, never the Android program, process group or ADB server.
            if process is not None:
                try:
                    if process.poll() is None:
                        process.kill()
                except ProcessLookupError:
                    pass
                except BaseException as error:
                    if cleanup_error is None:
                        cleanup_error = error
                try:
                    process.wait(timeout=1)
                except BaseException as error:
                    if cleanup_error is None:
                        cleanup_error = error
                if process.stdout is not None:
                    try:
                        process.stdout.close()
                    except BaseException as error:
                        if cleanup_error is None:
                            cleanup_error = error
        if original_error is not None:
            raise original_error
        if cleanup_error is not None:
            raise cleanup_error
        return result


def target_identity(adb, serial, target):
    require(target in TARGETS, "TARGET_INVALID")
    if target in ("S21", "API37"):
        result = BASE.physical_identity(adb, serial, target)
        return dict(result, environment="PHYSICAL_DEVICE")
    require(type(serial) is str and re.fullmatch(r"emulator-[0-9]{4,5}", serial), "TARGET_SIGNALS_INVALID")
    require(adb("get-state") == "device", "DEVICE_OFFLINE_OR_AMBIGUOUS")
    qemu = adb("shell", "getprop", "ro.kernel.qemu")
    boot_qemu = adb("shell", "getprop", "ro.boot.qemu")
    fingerprint = adb("shell", "getprop", "ro.build.fingerprint")
    hardware = adb("shell", "getprop", "ro.hardware")
    require(qemu in ("", "0", "1") and boot_qemu in ("", "0", "1") and "1" in (qemu, boot_qemu) and
            10 <= len(fingerprint) <= 512 and 0 < len(hardware) <= 128 and
            all(32 < ord(c) < 127 for c in fingerprint + hardware) and
            PREFLIGHT.EMULATOR.search(fingerprint + " " + hardware), "TARGET_SIGNALS_INVALID")
    api = adb("shell", "getprop", "ro.build.version.sdk")
    require(api == ("35" if target == "API35_EMULATOR" else "37"), "TARGET_API_MISMATCH")
    abi = adb("shell", "getprop", "ro.product.cpu.abi")
    pages = adb("shell", "getconf", "PAGE_SIZE")
    require(abi in ("x86_64", "arm64-v8a") and pages in ("4096", "16384"), "TARGET_ABI_OR_PAGE_SIZE_UNSUPPORTED")
    digest = hashlib.sha256
    return {"environment": "ANDROID_EMULATOR", "adbSerialSha256": digest(serial.encode()).hexdigest(),
            "fingerprintSha256": digest(fingerprint.encode()).hexdigest(),
            "deviceIdentitySha256": digest((serial + "\0" + fingerprint + "\0" + hardware).encode()).hexdigest(),
            "api": int(api), "abi": abi, "pageSizeBytes": int(pages), "physicalSignals": "EMULATOR_OBSERVED",
            "ramQualification": "NOT_VERIFIED"}


def boot_count(adb):
    text = adb(*BOOT_COUNT_COMMAND)
    require(type(text) is str and re.fullmatch(r"0|[1-9][0-9]{0,9}", text) and int(text) <= 2**31 - 1,
            "BOOT_COUNT_UNAVAILABLE")
    return int(text)


def device_window(adb):
    first = boot_count(adb)
    value = BASE.device_window(adb)
    require(first == boot_count(adb), "BOOT_CHANGED_DURING_OBSERVATION")
    return dict(value, bootCount=first)


def receipts(output):
    """Bounded final frame only. A transcript can never authenticate its producer."""
    require(type(output) is str and output.isascii() and 0 < len(output.encode("ascii")) <= MAX_OUTPUT and
            all(c in "\r\n" or 32 <= ord(c) <= 126 for c in output), "FRAME_INVALID")
    normalized = output.replace("\r\n", "\n")
    require("\r" not in normalized, "FRAME_INVALID")
    values, stream, pending, code = {}, None, False, None
    for line in normalized.split("\n"):
        if not line:
            continue
        require(code is None, "FRAME_INVALID")
        if pending:
            require(line in STREAMS, "FRAME_INVALID")
            stream, pending = line, False
        elif line.startswith("INSTRUMENTATION_RESULT: "):
            name, separator, value = line.removeprefix("INSTRUMENTATION_RESULT: ").partition("=")
            require(separator and name in {STATUS_KEY, SOURCE_KEY, "stream"}, "FRAME_INVALID")
            require(name not in values, "FRAME_INVALID")
            values[name] = value
            if name == "stream":
                require(not value or value in STREAMS, "FRAME_INVALID")
                stream, pending = value or None, not value
        elif line in ("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"):
            require(not pending and stream is not None and STATUS_KEY in values, "FRAME_INVALID")
            code = int(line.rsplit(" ", 1)[1])
        else:
            raise Rejected("FRAME_INVALID")
    require(code is not None and not pending and stream is not None and STATUS_KEY in values, "FRAME_INVALID")
    status = values[STATUS_KEY].encode("ascii")
    source = values[SOURCE_KEY].encode("ascii") if SOURCE_KEY in values else None
    require(0 < len(status) <= ASM.MAX_STATUS_BYTES and
            (source is None or 0 < len(source) <= ASM.MAX_SOURCE_BYTES), "FRAME_INVALID")
    return {"status": status, "source": source, "stream": stream, "code": code}


def validate_frame(frame):
    """Framing/code pairing only. assemble_json subsequently validates the entire closed schema."""
    value = ASM.v1.decode(frame["status"], ASM.MAX_STATUS_BYTES)
    ASM.v1.fields(value, ASM.STATUS_KEYS, "STATUS_FIELDS_INVALID")
    state, owner = value["recordState"], value["ownerCallInvoked"]
    require(owner is None or type(owner) is bool, "FRAME_CODE_MISMATCH")
    require((frame["source"] is not None) == (state == "V1_SOURCE"), "FRAME_CODE_MISMATCH")
    if frame["code"] == 0:
        require(state == "NO_V1_RECORD" and owner is None and frame["stream"] == "ORION_RUNTIME_UNAVAILABLE",
                "FRAME_CODE_MISMATCH")
    elif state == "V1_SOURCE":
        require(owner is True and frame["stream"] == "ORION_RUNTIME_SOURCE", "FRAME_CODE_MISMATCH")
    elif state == "NO_LOAD_REFUSAL":
        require(owner is False and frame["stream"] == "ORION_RUNTIME_REFUSED", "FRAME_CODE_MISMATCH")
    else:
        require(state == "NO_V1_RECORD" and frame["stream"] == "ORION_RUNTIME_UNAVAILABLE", "FRAME_CODE_MISMATCH")
    return value


class _OwnedDirectory:
    """A pinned new inode; no publication or cleanup follows mutable path names."""
    _DIR_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC

    @staticmethod
    def _inode(metadata):
        return metadata.st_dev, metadata.st_ino

    @classmethod
    def _open_directory(cls, path):
        descriptor = os.open("/", cls._DIR_FLAGS)
        try:
            for component in path.parts[1:]:
                selected = os.open(component, cls._DIR_FLAGS, dir_fd=descriptor)
                previous, descriptor = descriptor, selected
                os.close(previous)
            return descriptor
        except BaseException:
            try:
                os.close(descriptor)
            except BaseException:
                pass
            raise

    @classmethod
    def create(cls, path):
        path = Path(os.path.abspath(os.fspath(path)))
        require(bool(path.name), "PUBLICATION_UNAVAILABLE")
        parent = cls._open_directory(path.parent)
        descriptor = None
        try:
            # Parents must already exist. Every component was opened without following symlinks.
            os.mkdir(path.name, mode=0o700, dir_fd=parent)
            created = os.stat(path.name, dir_fd=parent, follow_symlinks=False)
            require(stat.S_ISDIR(created.st_mode) and created.st_uid == os.geteuid() and
                    created.st_mode & 0o777 == 0o700, "PUBLICATION_UNAVAILABLE")
            descriptor = os.open(path.name, cls._DIR_FLAGS, dir_fd=parent)
            require(cls._inode(os.fstat(descriptor)) == cls._inode(created), "PUBLICATION_UNAVAILABLE")
            value = cls(path, descriptor, cls._inode(created))
            descriptor = None
            return value
        finally:
            if descriptor is not None:
                os.close(descriptor)
            os.close(parent)

    def __init__(self, path, descriptor, identity):
        self.path, self.descriptor, self.identity = path, descriptor, identity
        self.files = {}

    def _assert_owned(self):
        require(self.descriptor is not None, "PUBLICATION_UNAVAILABLE")
        metadata = os.fstat(self.descriptor)
        require(self._inode(metadata) == self.identity and stat.S_ISDIR(metadata.st_mode) and
                metadata.st_uid == os.geteuid() and metadata.st_mode & 0o777 == 0o700, "PUBLICATION_UNAVAILABLE")

    def _assert_current(self):
        self._assert_owned()
        selected = self._open_directory(self.path)
        try:
            require(self._inode(os.fstat(selected)) == self.identity, "PUBLICATION_UNAVAILABLE")
        finally:
            os.close(selected)

    def _assert_members(self):
        names = set(os.listdir(self.descriptor))
        require(names == set(self.files), "PUBLICATION_UNAVAILABLE")
        for name in names:
            known = self.files.get(name)
            current = os.stat(name, dir_fd=self.descriptor, follow_symlinks=False)
            require(known is not None and stat.S_ISREG(current.st_mode) and self._inode(current) == known,
                    "PUBLICATION_UNAVAILABLE")

    def write(self, name, data, detached_report=False):
        require(name in OUTPUT_NAMES and type(data) is bytes and
                (not detached_report or name == "capture-report.json"), "PUBLICATION_UNAVAILABLE")
        self._assert_owned()
        if not detached_report:
            self._assert_current()
            self._assert_members()
        # Linux O_TMPFILE has no pathname to collide with, replace, unlink or redirect.
        # Unsupported filesystems/procfd links fail closed; there is no unsafe named fallback.
        temporary_flag = getattr(os, "O_TMPFILE", None)
        require(type(temporary_flag) is int, "PUBLICATION_UNAVAILABLE")
        temporary = os.open(".", os.O_RDWR | os.O_CLOEXEC | temporary_flag, 0o600, dir_fd=self.descriptor)
        try:
            metadata = os.fstat(temporary)
            require(stat.S_ISREG(metadata.st_mode) and metadata.st_nlink == 0 and
                    metadata.st_uid == os.geteuid() and metadata.st_mode & 0o777 == 0o600,
                    "PUBLICATION_UNAVAILABLE")
            owned_temporary = self._inode(metadata)
            pending = memoryview(data)
            while pending:
                count = os.write(temporary, pending)
                require(type(count) is int and 0 < count <= len(pending), "PUBLICATION_UNAVAILABLE")
                pending = pending[count:]
            require(os.fstat(temporary).st_size == len(data), "PUBLICATION_UNAVAILABLE")
            os.fsync(temporary)
            if not detached_report:
                self._assert_current()
                self._assert_members()
            # linkat follows only our controlled /proc/self/fd entry, never a named temporary.
            # The destination is relative to the pinned dirFD and atomically refuses any existing name.
            os.link("/proc/self/fd/" + str(temporary), name, dst_dir_fd=self.descriptor, follow_symlinks=True)
            linked = os.stat(name, dir_fd=self.descriptor, follow_symlinks=False)
            require(stat.S_ISREG(linked.st_mode) and self._inode(linked) == owned_temporary,
                    "PUBLICATION_UNAVAILABLE")
            self.files[name] = owned_temporary
            os.fsync(self.descriptor)
            if not detached_report:
                self._assert_current()
                self._assert_members()
        except BaseException:
            # Cleanup must not mask the original interruption or storage failure.
            try:
                os.close(temporary)
            except BaseException:
                pass
            raise
        else:
            # The only cleanup is closing our own tracked descriptor. No unlink/replace of any name.
            os.close(temporary)

    def close(self):
        if self.descriptor is not None:
            descriptor, self.descriptor = self.descriptor, None
            os.close(descriptor)


def publish(directory, name, data):
    """Only validated technical bytes; callers cannot substitute a mutable path for the owned dirFD."""
    require(type(directory) is _OwnedDirectory, "PUBLICATION_UNAVAILABLE")
    directory.write(name, data)


def expected_identity(path, source, local):
    if path is None:
        return None
    data = ASM.v1.read_document(path, ASM.v1.MAX_IDENTITY_BYTES)
    value = ASM.v1.decode(data, ASM.v1.MAX_IDENTITY_BYTES)
    ASM.v1.identity(value)
    require(value["sourceCommit"] == source["commit"] and value["sourceManifestSha256"] == source["manifestSha256"] and
            value["appPackage"] == PACKAGES["app"] and value["testPackage"] == PACKAGES["test"] and
            value["appApkSha256"] == local["app"] and value["testApkSha256"] == local["test"], "EXPECTED_IDENTITY_MISMATCH")
    return data


def record(adb, serial, target, source_root, source_manifest, app_apk, test_apk, directory,
           expected_identity_path=None, clock=None):
    directory = _OwnedDirectory.create(directory)
    report = {"schemaVersion": 1, "kind": "ORION_RUNTIME_CAPTURE_HOST_REPORT", "captureIntegrity": "REJECTED",
              "qualification": "NOT_QUALIFIED", "environment": "HOST", "instrumentationCommandIssued": False,
              "invocationOutcome": "NOT_ISSUED", "ownerCallInvoked": None, "programFrameObserved": False,
              "automaticReplayPermitted": False, "installPerformed": False, "deviceSettingsChanged": False,
              "possibleTechnicalBootstrapWrites": True, "publicationState": "FINAL_ACK_NOT_PROVEN"}
    report.update({key: False for key in FLAGS})
    payloads = {}
    interruption = None
    try:
        require(type(serial) is str and PREFLIGHT.SERIAL.fullmatch(serial), "EXPLICIT_PHYSICAL_SERIAL_REQUIRED")
        require(target in TARGETS, "TARGET_INVALID")
        timing = CLOCK.clock_for(clock=clock)
        timing.read_ns()
        metadata = SINGLE.clock_metadata(timing)
        require(metadata["clockSource"] == "CLOCK_BOOTTIME" and metadata["evidence"] == "HOST_CLOCK_ONLY" and
                metadata["bootIdentitySha256"] is not None, "REAL_HOST_CLOCK_REQUIRED")
        report["hostTiming"] = metadata
        source, artifacts = PREFLIGHT.source_identity(source_root, source_manifest, required_artifacts=frozenset({"app", "test"}))
        local = {"app": PREFLIGHT.sha_file(app_apk, 1024**3), "test": PREFLIGHT.sha_file(test_apk, 1024**3)}
        require(local == artifacts, "ARTIFACT_PROVENANCE_MISMATCH")
        expected = expected_identity(expected_identity_path, source, local)
        identity = target_identity(adb, serial, target)
        installed = {key: PREFLIGHT.installed_hash(adb, package) for key, package in PACKAGES.items()}
        require(installed == local, "INSTALLED_APK_MISMATCH")
        before = device_window(adb)
        started = timing.read_ns()
        report.update(source=source, artifactSha256=local, installedApkSha256=installed,
                      target={k: v for k, v in identity.items() if k not in {"manufacturer", "model"}},
                      declaredEnvironment=identity["environment"], windowBefore=before)
        if expected is not None:
            report["expectedIdentitySha256"] = hashlib.sha256(expected).hexdigest()
        report.update(instrumentationCommandIssued=True, invocationOutcome="UNKNOWN")
        output = adb(*instrumentation_command())
        require(timing.read_ns() - started <= TRANSPORT_TIMEOUT_SECONDS * CLOCK.NANOS, "HOST_PROGRAM_GAP")
        after = device_window(adb)
        require(before["bootIdSha256"] == after["bootIdSha256"] and before["bootCount"] == after["bootCount"] and
                before["totalMemoryBytes"] == after["totalMemoryBytes"] and
                before["elapsedLowerMillis"] <= after["elapsedLowerMillis"], "ANDROID_BOOT_OR_WINDOW_CHANGED")
        require(target_identity(adb, serial, target) == identity, "DEVICE_IDENTITY_CHANGED")
        require({key: PREFLIGHT.installed_hash(adb, package) for key, package in PACKAGES.items()} == installed, "INSTALLED_APK_CHANGED")
        current_source, current_artifacts = PREFLIGHT.source_identity(source_root, source_manifest,
                                                                     required_artifacts=frozenset({"app", "test"}))
        require(current_source == source and current_artifacts == artifacts and
                {"app": PREFLIGHT.sha_file(app_apk, 1024**3), "test": PREFLIGHT.sha_file(test_apk, 1024**3)} == local,
                "SOURCE_OR_LOCAL_ARTIFACT_CHANGED")
        require(expected_identity(expected_identity_path, source, local) == expected, "EXPECTED_IDENTITY_CHANGED")
        ended = timing.read_ns()
        require(ended - started <= TRANSPORT_TIMEOUT_SECONDS * CLOCK.NANOS, "HOST_PROGRAM_GAP")
        frame = receipts(output)
        validate_frame(frame)
        require(frame["source"] is None or expected is not None, "EXPECTED_IDENTITY_REQUIRED")
        platform = {"declaredEnvironment": identity["environment"], "bootCount": before["bootCount"],
                    "bootIdSha256": before["bootIdSha256"], "startedElapsed": before["elapsedLowerMillis"],
                    "completedElapsed": after["elapsedUpperMillis"], "api": identity["api"], "abi": identity["abi"],
                    "pageSizeBytes": identity["pageSizeBytes"], "deviceBuildSha256": identity["fingerprintSha256"],
                    "totalMemoryBytes": after["totalMemoryBytes"]}
        assembly = ASM.assemble_json(frame["source"], frame["status"], expected,
                                     json.dumps(platform, separators=(",", ":")).encode("ascii"))
        header = json.loads(assembly.status_json)
        known = frame["code"] == -1 and header["ownerCallInvoked"] is not None
        report.update(captureIntegrity="COMPLETE" if known else "INCOMPLETE",
                      invocationOutcome="PROGRAM_OBSERVED" if known else "UNKNOWN", programFrameObserved=True,
                      ownerCallInvoked=header["ownerCallInvoked"], programResultCode=frame["code"],
                      recordState=header["recordState"], sourceCaptureIntegrity=header["captureIntegrity"],
                      windowAfter=after, hostProgramSpanNanoseconds=ended - started, hostTiming=SINGLE.clock_metadata(timing))
        if not known:
            report["failureCode"] = "RUNNER_UNAVAILABLE" if frame["code"] == 0 else "OWNER_INVOCATION_UNAVAILABLE"
        payloads = {"capture-status.json": assembly.status_json, "assembly-report.json": assembly.report_json}
        if assembly.runtime_json is not None:
            payloads["runtime-v1.json"] = assembly.runtime_json
    except Rejected as error:
        code = str(error)
        report["failureCode"] = code if code in FAILURE_CODES else "INPUT_OR_OBSERVATION_UNAVAILABLE"
    except ASM.Rejected:
        report["failureCode"] = "SOURCE_OR_STATUS_INVALID"
    except CLOCK.HostTimingError as error:
        report["failureCode"] = error.code if error.code in CLOCK.FAILURES else "HOST_CLOCK_UNAVAILABLE"
    except Exception:
        report["failureCode"] = "INPUT_OR_OBSERVATION_UNAVAILABLE"
    except BaseException as error:
        interruption = error
        report["failureCode"] = "INTERRUPTED"
    if report["instrumentationCommandIssued"] and not report["programFrameObserved"]:
        report.update(captureIntegrity="INCOMPLETE", invocationOutcome="UNKNOWN")
    try:
        for name, data in payloads.items():
            publish(directory, name, data)
        publish(directory, "capture-report.json", json.dumps(report, sort_keys=True, separators=(",", ":")).encode("ascii"))
    except BaseException as error:
        report.update(captureIntegrity="INCOMPLETE", publicationState="UNKNOWN", failureCode="PUBLICATION_UNAVAILABLE")
        if report["instrumentationCommandIssued"]:
            report["invocationOutcome"] = "UNKNOWN"
        if interruption is None and not isinstance(error, Exception):
            interruption = error
        try:
            # Even if the original path moved, only the same owned inode can receive this UNKNOWN report.
            directory.write("capture-report.json", json.dumps(report, sort_keys=True, separators=(",", ":")).encode("ascii"),
                            detached_report=True)
        except BaseException:
            # No filesystem method can promise a record when the owned storage itself fails.
            pass
    try:
        directory.close()
    except BaseException as error:
        if interruption is None:
            interruption = error
    if interruption is not None:
        raise interruption
    return report


class ClosedArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, "CLI_ARGUMENTS_INVALID: explicit target/source/artifact inputs required; no workload configuration accepted.\n")


def main():
    parser = ClosedArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--target", required=True, choices=TARGETS)
    for name in ("source-root", "source-manifest", "app-apk", "test-apk", "evidence-dir"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--expected-identity", type=Path)
    args = parser.parse_args()
    try:
        report = record(CaptureAdb(args.adb, args.serial), args.serial, args.target, args.source_root,
                        args.source_manifest, args.app_apk, args.test_apk, args.evidence_dir, args.expected_identity)
    except (OSError, ValueError):
        parser.error("A new private evidence directory is required.")
    print(report["captureIntegrity"] + ": NOT_QUALIFIED; automatic replay is forbidden.")
    raise SystemExit(0 if report["captureIntegrity"] == "COMPLETE" else 2)


if __name__ == "__main__":
    main()
