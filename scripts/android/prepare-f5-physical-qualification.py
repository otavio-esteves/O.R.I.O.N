#!/usr/bin/env python3
"""HOST read-only F5 integrity preflight. A verified plan is still BLOCKED, never qualification."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import stat
import subprocess

SPEC = importlib.util.spec_from_file_location("f5_passive_observer", Path(__file__).with_name("record-soak-observation.py"))
OBS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(OBS)
SHA = re.compile(r"[0-9a-f]{64}")
SERIAL = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
EMULATOR = re.compile(r"emulator|qemu|ranchu|goldfish|cuttlefish|sdk_gphone|generic|aosp_cf", re.I)
PACKAGES = {"app": "network.orion", "test": "network.orion.test"}


class Rejected(ValueError):
    pass


def require(condition, code):
    if not condition:
        raise Rejected(code)


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "DUPLICATE_MANIFEST_FIELD")
        result[key] = value
    return result


def document(path):
    path = Path(path)
    metadata = path.lstat()
    require(stat.S_ISREG(metadata.st_mode) and 0 < metadata.st_size <= 1024 * 1024, "MANIFEST_TOO_LARGE_OR_UNSAFE")
    with path.open("rb") as stream:
        data = stream.read(1024 * 1024 + 1)
    require(len(data) <= 1024 * 1024, "MANIFEST_TOO_LARGE_OR_UNSAFE")
    result = json.loads(data.decode("utf-8"), object_pairs_hook=unique)
    require(type(result) is dict, "INVALID_MANIFEST")
    return result


def sha_file(path, maximum=4 * 1024**3):
    path = Path(path)
    metadata = path.lstat()
    require(stat.S_ISREG(metadata.st_mode) and 0 < metadata.st_size <= maximum, "UNSAFE_OR_EMPTY_FILE")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git(root, *args):
    result = subprocess.run(["git", "-C", str(root), *args], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=10, check=False)
    require(result.returncode == 0 and len(result.stdout) <= 1024 * 1024, "SOURCE_IDENTITY_UNAVAILABLE")
    return result.stdout.decode("utf-8", errors="strict")


def source_identity(root, manifest, required_artifacts=frozenset({"app", "test", "model"})):
    require(type(required_artifacts) in (set, frozenset) and required_artifacts in
            (frozenset({"app", "test", "model"}), frozenset({"app", "test"})), "INVALID_REQUIRED_ARTIFACTS")
    root = Path(root).resolve()
    value = document(manifest)
    require(set(value) == {"schemaVersion", "sourceCommit", "files", "artifacts"} and
            type(value["schemaVersion"]) is int and value["schemaVersion"] == 1, "INVALID_SOURCE_MANIFEST")
    require(type(value["sourceCommit"]) is str and re.fullmatch(r"[0-9a-f]{40}", value["sourceCommit"]), "INVALID_SOURCE_COMMIT")
    require(Path(git(root, "rev-parse", "--show-toplevel").strip()).resolve() == root, "SOURCE_ROOT_MISMATCH")
    require(git(root, "rev-parse", "HEAD").strip() == value["sourceCommit"], "SOURCE_COMMIT_MISMATCH")
    require(not git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all"), "SOURCE_TREE_DIRTY")
    tracked = set(filter(None, git(root, "ls-files", "-z").split("\0")))
    files = value["files"]
    require(type(files) is dict and 0 < len(files) <= 10_000 and set(files) == tracked, "SOURCE_COVERAGE_MISMATCH")
    for name, digest in files.items():
        path = Path(name)
        require(not path.is_absolute() and ".." not in path.parts and type(digest) is str and SHA.fullmatch(digest), "UNSAFE_SOURCE_MANIFEST")
        selected = root / path
        require(not selected.is_symlink() and selected.resolve().is_relative_to(root), "UNSAFE_SOURCE_MANIFEST")
        require(sha_file(selected, 256 * 1024**2) == digest, "SOURCE_HASH_MISMATCH")
    artifacts = value["artifacts"]
    require(type(artifacts) is dict and set(artifacts) == required_artifacts and
            all(type(v) is str and SHA.fullmatch(v) for v in artifacts.values()), "INVALID_ARTIFACT_MANIFEST")
    return {"commit": value["sourceCommit"], "manifestSha256": sha_file(manifest), "trackedFiles": len(files)}, artifacts


def model_identity(path, manifest):
    value = document(manifest)
    keys = {"schemaVersion", "purpose", "publisher", "repository", "revision", "filename", "sizeBytes", "sha256", "license", "url", "licenseSha256"}
    require(set(value) == keys and type(value["schemaVersion"]) is int and value["schemaVersion"] == 1, "INVALID_MODEL_MANIFEST")
    require(value["purpose"] == "PHYSICAL_CANDIDATE_NOT_QUALIFIED", "SYNTHETIC_OR_UNCLASSIFIED_MODEL")
    require(all(type(value[key]) is str and 0 < len(value[key]) <= 2048 for key in
                ("publisher", "repository", "revision", "license", "url", "licenseSha256")) and
            re.fullmatch(r"[0-9a-f]{40}", value["revision"]) and SHA.fullmatch(value["licenseSha256"]), "INVALID_MODEL_MANIFEST")
    require(not re.search(r"synthetic|fixture|example|test[_-]?only|emulator", value["publisher"] + " " + value["repository"] + " " + str(value["filename"]), re.I), "SYNTHETIC_OR_UNCLASSIFIED_MODEL")
    require(type(value["sha256"]) is str and SHA.fullmatch(value["sha256"]) and
            type(value["sizeBytes"]) is int and 0 < value["sizeBytes"] <= 4 * 1024**3, "INVALID_MODEL_MANIFEST")
    require(type(value["filename"]) is str and re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}\.gguf", value["filename"]) and
            ".." not in value["filename"], "UNSAFE_MODEL_FILENAME")
    require(Path(path).stat().st_size == value["sizeBytes"] and sha_file(path) == value["sha256"], "MODEL_HASH_OR_SIZE_MISMATCH")
    return {"sha256": value["sha256"], "sizeBytes": value["sizeBytes"], "manifestSha256": sha_file(manifest)}, value["filename"]


def installed_hash(adb, package):
    path = adb("shell", "pm", "path", package)
    require(re.fullmatch(r"package:/data/app/[A-Za-z0-9_./=+~\-]+/base\.apk", path), "UNSAFE_OR_SPLIT_APK_PATH")
    selected = path.removeprefix("package:")
    require(".." not in Path(selected).parts, "UNSAFE_OR_SPLIT_APK_PATH")
    digest = adb("shell", "sha256sum", selected)
    require(re.fullmatch(r"[0-9a-f]{64}\s+" + re.escape(selected), digest), "INSTALLED_APK_HASH_UNAVAILABLE")
    return digest[:64]


def physical_identity(adb, serial, target):
    require(type(serial) is str and SERIAL.fullmatch(serial) and not serial.startswith("emulator-"), "EXPLICIT_PHYSICAL_SERIAL_REQUIRED")
    require(adb("get-state") == "device", "DEVICE_OFFLINE_OR_AMBIGUOUS")
    qemu = adb("shell", "getprop", "ro.kernel.qemu")
    boot_qemu = adb("shell", "getprop", "ro.boot.qemu")
    fingerprint = adb("shell", "getprop", "ro.build.fingerprint")
    hardware = adb("shell", "getprop", "ro.hardware")
    require(qemu in ("", "0") and boot_qemu in ("", "0") and
            10 <= len(fingerprint) <= 512 and all(32 < ord(c) < 127 for c in fingerprint) and
            fingerprint.count("/") >= 4 and fingerprint.count(":") >= 2 and
            hardware and not EMULATOR.search(fingerprint + " " + hardware), "PHYSICAL_SIGNALS_UNAVAILABLE_OR_EMULATED")
    api_text = adb("shell", "getprop", "ro.build.version.sdk")
    require(re.fullmatch(r"[0-9]{2}", api_text), "API_UNAVAILABLE")
    api = int(api_text)
    require(31 <= api <= 37 and (target != "API37" or api == 37), "TARGET_API_MISMATCH")
    abi = adb("shell", "getprop", "ro.product.cpu.abi")
    pages = adb("shell", "getconf", "PAGE_SIZE")
    require(abi == "arm64-v8a" and pages in ("4096", "16384"), "TARGET_ABI_OR_PAGE_SIZE_UNSUPPORTED")
    maker = adb("shell", "getprop", "ro.product.manufacturer")
    model = adb("shell", "getprop", "ro.product.model")
    require(0 < len(maker) <= 80 and 0 < len(model) <= 80 and all(32 <= ord(c) < 127 for c in maker + model), "TARGET_MODEL_UNAVAILABLE")
    require(target in ("S21", "API37") and (target != "S21" or maker.lower() == "samsung" and re.fullmatch(r"SM-G991[A-Za-z0-9/-]*", model)), "TARGET_MODEL_MISMATCH")
    return {"deviceIdentitySha256": OBS.device_identity(adb), "adbSerialSha256": hashlib.sha256(serial.encode()).hexdigest(),
            "fingerprintSha256": hashlib.sha256(fingerprint.encode()).hexdigest(), "api": api, "abi": abi,
            "pageSizeBytes": int(pages), "manufacturer": maker, "model": model,
            "physicalSignals": "OBSERVED_NOT_TAMPER_PROOF_ATTESTATION", "ramQualification": "NOT_VERIFIED"}


def prepare(adb, serial, target, source_root, source_manifest, app_apk, test_apk, model_file, model_manifest, directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=False)
    report = {"schemaVersion": 1, "result": "REJECTED", "preflightIntegrity": "UNVERIFIED",
              "environment": "HOST", "targetRole": target, "qualificationResult": "NOT_EVALUATED",
              "physicalQualification": False, "profileQualified": False, "fullF5GateClosed": False,
              "fullSoakGateClosed": False, "cortexRuntimeReadyDeclared": False, "deviceMutated": False,
              "recordedAtUtc": datetime.now(timezone.utc).isoformat(), "recorderSha256": sha_file(__file__),
              "executionPlan": [{"case": "PHYSICAL_CORTEX_MEASURED_RUNTIME", "status": "NOT_IMPLEMENTED", "command": None},
                                {"case": "PHYSICAL_API37_MEMORY_LIMITER", "status": "NOT_IMPLEMENTED", "command": None},
                                {"case": "POST_CORTEX_24H_MEMORY_STRESS", "status": "NOT_IMPLEMENTED", "command": None}],
              "trackingIssue": "https://github.com/otavio-esteves/O.R.I.O.N/issues/89"}
    try:
        report["source"], expected = source_identity(source_root, source_manifest)
        local = {"app": sha_file(app_apk, 1024**3), "test": sha_file(test_apk, 1024**3)}
        report["model"], filename = model_identity(model_file, model_manifest)
        local["model"] = report["model"]["sha256"]
        require(local == expected, "ARTIFACT_PROVENANCE_MISMATCH")
        report["artifactSha256"] = local
        report["target"] = physical_identity(adb, serial, target)
        report["installedApkSha256"] = {key: installed_hash(adb, package) for key, package in PACKAGES.items()}
        require(all(report["installedApkSha256"][key] == local[key] for key in PACKAGES), "INSTALLED_APK_MISMATCH")
        relative = "files/" + filename
        require(adb("shell", "run-as", "network.orion", "stat", "-c", "%F", relative) == "regular file", "DEVICE_MODEL_NOT_REGULAR")
        digest = adb("shell", "run-as", "network.orion", "sha256sum", relative)
        require(re.fullmatch(r"[0-9a-f]{64}\s+" + re.escape(relative), digest) and digest[:64] == local["model"], "DEVICE_MODEL_HASH_MISMATCH")
        # Technical inputs are internally consistent; no physical case or profile was evaluated.
        report.update(result="BLOCKED", preflightIntegrity="VERIFIED",
                      blockers=["PHYSICAL_CASES_NOT_IMPLEMENTED", "PROFILE_MEASUREMENT_AND_REVIEW_REQUIRED",
                                "BUILD_PROVENANCE_REVIEW_REQUIRED", "24H_SOAK_NOT_EXECUTED", "F4_STABILITY_PREREQUISITE_OPEN"])
    except Rejected as error:
        report["rejectionCode"] = str(error)
    except KeyboardInterrupt:
        report["rejectionCode"] = "INTERRUPTED"
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired):
        report["rejectionCode"] = "INPUT_OR_TECHNICAL_OBSERVATION_UNAVAILABLE"
    finally:
        (directory / "f5-physical-preflight.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--serial", required=True)
    parser.add_argument("--target", required=True, choices=("S21", "API37"))
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--source-manifest", type=Path, required=True)
    parser.add_argument("--app-apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--model-file", type=Path, required=True)
    parser.add_argument("--model-manifest", type=Path, required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    args = parser.parse_args()
    if not args.adb:
        parser.error("An explicit HOST ADB executable is required.")
    try:
        report = prepare(OBS.Adb(args.adb, args.serial), args.serial, args.target, args.source_root,
                         args.source_manifest, args.app_apk, args.test_apk, args.model_file,
                         args.model_manifest, args.evidence_dir)
    except (OSError, ValueError):
        parser.error("A new writable evidence directory is required.")
    print(report["result"] + ": qualification NOT_EVALUATED; no device mutation or test executed.")
    raise SystemExit(3 if report["result"] == "BLOCKED" else 2)


if __name__ == "__main__":
    main()
