#!/usr/bin/env python3
"""Synthetic HOST fixtures only. No ADB, provisioning, actual runtime or qualification."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock

import test_f5_runtime_source_assembler_harness as source_fixtures


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


REC = load("orion_runtime_capture_test", "record-f5-runtime-capture.py")
BASE_TEST = load("orion_runtime_capture_baseline", "test_f5_physical_baseline_harness.py")


def compact(value):
    return json.dumps(value, separators=(",", ":"))


def no_load():
    value = source_fixtures.status()
    value.update(recordState="NO_LOAD_REFUSAL", captureIntegrity="UNAVAILABLE",
                 ownerCallInvoked=False, failureCode="NO_QUALIFIED_PROFILE")
    return value


def unavailable(owner=None):
    return dict(no_load(), recordState="NO_V1_RECORD", ownerCallInvoked=owner, failureCode="SOURCE_UNAVAILABLE")


def transcript(header=None, source=None, code=-1, stream=None):
    header = no_load() if header is None else header
    if stream is None:
        stream = "ORION_RUNTIME_SOURCE" if source is not None else (
            "ORION_RUNTIME_REFUSED" if header["recordState"] == "NO_LOAD_REFUSAL" else "ORION_RUNTIME_UNAVAILABLE")
    lines = ["INSTRUMENTATION_RESULT: " + REC.STATUS_KEY + "=" + compact(header)]
    if source is not None:
        lines.append("INSTRUMENTATION_RESULT: " + REC.SOURCE_KEY + "=" + compact(source))
    return "\n".join(lines + ["INSTRUMENTATION_RESULT: stream=", stream, "", "INSTRUMENTATION_CODE: " + str(code)])


class Adb(BASE_TEST.FakeAdb):
    def __init__(self, hashes):
        super().__init__(hashes)
        self.output = transcript()
        self.boot_count = "7"
        self.after_boot_count = "7"

    def __call__(self, *command):
        if command == REC.instrumentation_command():
            self.commands.append(command)
            self.on_instrument()
            self.after = True
            return self.output
        if command == REC.BOOT_COUNT_COMMAND:
            self.commands.append(command)
            return self.after_boot_count if self.after else self.boot_count
        if command == ("shell", "cat", "/proc/uptime"):
            self.commands.append(command)
            return "10.00 20.00" if self.after else "0.00 0.00"
        return super().__call__(*command)


class RuntimeCaptureHostTest(unittest.TestCase):
    def setUp(self):
        self.fixture = BASE_TEST.PhysicalBaselineRecorderTest(methodName="runTest")
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.adb = Adb(self.fixture.hashes)
        self.clock_mark = 0
        def read_ns():
            self.clock_mark += 1_000_000
            return self.clock_mark
        self.clock = REC.CLOCK.HostClock(read_ns, lambda: "00000000-0000-4000-8000-000000000010")
        self.clock.metadata.update(clockSource="CLOCK_BOOTTIME", evidence="HOST_CLOCK_ONLY")
        patch = mock.patch.object(REC.PREFLIGHT, "git", side_effect=self.fixture.git)
        patch.start(); self.addCleanup(patch.stop)
        self.expected_path = self.fixture.root / "expected.json"
        self.expected = source_fixtures.fixtures.identity()
        self.expected.update(sourceCommit=self.fixture.commit,
                             sourceManifestSha256=hashlib.sha256(self.fixture.manifest.read_bytes()).hexdigest(),
                             appApkSha256=self.fixture.hashes["app"], testApkSha256=self.fixture.hashes["test"])
        self.expected_path.write_text(compact(self.expected))

    def record(self, expected=False, **kwargs):
        f = self.fixture
        return REC.record(self.adb, kwargs.pop("serial", "EXPLICIT_PHYSICAL_SERIAL"), kwargs.pop("target", "S21"),
                          f.source, f.manifest, f.app, f.test, f.output,
                          expected_identity_path=self.expected_path if expected else None, clock=self.clock, **kwargs)

    def issued(self):
        return sum(command == REC.instrumentation_command() for command in self.adb.commands)

    def files(self):
        return {p.name for p in self.fixture.output.iterdir()}

    def all_persisted(self):
        return b"\n".join(p.read_bytes() for p in self.fixture.output.iterdir() if p.is_file())

    def source(self):
        source, _, _ = source_fixtures.capsule()
        header = source_fixtures.status()
        header["ownerCallInvoked"] = True
        self.adb.output = transcript(header, source)
        return source, header

    def test_no_load_refusal_without_model_metadata_is_one_valid_program_not_qualification(self):
        result = self.record()
        self.assertEqual("COMPLETE", result["captureIntegrity"])
        self.assertEqual("PROGRAM_OBSERVED", result["invocationOutcome"])
        self.assertIs(result["ownerCallInvoked"], False)
        self.assertEqual(1, self.issued())
        self.assertEqual({"capture-report.json", "capture-status.json", "assembly-report.json"}, self.files())
        for key in REC.FLAGS:
            self.assertIs(result[key], False)
        self.assertIs(result["automaticReplayPermitted"], False)
        self.assertNotIn(b"EXPLICIT_PHYSICAL_SERIAL", self.all_persisted())
        self.assertNotIn(b"ORION_PRIVATE_DEVICE_SERIAL", self.all_persisted())
        self.assertNotIn(str(self.fixture.source).encode(), self.all_persisted())

    def test_source_uses_independent_model_and_exact_host_binding_without_main_repairs(self):
        source, _ = self.source()
        result = self.record(expected=True)
        self.assertEqual("COMPLETE", result["captureIntegrity"])
        runtime = json.loads((self.fixture.output / "runtime-v1.json").read_text())
        self.assertEqual(self.expected, runtime["identity"])
        self.assertEqual(source["session"]["startedElapsed"], runtime["session"]["startedElapsed"])
        self.assertEqual(source["session"]["mainPid"], runtime["session"]["mainPid"])
        self.assertEqual(source["inference"]["requestIdSha256"], runtime["inference"]["chunks"][0]["requestIdSha256"])
        self.assertEqual(1, self.issued())

    def test_source_without_independent_identity_is_unknown_without_v1_or_replay(self):
        self.source()
        report = self.record()
        self.assertEqual("UNKNOWN", report["invocationOutcome"])
        self.assertEqual(1, self.issued())
        self.assertEqual({"capture-report.json"}, self.files())

    def test_expected_model_mismatch_is_not_repaired_or_used_to_select_work(self):
        self.source(); self.expected["modelSizeBytes"] += 1
        self.expected_path.write_text(compact(self.expected))
        report = self.record(expected=True)
        self.assertEqual("UNKNOWN", report["invocationOutcome"])
        self.assertNotIn("runtime-v1.json", self.files())
        self.assertEqual(1, self.issued())

    def test_expected_freeze_or_apk_mismatch_blocks_before_invocation(self):
        self.expected["appApkSha256"] = "f" * 64
        self.expected_path.write_text(compact(self.expected))
        report = self.record(expected=True)
        self.assertEqual("NOT_ISSUED", report["invocationOutcome"])
        self.assertEqual(0, self.issued())

    def test_incomplete_source_and_status_remain_incomplete(self):
        source, header = self.source()
        source["memory"].pop()
        header.update(captureIntegrity="INCOMPLETE", failureCode="PHASE_MISSED", droppedAtLeast=1,
                      lossAccountingComplete=False)
        self.adb.output = transcript(header, source)
        self.record(expected=True)
        status = json.loads((self.fixture.output / "capture-status.json").read_text())
        assembly = json.loads((self.fixture.output / "assembly-report.json").read_text())
        self.assertEqual("INCOMPLETE", status["captureIntegrity"])
        self.assertEqual("ASSEMBLED_INCOMPLETE_METADATA", assembly["result"])

    def test_valid_program_no_v1_has_nullable_independent_owner_fact(self):
        for owner in (True, False, None):
            with self.subTest(owner=owner):
                # Separate new owned directory for each explicitly selected test call.
                self.fixture.output = self.fixture.root / ("unknown-" + str(owner))
                self.adb.output = transcript(unavailable(owner))
                self.adb.after = False
                result = self.record()
                self.assertIs(result["ownerCallInvoked"], owner)
                self.assertEqual("UNKNOWN" if owner is None else "PROGRAM_OBSERVED", result["invocationOutcome"])
                self.assertNotIn("runtime-v1.json", self.files())

    def test_runner_failure_code_zero_is_unknown_even_when_adb_exits_normally(self):
        self.adb.output = transcript(unavailable(), code=0)
        result = self.record()
        self.assertEqual("UNKNOWN", result["invocationOutcome"])
        self.assertEqual(1, self.issued())
        self.assertNotIn("runtime-v1.json", self.files())

    def test_no_load_null_owner_and_code_zero_false_owner_are_rejected(self):
        for text in (transcript(dict(no_load(), ownerCallInvoked=None)), transcript(unavailable(False), code=0)):
            with self.subTest(text=text[:40]):
                with self.assertRaises(REC.Rejected):
                    REC.validate_frame(REC.receipts(text))

    def test_stream_code_source_presence_and_extra_keys_are_strict(self):
        source, header = self.source()
        bad = [transcript(header, source, code=0), transcript(header, source, stream="ORION_RUNTIME_REFUSED"),
               transcript(no_load(), source), transcript(unavailable(), stream="ORION_RUNTIME_SOURCE"),
               transcript() + "\nINSTRUMENTATION_RESULT: SECRET_SENTINEL=SECRET_SENTINEL"]
        for text in bad:
            with self.subTest(text=text[-60:]), self.assertRaises((REC.Rejected, REC.ASM.Rejected)):
                REC.validate_frame(REC.receipts(text))

    def test_parser_rejects_status_flood_duplicate_trailing_and_unbounded_raw_output(self):
        text = transcript()
        bad = ["INSTRUMENTATION_STATUS_CODE: 1\n" + text, text + "\n" + text,
               text.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: -1\nSECRET_SENTINEL"),
               text.replace("INSTRUMENTATION_RESULT: stream=", "INSTRUMENTATION_RESULT: extra=SECRET_SENTINEL\nINSTRUMENTATION_RESULT: stream="),
               text.replace("INSTRUMENTATION_RESULT: stream=", "INSTRUMENTATION_RESULT: " + REC.STATUS_KEY + "={}\nINSTRUMENTATION_RESULT: stream="),
               " " * (REC.MAX_OUTPUT + 1), text + "\u00e9", text.replace("stream=", "stream=\x00")]
        for value in bad:
            with self.subTest(size=len(value)), self.assertRaises(REC.Rejected):
                REC.receipts(value)

    def test_partial_source_status_and_sensitive_raw_text_are_never_persisted(self):
        self.adb.output = transcript() + "\nSECRET_SENTINEL /private/path"
        result = self.record()
        self.assertEqual("UNKNOWN", result["invocationOutcome"])
        self.assertEqual({"capture-report.json"}, self.files())
        self.assertNotIn(b"SECRET_SENTINEL", self.all_persisted())

    def test_boot_count_change_after_invocation_stays_unknown(self):
        self.adb.after_boot_count = "8"
        result = self.record()
        self.assertEqual("UNKNOWN", result["invocationOutcome"])
        self.assertEqual(1, self.issued())

    def test_source_boot_does_not_get_replaced_from_host(self):
        source, header = self.source(); source["session"]["bootCount"] = 8
        self.adb.output = transcript(header, source)
        self.assertEqual("UNKNOWN", self.record(expected=True)["invocationOutcome"])
        self.assertNotIn("runtime-v1.json", self.files())

    def test_preexisting_split_or_wrong_installed_pair_never_invokes(self):
        package = REC.PACKAGES["app"]
        self.adb.answers[("shell", "pm", "path", package)] += "\npackage:/data/app/extra/split.apk"
        self.assertEqual("NOT_ISSUED", self.record()["invocationOutcome"])
        self.assertEqual(0, self.issued())

    def test_postinvocation_artifact_source_and_expected_changes_are_unknown(self):
        def change():
            self.fixture.app.write_bytes(b"SECRET_SENTINEL_CHANGED")
        self.adb.on_instrument = change
        self.assertEqual("UNKNOWN", self.record()["invocationOutcome"])
        self.assertEqual(1, self.issued())
        self.assertNotIn(b"SECRET_SENTINEL", self.all_persisted())

    def test_original_baseexceptions_propagate_after_minimized_unknown_record(self):
        original = GeneratorExit("SECRET_SENTINEL")
        self.adb.on_instrument = lambda: (_ for _ in ()).throw(original)
        with self.assertRaises(GeneratorExit) as caught:
            self.record()
        self.assertIs(original, caught.exception)
        report = json.loads((self.fixture.output / "capture-report.json").read_text())
        self.assertEqual("UNKNOWN", report["invocationOutcome"])
        self.assertEqual("INTERRUPTED", report["failureCode"])
        self.assertNotIn(b"SECRET_SENTINEL", self.all_persisted())

    def test_timeout_after_one_command_is_unknown_and_has_no_second_invocation(self):
        self.adb.on_instrument = lambda: (_ for _ in ()).throw(REC.Rejected("ADB_TRANSPORT_TIMEOUT"))
        self.assertEqual("UNKNOWN", self.record()["invocationOutcome"])
        self.assertEqual(1, self.issued())
        self.assertFalse(any("kill" in c or "install" in c or "uninstall" in c for c in self.adb.commands))

    def test_publication_failure_cannot_report_a_success_or_repeat_android_work(self):
        original = REC.publish
        def fail(directory, name, data):
            if name == "assembly-report.json":
                raise OSError("SECRET_SENTINEL")
            return original(directory, name, data)
        with mock.patch.object(REC, "publish", side_effect=fail):
            result = self.record()
        self.assertEqual("UNKNOWN", result["invocationOutcome"])
        self.assertEqual("PUBLICATION_UNAVAILABLE", result["failureCode"])
        self.assertEqual(1, self.issued())
        self.assertNotIn(b"SECRET_SENTINEL", self.all_persisted())

    def test_foreign_named_temporary_is_never_unlinked(self):
        foreign = self.fixture.output / ".capture-status.json.tmp"
        self.adb.on_instrument = lambda: foreign.write_bytes(b"FOREIGN_PRIVATE_SENTINEL")
        result = self.record()
        self.assertEqual("UNKNOWN", result["invocationOutcome"])
        self.assertEqual(b"FOREIGN_PRIVATE_SENTINEL", foreign.read_bytes())
        self.assertEqual(1, self.issued())

    def test_foreign_final_file_is_never_overwritten(self):
        foreign = self.fixture.output / "capture-status.json"
        self.adb.on_instrument = lambda: foreign.write_bytes(b"FOREIGN_PRIVATE_SENTINEL")
        result = self.record()
        self.assertEqual("UNKNOWN", result["invocationOutcome"])
        self.assertEqual(b"FOREIGN_PRIVATE_SENTINEL", foreign.read_bytes())
        self.assertNotIn("assembly-report.json", self.files())
        self.assertEqual(1, self.issued())

    def test_foreign_final_symlink_and_its_target_are_never_changed(self):
        outside = self.fixture.root / "outside-private"
        outside.write_bytes(b"FOREIGN_PRIVATE_SENTINEL")
        foreign = self.fixture.output / "capture-status.json"
        self.adb.on_instrument = lambda: foreign.symlink_to(outside)
        result = self.record()
        self.assertEqual("UNKNOWN", result["invocationOutcome"])
        self.assertTrue(foreign.is_symlink())
        self.assertEqual(b"FOREIGN_PRIVATE_SENTINEL", outside.read_bytes())

    def test_final_collision_at_atomic_link_never_clobbers_foreign_inode(self):
        real = REC.os.link
        foreign = self.fixture.output / "capture-status.json"
        def collide(source, destination, **kwargs):
            if destination == "capture-status.json":
                foreign.write_bytes(b"FOREIGN_PRIVATE_SENTINEL")
            return real(source, destination, **kwargs)
        with mock.patch.object(REC.os, "link", side_effect=collide):
            result = self.record()
        self.assertEqual("UNKNOWN", result["invocationOutcome"])
        self.assertEqual(b"FOREIGN_PRIVATE_SENTINEL", foreign.read_bytes())
        self.assertEqual(1, self.issued())

    def test_removed_published_status_cannot_be_missing_from_a_complete_capture(self):
        self.source()
        real = REC.os.link
        def remove_status(source, destination, **kwargs):
            if destination == "assembly-report.json":
                REC.os.unlink("capture-status.json", dir_fd=kwargs["dst_dir_fd"])
            return real(source, destination, **kwargs)
        with mock.patch.object(REC.os, "link", side_effect=remove_status):
            result = self.record(expected=True)
        self.assertEqual("UNKNOWN", result["invocationOutcome"])
        self.assertEqual("PUBLICATION_UNAVAILABLE", result["failureCode"])
        self.assertNotIn("runtime-v1.json", self.files())
        self.assertEqual(1, self.issued())

    def test_existing_foreign_capture_report_is_not_replaced_by_failure_reporting(self):
        foreign = self.fixture.output / "capture-report.json"
        self.adb.on_instrument = lambda: foreign.write_bytes(b"FOREIGN_REPORT_SENTINEL")
        result = self.record()
        self.assertEqual("UNKNOWN", result["invocationOutcome"])
        self.assertEqual(b"FOREIGN_REPORT_SENTINEL", foreign.read_bytes())

    def test_symlink_parent_is_refused_before_any_device_command(self):
        actual = self.fixture.root / "foreign-parent"
        actual.mkdir()
        alias = self.fixture.root / "alias-parent"
        alias.symlink_to(actual, target_is_directory=True)
        self.fixture.output = alias / "capture"
        with self.assertRaises(OSError):
            self.record()
        self.assertEqual([], list(actual.iterdir()))
        self.assertEqual([], self.adb.commands)

    def test_output_symlink_swap_never_writes_outside_owned_inode(self):
        owned = self.fixture.root / "original-owned"
        foreign = self.fixture.root / "foreign-directory"
        foreign.mkdir()
        def swap():
            self.fixture.output.rename(owned)
            self.fixture.output.symlink_to(foreign, target_is_directory=True)
        self.adb.on_instrument = swap
        result = self.record()
        self.assertEqual("UNKNOWN", result["invocationOutcome"])
        self.assertEqual([], list(foreign.iterdir()))
        self.assertTrue(self.fixture.output.is_symlink())
        self.assertEqual("UNKNOWN", json.loads((owned / "capture-report.json").read_text())["invocationOutcome"])
        self.assertEqual(1, self.issued())

    def test_output_real_directory_replacement_never_gets_owned_writes(self):
        owned = self.fixture.root / "original-owned"
        def swap():
            self.fixture.output.rename(owned)
            self.fixture.output.mkdir()
        self.adb.on_instrument = swap
        result = self.record()
        self.assertEqual("UNKNOWN", result["invocationOutcome"])
        self.assertEqual([], list(self.fixture.output.iterdir()))
        self.assertEqual("UNKNOWN", json.loads((owned / "capture-report.json").read_text())["invocationOutcome"])

    def test_parent_symlink_swap_does_not_redirect_publication(self):
        parent = self.fixture.root / "publication-parent"
        parent.mkdir(); self.fixture.output = parent / "capture"
        moved = self.fixture.root / "moved-parent"
        foreign = self.fixture.root / "foreign-parent"
        foreign.mkdir(); (foreign / "capture").mkdir()
        def swap():
            parent.rename(moved)
            parent.symlink_to(foreign, target_is_directory=True)
        self.adb.on_instrument = swap
        result = self.record()
        self.assertEqual("UNKNOWN", result["invocationOutcome"])
        self.assertEqual([], list((foreign / "capture").iterdir()))
        self.assertEqual("UNKNOWN", json.loads((moved / "capture/capture-report.json").read_text())["invocationOutcome"])

    def test_partial_anonymous_write_failure_does_not_publish_partial_source(self):
        real = REC.os.write
        calls = 0
        def partial(fd, data):
            nonlocal calls
            calls += 1
            if calls == 1:
                return real(fd, data[:max(1, len(data) // 2)])
            if calls == 2:
                return 0
            return real(fd, data)
        with mock.patch.object(REC.os, "write", side_effect=partial):
            result = self.record()
        self.assertEqual("UNKNOWN", result["invocationOutcome"])
        self.assertEqual({"capture-report.json"}, self.files())
        self.assertEqual(1, self.issued())

    def test_unavailable_anonymous_tmpfile_support_fails_closed_without_named_fallback(self):
        real = REC.os.open
        def open_file(path, flags, *args, **kwargs):
            if flags & getattr(REC.os, "O_TMPFILE", 0) == getattr(REC.os, "O_TMPFILE", -1):
                raise OSError("PRIVATE_FILESYSTEM_SENTINEL")
            return real(path, flags, *args, **kwargs)
        with mock.patch.object(REC.os, "open", side_effect=open_file):
            result = self.record()
        self.assertEqual("UNKNOWN", result["invocationOutcome"])
        self.assertEqual([], list(self.fixture.output.iterdir()))
        self.assertEqual(1, self.issued())

    def test_unavailable_procfd_link_fails_closed_and_never_names_temporary_payload(self):
        with mock.patch.object(REC.os, "link", side_effect=OSError("PRIVATE_LINK_SENTINEL")):
            result = self.record()
        self.assertEqual("UNKNOWN", result["invocationOutcome"])
        self.assertEqual([], list(self.fixture.output.iterdir()))
        self.assertEqual(1, self.issued())

    def test_publication_baseexception_is_original_after_unknown_report_attempt(self):
        original = GeneratorExit("PRIVATE_PUBLICATION_SENTINEL")
        real = REC.os.write
        calls = 0
        def interrupted(fd, data):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise original
            return real(fd, data)
        with mock.patch.object(REC.os, "write", side_effect=interrupted), self.assertRaises(GeneratorExit) as caught:
            self.record()
        self.assertIs(original, caught.exception)
        report = json.loads((self.fixture.output / "capture-report.json").read_text())
        self.assertEqual("UNKNOWN", report["invocationOutcome"])
        self.assertEqual(1, self.issued())
        self.assertNotIn(b"PRIVATE_PUBLICATION_SENTINEL", self.all_persisted())

    def test_existing_evidence_directory_is_refused_before_device_work(self):
        self.fixture.output.mkdir()
        with self.assertRaises(FileExistsError):
            self.record()
        self.assertEqual([], self.adb.commands)

    def test_only_real_host_clock_metadata_can_precede_device_access(self):
        self.clock.metadata.update(clockSource="SYNTHETIC_INJECTED", evidence="UNVERIFIED_INPUT")
        self.assertEqual("NOT_ISSUED", self.record()["invocationOutcome"])
        self.assertEqual([], self.adb.commands)

    def test_emulator_target_uses_actual_positive_signals_and_never_physical_label(self):
        self.adb.answers.update({("shell", "getprop", "ro.kernel.qemu"): "1",
                                 ("shell", "getprop", "ro.hardware"): "ranchu",
                                 ("shell", "getprop", "ro.product.cpu.abi"): "x86_64",
                                 ("shell", "getprop", "ro.build.fingerprint"): "Android/sdk_phone64/emu:15/ID/123:userdebug/test-keys"})
        result = self.record(serial="emulator-5596", target="API35_EMULATOR")
        self.assertEqual("COMPLETE", result["captureIntegrity"])
        self.assertEqual("ANDROID_EMULATOR", result["declaredEnvironment"])
        self.assertIs(result["physicalVerified"], False)

    def test_emulator_cannot_be_promoted_and_physical_cannot_claim_emulator_without_signals(self):
        for serial, target in (("emulator-5596", "S21"), ("EXPLICIT_PHYSICAL_SERIAL", "API35_EMULATOR")):
            with self.assertRaises(REC.Rejected):
                REC.target_identity(self.adb, serial, target)

    def test_adb_allowlist_contains_only_fixed_runner_read_queries_and_readonly_boot_count(self):
        self.assertTrue(REC.allowed_command(REC.instrumentation_command()))
        self.assertTrue(REC.allowed_command(REC.BOOT_COUNT_COMMAND))
        for command in [("shell", "settings", "put", "global", "boot_count", "0"),
                        REC.instrumentation_command()[:-1] + ("other/runner",),
                        ("shell", "am", "force-stop", "network.orion.qualification"),
                        ("install", "file.apk"), ("shell", "setprop", "x", "y"),
                        REC.instrumentation_command()[:-1] + ("--no-restart", REC.RUNNER),
                        tuple(REC.BASE.instrumentation_command(3, 100))]:
            with self.subTest(command=command):
                self.assertFalse(REC.allowed_command(command))
        self.assertEqual(("shell", "am", "instrument", "-w", "-r", REC.RUNNER), REC.instrumentation_command())

    def client_factory(self, code):
        real = subprocess.Popen
        children = []
        def create(command, **kwargs):
            self.assertEqual(["owned-adb", "-s", "EXPLICIT_PHYSICAL_SERIAL"] + list(REC.instrumentation_command()), command)
            self.assertEqual(subprocess.DEVNULL, kwargs["stdin"])
            self.assertEqual(subprocess.DEVNULL, kwargs["stderr"])
            self.assertEqual(subprocess.PIPE, kwargs["stdout"])
            child = real([sys.executable, "-u", "-c", code], **kwargs)
            children.append(child)
            return child
        return create, children

    def test_transport_preserves_frame_whitespace_and_consumes_exactly_one_command(self):
        adapter = REC.CaptureAdb("owned-adb", "EXPLICIT_PHYSICAL_SERIAL")
        create, children = self.client_factory("print(' fixture transport ')")
        with mock.patch.object(REC.CLOCK.HostClock, "linux", return_value=self.clock), \
                mock.patch.object(REC.subprocess, "Popen", side_effect=create) as run:
            self.assertEqual(" fixture transport \n", adapter(*REC.instrumentation_command()))
            with self.assertRaises(REC.Rejected):
                adapter(*REC.instrumentation_command())
            self.assertEqual(1, run.call_count)
        self.assertIsNotNone(children[0].poll())

    def test_streaming_transport_bounds_raw_bytes_before_owned_child_finishes(self):
        adapter = REC.CaptureAdb("owned-adb", "EXPLICIT_PHYSICAL_SERIAL")
        create, children = self.client_factory("import os\nwhile True: os.write(1,b'x'*65536)")
        sizes = []
        real = REC.os.read
        def read(fd, count):
            block = real(fd, count); sizes.append(len(block)); return block
        with mock.patch.object(REC.CLOCK.HostClock, "linux", return_value=self.clock), \
                mock.patch.object(REC.subprocess, "Popen", side_effect=create) as run, \
                mock.patch.object(REC.os, "read", side_effect=read):
            with self.assertRaisesRegex(REC.Rejected, "ADB_OUTPUT_BOUND_EXCEEDED"):
                adapter(*REC.instrumentation_command())
            self.assertEqual(1, run.call_count)
        self.assertEqual(REC.MAX_OUTPUT + 1, sum(sizes))
        self.assertIsNotNone(children[0].poll())

    def test_transport_boottime_watchdog_kills_only_its_local_host_client(self):
        adapter = REC.CaptureAdb("owned-adb", "EXPLICIT_PHYSICAL_SERIAL")
        create, children = self.client_factory("import time;time.sleep(30)")
        def jumped(command, **kwargs):
            child = create(command, **kwargs); self.clock_mark += 181 * REC.CLOCK.NANOS; return child
        with mock.patch.object(REC.CLOCK.HostClock, "linux", return_value=self.clock), \
                mock.patch.object(REC.subprocess, "Popen", side_effect=jumped) as run:
            with self.assertRaisesRegex(REC.Rejected, "ADB_TRANSPORT_TIMEOUT"):
                adapter(*REC.instrumentation_command())
            self.assertEqual(1, run.call_count)
        self.assertIsNotNone(children[0].poll())

    def test_transport_cleanup_errors_never_mask_the_original_baseexception(self):
        for failure in ("selector_close", "process_kill", "stdout_close"):
            with self.subTest(failure=failure):
                original = GeneratorExit("PRIVATE_ORIGINAL_TRANSPORT_SENTINEL")
                selector = mock.Mock()
                selector.select.side_effect = original
                process = mock.Mock()
                process.stdout.fileno.return_value = 83
                process.poll.return_value = None
                if failure == "selector_close":
                    selector.close.side_effect = OSError("PRIVATE_CLEANUP_SENTINEL")
                elif failure == "process_kill":
                    process.kill.side_effect = OSError("PRIVATE_CLEANUP_SENTINEL")
                else:
                    process.stdout.close.side_effect = OSError("PRIVATE_CLEANUP_SENTINEL")
                adapter = REC.CaptureAdb("owned-adb", "EXPLICIT_PHYSICAL_SERIAL")
                with mock.patch.object(REC.CLOCK.HostClock, "linux", return_value=self.clock), \
                        mock.patch.object(REC.subprocess, "Popen", return_value=process) as popen, \
                        mock.patch.object(REC.selectors, "DefaultSelector", return_value=selector), \
                        mock.patch.object(REC.os, "set_blocking"), self.assertRaises(GeneratorExit) as caught:
                    adapter(*REC.instrumentation_command())
                self.assertIs(original, caught.exception)
                self.assertEqual(1, popen.call_count)
                selector.close.assert_called_once()
                process.kill.assert_called_once()
                process.wait.assert_called_once_with(timeout=1)
                process.stdout.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
