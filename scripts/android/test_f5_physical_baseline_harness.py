#!/usr/bin/env python3
"""HOST fixtures only: no actual ADB, installation, model, heavy work or qualification."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

SCRIPT = Path(__file__).with_name("record-f5-physical-baseline.py")
SPEC = importlib.util.spec_from_file_location("orion_f5_baseline", SCRIPT)
BASELINE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASELINE)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def sample(index, count=3, interval=100):
    start = 100_100 + index * (interval + 10)
    return {"schemaVersion": 1, "kind": "PHYSICAL_BASELINE_SAMPLE", "environment": "PHYSICAL_DEVICE",
            "qualification": "NOT_QUALIFIED", "packageName": BASELINE.PACKAGE, "sampleIndex": index, "sampleCount": count,
            "bootCount": 7, "mainPid": 123, "aiPid": 456, "sampledElapsed": start, "aiSampledElapsed": start + 5,
            "completedElapsed": start + 10, "maximumAgeMillis": 1000, "mainPssBytes": 64 * 1024**2,
            "aiPssBytes": 32 * 1024**2, "aggregatePssBytes": 96 * 1024**2, "systemAvailableMemoryBytes": 4 * 1024**3,
            "batteryPercent": 75, "thermalStatus": 0, "powerSave": False, "foreground": False,
            "importance": "BACKGROUND", "memoryPressure": "NONE", "inventoryProcessCount": 2,
            "serviceState": "UNLOADED", "nativeRuntimeLoaded": False, "limitations": []}


def summary(count=3, interval=100, collected=None, result="OBSERVATION_COMPLETE_REVIEW_REQUIRED", failure=None):
    return {"schemaVersion": 1, "kind": "PHYSICAL_BASELINE_SUMMARY", "environment": "PHYSICAL_DEVICE",
            "qualification": "NOT_QUALIFIED", "packageName": BASELINE.PACKAGE, "result": result,
            "samplesRequested": count, "samplesCollected": count if collected is None else collected,
            "sampleIntervalMillis": interval, "profileQualified": False, "productionInferenceEnabled": False,
            "fullF5GateClosed": False, "fullSoakGateClosed": False, "heavyWorkIssued": False, "failureCode": failure}


def receipt_output(samples=None, final=None, code=-1):
    rows = [sample(index) for index in range(3)] if samples is None else samples
    result = summary() if final is None else final
    lines = []
    for row in rows:
        lines += ["INSTRUMENTATION_STATUS: orionPhysicalBaselineSample=" + json.dumps(row), "INSTRUMENTATION_STATUS_CODE: 2"]
    lines += ["INSTRUMENTATION_RESULT: orionPhysicalBaselineSummary=" + json.dumps(result),
              "INSTRUMENTATION_RESULT: stream=" + BASELINE.STREAM, "INSTRUMENTATION_CODE: " + str(code)]
    return "\n".join(lines)


def window(elapsed):
    return {"bootIdSha256": "b" * 64, "elapsedLowerMillis": elapsed, "elapsedUpperMillis": elapsed + 9,
            "totalMemoryBytes": 8 * 1024**3}


class FakeAdb:
    def __init__(self, hashes):
        self.commands = []
        self.after = False
        self.on_instrument = lambda: None
        self.output = receipt_output()
        self.uptime_reads = 0
        self.boot_id = "00000000-0000-4000-8000-000000000001"
        self.after_boot_id = self.boot_id
        self.answers = {
            ("get-state",): "device",
            ("shell", "getprop", "ro.kernel.qemu"): "",
            ("shell", "getprop", "ro.boot.qemu"): "0",
            ("shell", "getprop", "ro.build.fingerprint"): "samsung/o1sxxx/o1s:15/AP3A.240905.015.A2/G991BXX:user/release-keys",
            ("shell", "getprop", "ro.hardware"): "exynos2100",
            ("shell", "getprop", "ro.build.version.sdk"): "35",
            ("shell", "getprop", "ro.product.cpu.abi"): "arm64-v8a",
            ("shell", "getconf", "PAGE_SIZE"): "4096",
            ("shell", "getprop", "ro.product.manufacturer"): "samsung",
            ("shell", "getprop", "ro.product.model"): "SM-G991B",
            ("shell", "getprop", "ro.serialno"): "ORION_PRIVATE_DEVICE_SERIAL",
            ("shell", "cat", "/proc/meminfo"): "MemTotal:       8388608 kB\nMemAvailable:    4194304 kB",
        }
        for key, package in BASELINE.PACKAGES.items():
            path = "/data/app/~~baseline/" + package + "/base.apk"
            self.answers[("shell", "pm", "path", package)] = "package:" + path
            self.answers[("shell", "sha256sum", path)] = hashes[key] + "  " + path

    def __call__(self, *command):
        if not BASELINE.allowed_command(command):
            raise AssertionError("Unexpected or mutating ADB command: " + repr(command))
        self.commands.append(command)
        if command[:3] == ("shell", "am", "instrument"):
            self.on_instrument()
            self.after = True
            return self.output
        if command == ("shell", "cat", "/proc/uptime"):
            self.uptime_reads += 1
            return "100.00 200.00" if self.uptime_reads == 1 else "102.00 202.00"
        if command == ("shell", "cat", "/proc/sys/kernel/random/boot_id"):
            return self.after_boot_id if self.after else self.boot_id
        value = self.answers[command]
        if isinstance(value, BaseException):
            raise value
        return value


class PhysicalBaselineRecorderTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        (self.source / "seed.kt").write_text("// owned source\n")
        self.source_hash = digest((self.source / "seed.kt").read_bytes())
        self.commit = "1" * 40
        self.app = self.root / "app.apk"
        self.test = self.root / "test.apk"
        self.app.write_bytes(b"owned qualification application")
        self.test.write_bytes(b"owned qualification test")
        self.hashes = {"app": digest(self.app.read_bytes()), "test": digest(self.test.read_bytes())}
        self.source_record = {"schemaVersion": 1, "sourceCommit": self.commit,
                              "files": {"seed.kt": self.source_hash}, "artifacts": self.hashes.copy()}
        self.manifest = self.root / "source.json"
        self.save_manifest()
        self.output = self.root / "evidence"
        self.adb = FakeAdb(self.hashes)
        patch = mock.patch.object(BASELINE.PREFLIGHT, "git", side_effect=self.git)
        patch.start()
        self.addCleanup(patch.stop)

    def git(self, root, *args):
        self.assertEqual(self.source.resolve(), Path(root).resolve())
        if args == ("rev-parse", "--show-toplevel"):
            return str(self.source) + "\n"
        if args == ("rev-parse", "HEAD"):
            return self.commit + "\n"
        if args == ("ls-files", "-z"):
            return "seed.kt\0"
        if args == ("status", "--porcelain=v1", "-z", "--untracked-files=all"):
            clean = {p.name for p in self.source.iterdir()} == {"seed.kt"} and digest((self.source / "seed.kt").read_bytes()) == self.source_hash
            return "" if clean else "?? unreviewed\0"
        raise AssertionError("Unexpected source identity command.")

    def save_manifest(self):
        self.manifest.write_text(json.dumps(self.source_record))

    def record(self, serial="ORION_EXPLICIT_SERIAL", target="S21"):
        return BASELINE.record(self.adb, serial, target, self.source, self.manifest,
                               self.app, self.test, self.output, samples=3, interval=100)

    def parse(self, output):
        return BASELINE.receipts(output, 3, 100, window(100_000), window(102_000))

    def assert_rejected(self, code=None):
        report = self.record()
        self.assertEqual("REJECTED", report["result"])
        if code is not None:
            self.assertEqual(code, report["rejectionCode"])
        self.assertEqual("NOT_QUALIFIED", report["qualificationResult"])
        self.assertFalse(report["fullF5GateClosed"])
        self.assertFalse(report["fullSoakGateClosed"])
        return report

    def test_consistent_real_labelled_receipts_are_only_measurement_not_qualification(self):
        report = self.record()
        self.assertEqual("MEASUREMENT_ONLY", report["result"])
        self.assertEqual("NOT_QUALIFIED", report["qualificationResult"])
        self.assertTrue(report["ownedMeasurementProcessStartRequested"])
        self.assertTrue(report["ownedMeasurementProcessStarted"])
        self.assertTrue(report["instrumentationCommandIssued"])
        self.assertTrue(report["instrumentationExecuted"])
        self.assertTrue(report["possibleTechnicalBootstrapWrites"])
        self.assertEqual(BASELINE.PACKAGE, report["targetPackage"])
        for key in ["physicalQualification", "profileQualified", "productionInferenceEnabled", "fullF5GateClosed",
                    "fullSoakGateClosed", "cortexRuntimeReadyDeclared", "modelInputsUsed", "heavyWorkIssued", "installPerformed",
                    "deviceSettingsChanged", "productionDataAccessRequested"]:
            self.assertFalse(report[key])
        self.assertNotIn("deviceMutated", report)
        self.assertNotIn("deviceConfigurationMutated", report)
        self.assertEqual(self.hashes, report["artifactSha256"])
        self.assertEqual(3, len(report["samples"]))
        self.assertEqual(1, sum(command[:3] == ("shell", "am", "instrument") for command in self.adb.commands))
        encoded = (self.output / "f5-physical-baseline.json").read_text()
        self.assertNotIn("ORION_EXPLICIT_SERIAL", encoded)
        self.assertNotIn("ORION_PRIVATE_DEVICE_SERIAL", encoded)
        self.assertNotIn(self.adb.boot_id, encoded)
        self.assertTrue(all(BASELINE.allowed_command(command) for command in self.adb.commands))

    def test_emulator_serial_is_rejected_before_source_or_device_calls(self):
        self.manifest.unlink()
        report = self.record(serial="emulator-5554")
        self.assertEqual("EXPLICIT_PHYSICAL_SERIAL_REQUIRED", report["rejectionCode"])
        self.assertEqual([], self.adb.commands)
        self.assertTrue((self.output / "f5-physical-baseline.json").is_file())

    def test_qemu_signal_is_rejected_without_instrumentation(self):
        self.adb.answers[("shell", "getprop", "ro.kernel.qemu")] = "1"
        self.assert_rejected("PHYSICAL_SIGNALS_UNAVAILABLE_OR_EMULATED")
        self.assertFalse(self.adb.after)

    def test_fake_device_label_is_rejected(self):
        self.adb.answers[("shell", "getprop", "ro.hardware")] = "synthetic_board"
        self.assert_rejected("SYNTHETIC_DEVICE_LABEL")
        self.assertFalse(self.adb.after)

    def test_wrong_s21_identity_is_rejected(self):
        self.adb.answers[("shell", "getprop", "ro.product.model")] = "SM-G990B"
        self.assert_rejected("TARGET_MODEL_MISMATCH")

    def test_missing_or_partial_source_freeze_cannot_start_a_measurement(self):
        self.source_record["files"] = {}
        self.save_manifest()
        self.assert_rejected("SOURCE_COVERAGE_MISMATCH")
        self.assertEqual([], self.adb.commands)

    def test_duplicate_source_manifest_fields_are_rejected(self):
        self.manifest.write_text(self.manifest.read_text().replace('"schemaVersion": 1', '"schemaVersion": 1, "schemaVersion": 1'))
        self.assert_rejected("DUPLICATE_MANIFEST_FIELD")
        self.assertEqual([], self.adb.commands)

    def test_default_preflight_still_requires_model_and_baseline_requires_exact_two_artifacts(self):
        with self.assertRaisesRegex(BASELINE.Rejected, "INVALID_ARTIFACT_MANIFEST"):
            BASELINE.PREFLIGHT.source_identity(self.source, self.manifest)
        self.source_record["artifacts"]["model"] = "2" * 64
        self.save_manifest()
        _, default = BASELINE.PREFLIGHT.source_identity(self.source, self.manifest)
        self.assertEqual({"app", "test", "model"}, set(default))
        with self.assertRaisesRegex(BASELINE.Rejected, "INVALID_ARTIFACT_MANIFEST"):
            BASELINE.PREFLIGHT.source_identity(self.source, self.manifest, required_artifacts={"app", "test"})

    def test_empty_unknown_or_partial_artifact_modes_are_never_accepted(self):
        for keys in [set(), {"app"}, {"test"}, {"app", "test", "other"}, ["app", "test"]]:
            with self.subTest(keys=keys), self.assertRaisesRegex(BASELINE.Rejected, "INVALID_REQUIRED_ARTIFACTS"):
                BASELINE.PREFLIGHT.source_identity(self.source, self.manifest, required_artifacts=keys)

    def test_installed_apk_mismatch_prevents_instrumentation(self):
        path = "/data/app/~~baseline/" + BASELINE.PACKAGE + "/base.apk"
        self.adb.answers[("shell", "sha256sum", path)] = "0" * 64 + "  " + path
        self.assert_rejected("INSTALLED_APK_MISMATCH")
        self.assertFalse(self.adb.after)

    def test_split_apk_path_is_rejected_without_followup_hash(self):
        self.adb.answers[("shell", "pm", "path", BASELINE.PACKAGE)] = "package:/data/app/app/base.apk\npackage:/data/app/app/split.apk"
        self.assert_rejected("UNSAFE_OR_SPLIT_APK_PATH")
        self.assertEqual(("shell", "pm", "path", BASELINE.PACKAGE), self.adb.commands[-1])

    def test_source_modified_during_measurement_is_rejected(self):
        self.adb.on_instrument = lambda: (self.source / "seed.kt").write_text("// unreviewed replacement\n")
        self.assert_rejected("SOURCE_TREE_DIRTY")

    def test_local_artifact_modified_during_measurement_is_rejected(self):
        self.adb.on_instrument = lambda: self.app.write_bytes(b"changed APK")
        self.assert_rejected("SOURCE_OR_LOCAL_ARTIFACT_CHANGED")

    def test_crossboot_measurement_is_rejected(self):
        self.adb.after_boot_id = "00000000-0000-4000-8000-000000000002"
        self.assert_rejected("BOOT_OR_MEMORY_IDENTITY_CHANGED")

    def test_valid_partial_runner_failure_stays_incomplete_and_not_qualified(self):
        self.adb.output = receipt_output([sample(0)], summary(collected=1, result="INCOMPLETE", failure="OBSERVATION_TIMEOUT"), 0)
        report = self.record()
        self.assertEqual("INCOMPLETE", report["result"])
        self.assertEqual("NOT_QUALIFIED", report["qualificationResult"])
        self.assertFalse(report["profileQualified"])
        self.assertFalse(report["fullF5GateClosed"])

    def test_cleanup_not_registered_is_typed_failure_and_never_qualification(self):
        self.adb.output = receipt_output([sample(0)], summary(collected=1, result="INCOMPLETE", failure="CLEANUP_NOT_REGISTERED"), 0)
        report = self.record()
        self.assertEqual("INCOMPLETE", report["result"])
        self.assertEqual("CLEANUP_NOT_REGISTERED", report["summary"]["failureCode"])
        self.assertEqual("NOT_QUALIFIED", report["qualificationResult"])
        self.assertFalse(report["profileQualified"])
        self.assertFalse(report["fullF5GateClosed"])
        self.assertFalse(report["fullSoakGateClosed"])

    def test_unverified_runner_rejection_cannot_be_relabelled_physical(self):
        final = summary(collected=0, result="REJECTED", failure="PHYSICAL_ENVIRONMENT_REJECTED")
        final["environment"] = "UNVERIFIED"
        self.adb.output = receipt_output([], final, 0)
        report = self.assert_rejected("SUMMARY_IDENTITY_INVALID")
        self.assertNotIn("measurementEnvironment", report)
        self.assertNotIn("samples", report)
        self.assertNotIn("summary", report)
        self.assertEqual("HOST", report["environment"])
        self.assertFalse(report["physicalQualification"])

    def test_rejected_runner_result_is_preserved(self):
        self.adb.output = receipt_output([], summary(collected=0, result="REJECTED", failure="BIND_FAILED"), 0)
        report = self.record()
        self.assertEqual("REJECTED", report["result"])
        self.assertTrue(report["instrumentationExecuted"])
        self.assertTrue(report["ownedMeasurementProcessStarted"])

    def test_metrics_missing_surplus_synthetic_or_nonsensical_are_denied(self):
        mutations = [{"environment": "ANDROID_EMULATOR"}, {"kind": "SYNTHETIC_SAMPLE"}, {"qualification": "QUALIFIED"},
                     {"nativeRuntimeLoaded": True}, {"serviceState": "INFERENCING"}, {"limitations": ["PARTIAL_INVENTORY"]},
                     {"inventoryProcessCount": 3}, {"mainPssBytes": 0}, {"aiPssBytes": True}, {"aggregatePssBytes": 1},
                     {"systemAvailableMemoryBytes": 2**63}, {"batteryPercent": 101}, {"thermalStatus": 7},
                     {"powerSave": "false"}, {"foreground": 0}, {"importance": "UNKNOWN"}, {"memoryPressure": "UNKNOWN"},
                     {"mainPid": 456}, {"mainPid": 0}, {"bootCount": -1}, {"schemaVersion": True},
                     {"maximumAgeMillis": 5000}, {"sampleIndex": 1}, {"sampleCount": 4}, {"extra": "private payload"}]
        for changes in mutations:
            with self.subTest(changes=changes), self.assertRaises(BASELINE.Rejected):
                rows = [sample(i) for i in range(3)]
                rows[0].update(changes)
                self.parse(receipt_output(rows))
        missing = sample(0)
        del missing["mainPssBytes"]
        with self.assertRaises(BASELINE.Rejected):
            self.parse(receipt_output([missing, sample(1), sample(2)]))

    def test_stale_future_regressed_and_crossprocess_receipts_are_denied(self):
        cases = []
        stale = [sample(i) for i in range(3)]
        stale[0]["completedElapsed"] = stale[0]["sampledElapsed"] + 1000
        cases.append(stale)
        old = [sample(i) for i in range(3)]
        old[0].update(sampledElapsed=90_000, aiSampledElapsed=90_005, completedElapsed=90_010)
        cases.append(old)
        future = [sample(i) for i in range(3)]
        future[2].update(sampledElapsed=110_000, aiSampledElapsed=110_005, completedElapsed=110_010)
        cases.append(future)
        for changes in [{"bootCount": 8}, {"mainPid": 124}, {"aiPid": 457}, {"sampledElapsed": 100_105}, {"aiSampledElapsed": 1}]:
            rows = [sample(i) for i in range(3)]
            rows[1].update(changes)
            cases.append(rows)
        for rows in cases:
            with self.subTest(rows=rows), self.assertRaises(BASELINE.Rejected):
                self.parse(receipt_output(rows))

    def test_missing_duplicate_surplus_and_unordered_frames_are_denied(self):
        good = receipt_output()
        for output in [receipt_output([sample(0), sample(1)]), receipt_output([sample(0), sample(0), sample(2)]),
                       receipt_output([sample(i) for i in range(4)]), good + "\nINSTRUMENTATION_CODE: -1",
                       good.replace("INSTRUMENTATION_STATUS_CODE: 2\n", "", 1),
                       good.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
                       good + "\nOK (3 tests)"]:
            with self.subTest(output=output), self.assertRaises(BASELINE.Rejected):
                self.parse(output)

    def test_summary_cannot_promote_qualification_or_hide_missing_samples(self):
        changes = [{key: True} for key in ["profileQualified", "productionInferenceEnabled", "fullF5GateClosed", "fullSoakGateClosed", "heavyWorkIssued"]]
        changes += [{"samplesCollected": 1}, {"samplesRequested": True}, {"sampleIntervalMillis": 250},
                    {"result": "PASS"}, {"environment": "HOST"}, {"extra": False}, {"failureCode": "OBSERVATION_FAILED"}]
        for mutation in changes:
            with self.subTest(mutation=mutation), self.assertRaises(BASELINE.Rejected):
                final = summary()
                final.update(mutation)
                self.parse(receipt_output(final=final))

    def test_duplicate_json_fields_and_nonfinite_numbers_are_denied(self):
        for output in [receipt_output().replace('"schemaVersion": 1', '"schemaVersion": 1, "schemaVersion": 1', 1),
                       receipt_output().replace('"batteryPercent": 75', '"batteryPercent": NaN', 1),
                       receipt_output().replace('"batteryPercent": 75', '"batteryPercent": Infinity', 1)]:
            with self.subTest(output=output), self.assertRaises(BASELINE.Rejected):
                self.parse(output)

    def test_result_bundle_key_order_and_multiline_stream_do_not_change_the_schema(self):
        lines = receipt_output().splitlines()
        lines[-3], lines[-2] = lines[-2], lines[-3]
        self.assertTrue(self.parse("\n".join(lines))["complete"])
        self.assertTrue(self.parse(receipt_output().replace("stream=" + BASELINE.STREAM, "stream=\n\n" + BASELINE.STREAM + "\n"))["complete"])

    def test_invalid_receipt_or_observation_failure_does_not_persist_raw_payload(self):
        self.adb.output = receipt_output().replace('"kind": "PHYSICAL_BASELINE_SAMPLE"', '"kind": "PRIVATE_SECRET_PAYLOAD"', 1)
        report = self.assert_rejected("SAMPLE_IDENTITY_INVALID")
        self.assertTrue(report["instrumentationCommandIssued"])
        self.assertIsNone(report["instrumentationExecuted"])
        self.assertIsNone(report["ownedMeasurementProcessStarted"])
        self.assertNotIn("PRIVATE_SECRET_PAYLOAD", (self.output / "f5-physical-baseline.json").read_text())

    def test_unknown_or_mutating_commands_never_reach_subprocess(self):
        adb = BASELINE.BaselineAdb("owned-adb", "EXPLICIT_SERIAL")
        rejected = [("install", "owned.apk"), ("push", "owned", "owned"), ("shell", "settings", "put", "global", "x", "1"),
                    ("shell", "settings", "get", "global", "boot_count"), ("shell", "pm", "grant", BASELINE.PACKAGE, "permission"),
                    ("shell", "am", "force-stop", BASELINE.PACKAGE), ("shell", "am", "start", "owned"),
                    tuple(BASELINE.instrumentation_command(3, 100)[:-1] + ["network.orion.test/OtherRunner"])]
        with mock.patch.object(BASELINE.subprocess, "run") as execute:
            for command in rejected:
                with self.subTest(command=command), self.assertRaises(BASELINE.Rejected):
                    adb(*command)
            execute.assert_not_called()

    def test_exact_runner_args_and_sampling_bounds(self):
        command = BASELINE.instrumentation_command(3, 100)
        self.assertEqual(BASELINE.RUNNER, command[-1])
        self.assertEqual("PHYSICAL_DEVICE", command[7])
        self.assertTrue(BASELINE.allowed_command(tuple(command)))
        for count, interval in [(0, 100), (11, 100), (True, 100), (3, 99), (3, 1001), (3, 100.0)]:
            with self.subTest(count=count, interval=interval), self.assertRaises(BASELINE.Rejected):
                BASELINE.instrumentation_command(count, interval)

    def test_existing_output_is_never_overwritten(self):
        self.output.mkdir()
        original = self.output / "f5-physical-baseline.json"
        original.write_text("retained attempt")
        with self.assertRaises(FileExistsError):
            self.record()
        self.assertEqual("retained attempt", original.read_text())
        self.assertEqual([], self.adb.commands)

    def test_real_adb_adapter_uses_explicit_serial_and_bounded_instrumentation_timeout(self):
        def execute(command, stdout, **kwargs):
            stdout.write(receipt_output().encode())
            return mock.Mock(returncode=0)
        with mock.patch.object(BASELINE.subprocess, "run", side_effect=execute) as run:
            value = BASELINE.BaselineAdb("owned-adb", "EXPLICIT_SERIAL")(*BASELINE.instrumentation_command(3, 100))
        self.assertEqual(receipt_output(), value)
        self.assertEqual(["owned-adb", "-s", "EXPLICIT_SERIAL"], run.call_args.args[0][:3])
        self.assertEqual(45, run.call_args.kwargs["timeout"])
        self.assertEqual(subprocess.DEVNULL, run.call_args.kwargs["stderr"])


if __name__ == "__main__":
    unittest.main()
