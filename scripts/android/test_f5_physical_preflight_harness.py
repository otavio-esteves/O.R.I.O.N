#!/usr/bin/env python3
"""HOST contract fixtures only; no actual device, installation, inference or qualification."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SCRIPT = Path(__file__).with_name("prepare-f5-physical-qualification.py")
SPEC = importlib.util.spec_from_file_location("f5_physical_preflight", SCRIPT)
PREFLIGHT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREFLIGHT)
REAL_GIT = PREFLIGHT.git


def digest(value):
    return hashlib.sha256(value).hexdigest()


class FakeAdb:
    def __init__(self, hashes):
        self.commands = []
        self.answers = {
            ("get-state",): "device",
            ("shell", "getprop", "ro.kernel.qemu"): "",
            ("shell", "getprop", "ro.boot.qemu"): "",
            ("shell", "getprop", "ro.build.fingerprint"): "samsung/o1sxxx/o1s:15/AP3A.240905.015.A2/G991BXX:user/release-keys",
            ("shell", "getprop", "ro.hardware"): "exynos2100",
            ("shell", "getprop", "ro.build.version.sdk"): "35",
            ("shell", "getprop", "ro.product.cpu.abi"): "arm64-v8a",
            ("shell", "getconf", "PAGE_SIZE"): "4096",
            ("shell", "getprop", "ro.product.manufacturer"): "samsung",
            ("shell", "getprop", "ro.product.model"): "SM-G991B",
            ("shell", "getprop", "ro.serialno"): "ORION_PRIVATE_FIXTURE_SERIAL",
            ("shell", "run-as", "network.orion", "stat", "-c", "%F", "files/candidate.gguf"): "regular file",
            ("shell", "run-as", "network.orion", "sha256sum", "files/candidate.gguf"): hashes["model"] + "  files/candidate.gguf",
        }
        for key, package in PREFLIGHT.PACKAGES.items():
            path = "/data/app/~~orion_fixture/" + package + "/base.apk"
            self.answers[("shell", "pm", "path", package)] = "package:" + path
            self.answers[("shell", "sha256sum", path)] = hashes[key] + "  " + path

    def __call__(self, *command):
        self.commands.append(command)
        if command not in self.answers:
            raise AssertionError("Unexpected or mutating ADB command: " + repr(command))
        value = self.answers[command]
        if isinstance(value, BaseException):
            raise value
        return value


class PhysicalPreflightTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        (self.source / "seed.kt").write_text("// owned source fixture\n")
        self.commit = "1" * 40
        self.source_hash = digest((self.source / "seed.kt").read_bytes())
        git_fixture = mock.patch.object(PREFLIGHT, "git", side_effect=self.git)
        git_fixture.start()
        self.addCleanup(git_fixture.stop)
        self.app = self.root / "app.apk"
        self.test = self.root / "test.apk"
        self.model = self.root / "candidate.gguf"
        for path, content in [(self.app, b"owned app fixture"), (self.test, b"owned test fixture"), (self.model, b"owned model fixture")]:
            path.write_bytes(content)
        self.hashes = {"app": digest(self.app.read_bytes()), "test": digest(self.test.read_bytes()), "model": digest(self.model.read_bytes())}
        self.source_manifest = self.root / "source.json"
        self.source_record = {"schemaVersion": 1, "sourceCommit": self.commit,
                              "files": {"seed.kt": digest((self.source / "seed.kt").read_bytes())}, "artifacts": self.hashes}
        self.model_manifest = self.root / "model.json"
        self.model_record = {"schemaVersion": 1, "purpose": "PHYSICAL_CANDIDATE_NOT_QUALIFIED", "publisher": "OwnedCandidate",
                             "repository": "orion/candidate", "revision": "1" * 40, "filename": "candidate.gguf",
                             "sizeBytes": self.model.stat().st_size, "sha256": self.hashes["model"], "license": "Apache-2.0",
                             "url": "https://example.invalid/candidate.gguf", "licenseSha256": "2" * 64}
        self.save_manifests()
        self.adb = FakeAdb(self.hashes)
        self.output = self.root / "evidence"

    def git(self, root, *args):
        self.assertEqual(self.source.resolve(), Path(root).resolve())
        if args == ("rev-parse", "--show-toplevel"):
            return str(self.source) + "\n"
        if args == ("rev-parse", "HEAD"):
            return self.commit + "\n"
        if args == ("status", "--porcelain=v1", "-z", "--untracked-files=all"):
            clean = {p.name for p in self.source.iterdir()} == {"seed.kt"} and digest((self.source / "seed.kt").read_bytes()) == self.source_hash
            return "" if clean else "?? unreviewed\0"
        if args == ("ls-files", "-z"):
            return "seed.kt\0"
        raise AssertionError("Unexpected Git fixture command.")

    def save_manifests(self):
        self.source_manifest.write_text(json.dumps(self.source_record))
        self.model_manifest.write_text(json.dumps(self.model_record))

    def prepare(self, serial="ORION_EXPLICIT_SERIAL", target="S21"):
        return PREFLIGHT.prepare(self.adb, serial, target, self.source, self.source_manifest,
                                 self.app, self.test, self.model, self.model_manifest, self.output)

    def assert_rejected(self, code):
        report = self.prepare()
        self.assertEqual("REJECTED", report["result"])
        self.assertEqual(code, report["rejectionCode"])
        self.assertFalse(report["physicalQualification"])
        self.assertFalse(report["fullF5GateClosed"])
        return report

    def test_consistent_inputs_only_generate_blocked_unimplemented_plan(self):
        report = self.prepare()
        self.assertEqual("BLOCKED", report["result"])
        self.assertEqual("VERIFIED", report["preflightIntegrity"])
        self.assertEqual("NOT_EVALUATED", report["qualificationResult"])
        for key in ["physicalQualification", "profileQualified", "fullF5GateClosed", "fullSoakGateClosed", "cortexRuntimeReadyDeclared", "deviceMutated"]:
            self.assertFalse(report[key])
        self.assertTrue(all(item["status"] == "NOT_IMPLEMENTED" and item["command"] is None for item in report["executionPlan"]))
        encoded = (self.output / "f5-physical-preflight.json").read_text()
        self.assertNotIn("ORION_EXPLICIT_SERIAL", encoded)
        self.assertNotIn("ORION_PRIVATE_FIXTURE_SERIAL", encoded)
        self.assertNotIn(self.adb.answers[("shell", "getprop", "ro.build.fingerprint")], encoded)
        self.assertEqual(self.hashes, report["artifactSha256"])
        self.assertTrue(self.adb.commands)

    def test_emulator_serial_is_rejected_before_any_adb_command(self):
        self.assertEqual("EXPLICIT_PHYSICAL_SERIAL_REQUIRED", self.prepare(serial="emulator-5554")["rejectionCode"])
        self.assertEqual([], self.adb.commands)

    def test_qemu_device_is_rejected(self):
        self.adb.answers[("shell", "getprop", "ro.kernel.qemu")] = "1"
        self.assert_rejected("PHYSICAL_SIGNALS_UNAVAILABLE_OR_EMULATED")

    def test_emulator_fingerprint_is_rejected_even_without_qemu_property(self):
        self.adb.answers[("shell", "getprop", "ro.build.fingerprint")] = "google/sdk_gphone64_x86_64/emu64x:15/A/B:userdebug/test-keys"
        self.assert_rejected("PHYSICAL_SIGNALS_UNAVAILABLE_OR_EMULATED")

    def test_unknown_qemu_property_is_not_assumed_physical(self):
        self.adb.answers[("shell", "getprop", "ro.boot.qemu")] = "unknown"
        self.assert_rejected("PHYSICAL_SIGNALS_UNAVAILABLE_OR_EMULATED")

    def test_offline_or_ambiguous_device_state_is_rejected(self):
        self.adb.answers[("get-state",)] = "offline or multiple matches"
        self.assert_rejected("DEVICE_OFFLINE_OR_AMBIGUOUS")

    def test_wrong_s21_model_is_rejected(self):
        self.adb.answers[("shell", "getprop", "ro.product.model")] = "SM-G990B"
        self.assert_rejected("TARGET_MODEL_MISMATCH")

    def test_api37_role_requires_actual_api37(self):
        report = self.prepare(target="API37")
        self.assertEqual("TARGET_API_MISMATCH", report["rejectionCode"])

    def test_physical_api37_preflight_remains_blocked(self):
        self.adb.answers[("shell", "getprop", "ro.build.version.sdk")] = "37"
        self.adb.answers[("shell", "getconf", "PAGE_SIZE")] = "16384"
        report = self.prepare(target="API37")
        self.assertEqual("BLOCKED", report["result"])
        self.assertEqual(37, report["target"]["api"])

    def test_installed_apk_mismatch_is_rejected(self):
        self.adb.answers[("shell", "sha256sum", "/data/app/~~orion_fixture/network.orion/base.apk")] = "0" * 64 + "  /data/app/~~orion_fixture/network.orion/base.apk"
        self.assert_rejected("INSTALLED_APK_MISMATCH")

    def test_split_or_injected_apk_path_is_rejected_without_followup(self):
        self.adb.answers[("shell", "pm", "path", "network.orion")] = "package:/data/app/app/base.apk;am start owned"
        self.assert_rejected("UNSAFE_OR_SPLIT_APK_PATH")
        self.assertEqual(("shell", "pm", "path", "network.orion"), self.adb.commands[-1])

    def test_source_commit_mismatch_prevents_device_reads(self):
        self.source_record["sourceCommit"] = "0" * 40
        self.save_manifests()
        self.assert_rejected("SOURCE_COMMIT_MISMATCH")
        self.assertEqual([], self.adb.commands)

    def test_duplicate_manifest_fields_are_rejected(self):
        self.source_manifest.write_text(json.dumps(self.source_record).replace('"schemaVersion": 1', '"schemaVersion": 1, "schemaVersion": 1'))
        self.assert_rejected("DUPLICATE_MANIFEST_FIELD")
        self.assertEqual([], self.adb.commands)

    def test_oversized_manifest_is_rejected_before_decoding(self):
        self.source_manifest.write_bytes(b"x" * (1024 * 1024 + 1))
        self.assert_rejected("MANIFEST_TOO_LARGE_OR_UNSAFE")
        self.assertEqual([], self.adb.commands)

    def test_source_hash_mismatch_prevents_device_reads(self):
        self.source_record["files"]["seed.kt"] = "0" * 64
        self.save_manifests()
        self.assert_rejected("SOURCE_HASH_MISMATCH")
        self.assertEqual([], self.adb.commands)

    def test_missing_tracked_file_is_not_complete_source_freeze(self):
        self.source_record["files"] = {}
        self.save_manifests()
        self.assert_rejected("SOURCE_COVERAGE_MISMATCH")

    def test_dirty_untracked_runtime_source_is_rejected(self):
        (self.source / "new.kt").write_text("// unreviewed\n")
        self.assert_rejected("SOURCE_TREE_DIRTY")

    def test_local_artifact_mismatch_prevents_device_reads(self):
        self.app.write_bytes(b"replacement app")
        self.assert_rejected("ARTIFACT_PROVENANCE_MISMATCH")
        self.assertEqual([], self.adb.commands)

    def test_model_hash_mismatch_prevents_device_reads(self):
        self.model_record["sha256"] = "0" * 64
        self.save_manifests()
        self.assert_rejected("MODEL_HASH_OR_SIZE_MISMATCH")
        self.assertEqual([], self.adb.commands)

    def test_emulator_fixture_manifest_cannot_be_relabelled_as_physical_evidence(self):
        self.model_record["purpose"] = "ANDROID_EMULATOR integration fixture only; NOT QUALIFIED for production"
        self.save_manifests()
        self.assert_rejected("SYNTHETIC_OR_UNCLASSIFIED_MODEL")

    def test_synthetic_marker_in_candidate_provenance_is_rejected(self):
        self.model_record["publisher"] = "Synthetic Fixture"
        self.save_manifests()
        self.assert_rejected("SYNTHETIC_OR_UNCLASSIFIED_MODEL")

    def test_operator_qualified_boolean_is_not_a_profile_approval(self):
        self.model_record["qualified"] = True
        self.save_manifests()
        self.assert_rejected("INVALID_MODEL_MANIFEST")

    def test_private_model_symlink_or_hash_mismatch_is_rejected(self):
        self.adb.answers[("shell", "run-as", "network.orion", "stat", "-c", "%F", "files/candidate.gguf")] = "symbolic link"
        self.assert_rejected("DEVICE_MODEL_NOT_REGULAR")

    def test_installed_model_hash_mismatch_is_rejected(self):
        self.adb.answers[("shell", "run-as", "network.orion", "sha256sum", "files/candidate.gguf")] = "0" * 64 + "  files/candidate.gguf"
        self.assert_rejected("DEVICE_MODEL_HASH_MISMATCH")

    def test_split_apk_install_is_rejected(self):
        self.adb.answers[("shell", "pm", "path", "network.orion")] = "package:/data/app/app/base.apk\npackage:/data/app/app/split.apk"
        self.assert_rejected("UNSAFE_OR_SPLIT_APK_PATH")

    def test_unsafe_model_filename_cannot_become_a_device_command(self):
        self.model_record["filename"] = "../candidate.gguf"
        self.save_manifests()
        self.assert_rejected("UNSAFE_MODEL_FILENAME")
        self.assertEqual([], self.adb.commands)

    def test_interruption_retains_rejected_plan_without_qualification(self):
        self.adb.answers[("get-state",)] = KeyboardInterrupt()
        self.assert_rejected("INTERRUPTED")
        self.assertTrue((self.output / "f5-physical-preflight.json").is_file())

    def test_raw_observation_errors_are_not_persisted(self):
        self.adb.answers[("get-state",)] = RuntimeError("private raw failure must not be recorded")
        self.assert_rejected("INPUT_OR_TECHNICAL_OBSERVATION_UNAVAILABLE")
        self.assertNotIn("private raw failure", (self.output / "f5-physical-preflight.json").read_text())

    def test_existing_output_is_never_overwritten(self):
        self.output.mkdir()
        sentinel = self.output / "f5-physical-preflight.json"
        sentinel.write_text("original attempt")
        with self.assertRaises(FileExistsError):
            self.prepare()
        self.assertEqual("original attempt", sentinel.read_text())
        self.assertEqual([], self.adb.commands)

    def test_real_git_adapter_bounds_output_and_uses_explicit_source_root(self):
        with mock.patch.object(PREFLIGHT.subprocess, "run", return_value=mock.Mock(returncode=0, stdout=b"owned\n")) as run:
            self.assertEqual("owned\n", REAL_GIT(self.source, "rev-parse", "HEAD"))
        self.assertEqual(["git", "-C", str(self.source), "rev-parse", "HEAD"], run.call_args.args[0])
        self.assertEqual(10, run.call_args.kwargs["timeout"])

    def test_real_git_adapter_rejects_failed_or_unbounded_output(self):
        for result in [mock.Mock(returncode=1, stdout=b""), mock.Mock(returncode=0, stdout=b"x" * (1024 * 1024 + 1))]:
            with mock.patch.object(PREFLIGHT.subprocess, "run", return_value=result):
                with self.assertRaisesRegex(PREFLIGHT.Rejected, "SOURCE_IDENTITY_UNAVAILABLE"):
                    REAL_GIT(self.source, "status")

    def test_cli_uses_explicit_serial_and_returns_non_success_for_blocked_plan(self):
        binaries = self.root / "bin"
        binaries.mkdir()
        git = binaries / "git"
        git.write_text("#!/usr/bin/env python3\nimport hashlib,sys\nfrom pathlib import Path\n" +
                       "root=Path(" + repr(str(self.source)) + ")\n" +
                       "assert sys.argv[1:3] == ['-C',str(root)]\nargs=sys.argv[3:]\n" +
                       "if args == ['rev-parse','--show-toplevel']: print(root)\n" +
                       "elif args == ['rev-parse','HEAD']: print(" + repr(self.commit) + ")\n" +
                       "elif args == ['status','--porcelain=v1','-z','--untracked-files=all']:\n" +
                       " clean={p.name for p in root.iterdir()} == {'seed.kt'} and hashlib.sha256((root/'seed.kt').read_bytes()).hexdigest() == " + repr(self.source_hash) + "\n" +
                       " sys.stdout.write('' if clean else '?? unreviewed\\0')\n" +
                       "elif args == ['ls-files','-z']: sys.stdout.write('seed.kt\\0')\nelse: sys.exit(1)\n")
        git.chmod(0o755)
        fake = self.root / "fake_adb.py"
        records = [{"command": list(command), "reply": reply} for command, reply in self.adb.answers.items()]
        fake.write_text("#!/usr/bin/env python3\nimport json,sys\n" +
                        "records=json.loads(" + repr(json.dumps(records)) + ")\n" +
                        "assert sys.argv[1:3] == ['-s','ORION_EXPLICIT_SERIAL']\n" +
                        "for row in records:\n if row['command'] == sys.argv[3:]:\n  print(row['reply']);sys.exit(0)\n" +
                        "sys.exit(1)\n")
        fake.chmod(0o755)
        result = subprocess.run([sys.executable, str(SCRIPT), "--adb", str(fake), "--serial", "ORION_EXPLICIT_SERIAL",
                                 "--target", "S21", "--source-root", str(self.source), "--source-manifest", str(self.source_manifest),
                                 "--app-apk", str(self.app), "--test-apk", str(self.test), "--model-file", str(self.model),
                                 "--model-manifest", str(self.model_manifest), "--evidence-dir", str(self.output)],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=20,
                                env=dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ.get("PATH", "")))
        self.assertEqual(3, result.returncode, result.stderr)
        self.assertIn("BLOCKED", result.stdout)
        self.assertEqual("BLOCKED", json.loads((self.output / "f5-physical-preflight.json").read_text())["result"])


if __name__ == "__main__":
    unittest.main()
