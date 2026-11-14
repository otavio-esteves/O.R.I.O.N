#!/usr/bin/env python3
"""HOST recorder for the opt-in physical baseline only; never model/profile/F5 qualification."""
import argparse
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import uuid

SPEC = importlib.util.spec_from_file_location("orion_f5_preflight", Path(__file__).with_name("prepare-f5-physical-qualification.py"))
PREFLIGHT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREFLIGHT)
require = PREFLIGHT.require
Rejected = PREFLIGHT.Rejected
PACKAGE = "network.orion.qualification"
PACKAGES = {"app": PACKAGE, "test": PACKAGE + ".test"}
RUNNER = PACKAGES["test"] + "/network.orion.app.compatibility.OrionPhysicalBaselineInstrumentation"
STREAM = "MEASUREMENT_ONLY / NOT_QUALIFIED"
MAX_OUTPUT = 1024 * 1024
LONG_MAX = 2**63 - 1
FAILURES = {"ARGUMENTS_REJECTED", "PHYSICAL_ENVIRONMENT_REJECTED", "TARGET_IDENTITY_REJECTED", "BIND_FAILED",
            "OBSERVATION_FAILED", "OBSERVATION_TIMEOUT", "SERVICE_DISCONNECTED", "INTERRUPTED", "CLEANUP_FAILED",
            "CLEANUP_NOT_REGISTERED"}
SAMPLE_KEYS = set("schemaVersion kind environment qualification packageName sampleIndex sampleCount bootCount mainPid aiPid "
                  "sampledElapsed aiSampledElapsed completedElapsed maximumAgeMillis mainPssBytes aiPssBytes aggregatePssBytes "
                  "systemAvailableMemoryBytes batteryPercent thermalStatus powerSave foreground importance memoryPressure "
                  "inventoryProcessCount serviceState nativeRuntimeLoaded limitations".split())
SUMMARY_KEYS = set("schemaVersion kind environment qualification packageName result samplesRequested samplesCollected sampleIntervalMillis "
                   "profileQualified productionInferenceEnabled fullF5GateClosed fullSoakGateClosed heavyWorkIssued failureCode".split())
NUMERIC_SAMPLE = set("schemaVersion sampleIndex sampleCount bootCount mainPid aiPid sampledElapsed aiSampledElapsed completedElapsed "
                     "maximumAgeMillis mainPssBytes aiPssBytes aggregatePssBytes systemAvailableMemoryBytes batteryPercent "
                     "thermalStatus inventoryProcessCount".split())
PROPERTIES = {"ro.kernel.qemu", "ro.boot.qemu", "ro.build.fingerprint", "ro.hardware", "ro.build.version.sdk",
              "ro.product.cpu.abi", "ro.product.manufacturer", "ro.product.model", "ro.serialno"}
FAKE_LABEL = re.compile(r"synthetic|fixture|fake|test[_-]?only|example", re.I)


def bounds(samples, interval):
    require(type(samples) is int and 1 <= samples <= 10 and type(interval) is int and 100 <= interval <= 1000,
            "INVALID_SAMPLE_BOUNDS")


def instrumentation_command(samples, interval):
    bounds(samples, interval)
    return ["shell", "am", "instrument", "-w", "-r", "-e", "orionEnvironment", "PHYSICAL_DEVICE",
            "-e", "orionSampleCount", str(samples), "-e", "orionSampleIntervalMs", str(interval), RUNNER]


def allowed_command(command):
    if command == ("get-state",):
        return True
    if len(command) == 3 and command[:2] == ("shell", "getprop") and command[2] in PROPERTIES:
        return True
    if command == ("shell", "getconf", "PAGE_SIZE"):
        return True
    if len(command) == 4 and command[:3] == ("shell", "pm", "path") and command[3] in PACKAGES.values():
        return True
    if len(command) == 3 and command[:2] == ("shell", "sha256sum"):
        return bool(re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~\-]+/base\.apk", command[2]) and ".." not in Path(command[2]).parts)
    if len(command) == 3 and command[:2] == ("shell", "cat"):
        return command[2] in ("/proc/uptime", "/proc/meminfo", "/proc/sys/kernel/random/boot_id")
    if len(command) == 15 and command[0:3] == ("shell", "am", "instrument"):
        try:
            return list(command) == instrumentation_command(int(command[10]), int(command[13]))
        except (ValueError, Rejected):
            return False
    return False


class BaselineAdb:
    """Closed observation queries plus one bounded instrumentation endpoint in the separate qualification APK."""
    def __init__(self, executable, serial):
        require(type(serial) is str and PREFLIGHT.SERIAL.fullmatch(serial),
                "EXPLICIT_PHYSICAL_SERIAL_REQUIRED")
        self.prefix = [executable, "-s", serial]

    def __call__(self, *command):
        require(allowed_command(command), "ADB_COMMAND_NOT_ALLOWED")
        timeout = 45 if command[:3] == ("shell", "am", "instrument") else 10
        with tempfile.TemporaryFile() as output:
            result = subprocess.run(self.prefix + list(command), stdout=output, stderr=subprocess.DEVNULL,
                                    timeout=timeout, check=False)
            output.seek(0)
            data = output.read(MAX_OUTPUT + 1)
        require(len(data) <= MAX_OUTPUT and result.returncode == 0, "ADB_OBSERVATION_UNAVAILABLE")
        return data.decode("utf-8", errors="strict").strip()


def device_window(adb):
    first = adb("shell", "cat", "/proc/sys/kernel/random/boot_id")
    require(str(uuid.UUID(first)) == first and uuid.UUID(first).int != 0, "BOOT_ID_UNAVAILABLE")
    uptime = adb("shell", "cat", "/proc/uptime")
    match = re.fullmatch(r"([0-9]{1,13})\.([0-9]{1,3}) [0-9]{1,15}\.[0-9]{1,3}", uptime)
    require(match is not None, "UPTIME_UNAVAILABLE")
    elapsed = int(Decimal(match.group(1) + "." + match.group(2)) * 1000)
    precision = 10 ** (3 - len(match.group(2)))
    require(0 <= elapsed <= LONG_MAX - precision, "UPTIME_UNAVAILABLE")
    memory = adb("shell", "cat", "/proc/meminfo")
    totals = re.findall(r"(?m)^MemTotal:\s+([0-9]{1,12}) kB$", memory)
    require(len(totals) == 1, "TOTAL_MEMORY_UNAVAILABLE")
    total = int(totals[0]) * 1024
    require(64 * 1024**2 <= total <= 1024**4, "TOTAL_MEMORY_UNAVAILABLE")
    second = adb("shell", "cat", "/proc/sys/kernel/random/boot_id")
    require(first == second, "BOOT_CHANGED_DURING_OBSERVATION")
    return {"bootIdSha256": hashlib.sha256(first.encode()).hexdigest(), "elapsedLowerMillis": elapsed,
            "elapsedUpperMillis": elapsed + precision - 1, "totalMemoryBytes": total}


def physical_identity(adb, serial, target):
    identity = PREFLIGHT.physical_identity(adb, serial, target)
    labels = " ".join([identity["manufacturer"], identity["model"],
                       adb("shell", "getprop", "ro.build.fingerprint"), adb("shell", "getprop", "ro.hardware")])
    require(not FAKE_LABEL.search(labels), "SYNTHETIC_DEVICE_LABEL")
    return identity


def receipt_json(text):
    require(0 < len(text.encode("utf-8")) <= 16 * 1024, "RECEIPT_SIZE_INVALID")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "DUPLICATE_RECEIPT_FIELD")
            result[key] = value
        return result
    def constant(_):
        raise Rejected("NONFINITE_RECEIPT_VALUE")
    value = json.loads(text, object_pairs_hook=unique, parse_constant=constant)
    require(type(value) is dict, "RECEIPT_NOT_OBJECT")
    return value


def validate_sample(sample, index, samples, interval, before, after, previous):
    require(set(sample) == SAMPLE_KEYS, "SAMPLE_FIELDS_INVALID")
    require(all(type(sample[key]) is int and 0 <= sample[key] <= LONG_MAX for key in NUMERIC_SAMPLE), "SAMPLE_NUMBERS_INVALID")
    require(sample["schemaVersion"] == 1 and sample["kind"] == "PHYSICAL_BASELINE_SAMPLE" and
            sample["environment"] == "PHYSICAL_DEVICE" and sample["qualification"] == "NOT_QUALIFIED" and
            sample["packageName"] == PACKAGE, "SAMPLE_IDENTITY_INVALID")
    require(sample["sampleIndex"] == index and sample["sampleCount"] == samples and
            sample["inventoryProcessCount"] == 2 and sample["maximumAgeMillis"] == 1000, "SAMPLE_SEQUENCE_INVALID")
    require(0 < sample["mainPid"] <= 2**31 - 1 and 0 < sample["aiPid"] <= 2**31 - 1 and
            sample["mainPid"] != sample["aiPid"] and sample["bootCount"] <= 2**31 - 1, "SAMPLE_PROCESS_INVALID")
    require(all(type(sample[key]) is bool for key in ("powerSave", "foreground", "nativeRuntimeLoaded")) and
            not sample["nativeRuntimeLoaded"] and sample["serviceState"] == "UNLOADED" and
            type(sample["limitations"]) is list and not sample["limitations"], "SAMPLE_NOT_LIGHTWEIGHT_OR_COMPLETE")
    require(sample["importance"] in ("FOREGROUND", "BACKGROUND") and sample["memoryPressure"] in
            ("NONE", "MODERATE", "CRITICAL") and 0 <= sample["batteryPercent"] <= 100 and
            0 <= sample["thermalStatus"] <= 6, "SAMPLE_PLATFORM_METRICS_INVALID")
    require(0 < sample["mainPssBytes"] <= after["totalMemoryBytes"] and 0 < sample["aiPssBytes"] <= after["totalMemoryBytes"] and
            sample["aggregatePssBytes"] == sample["mainPssBytes"] + sample["aiPssBytes"] <= after["totalMemoryBytes"] and
            sample["systemAvailableMemoryBytes"] <= after["totalMemoryBytes"], "SAMPLE_MEMORY_INVALID")
    start, ai, completed = (sample[key] for key in ("sampledElapsed", "aiSampledElapsed", "completedElapsed"))
    require(before["elapsedLowerMillis"] <= start <= ai <= completed <= after["elapsedUpperMillis"] and
            completed - start < 1000 and completed - ai < 1000, "SAMPLE_STALE_OR_OUTSIDE_WINDOW")
    if previous is not None:
        require(all(sample[key] == previous[key] for key in ("bootCount", "mainPid", "aiPid")), "SAMPLE_BOOT_OR_PROCESS_CHANGED")
        require(start >= previous["completedElapsed"] + interval and completed > previous["completedElapsed"], "SAMPLE_CADENCE_INVALID")


def receipts(output, samples, interval, before, after):
    bounds(samples, interval)
    require(type(output) is str and 0 < len(output.encode("utf-8")) <= MAX_OUTPUT, "INSTRUMENTATION_OUTPUT_INVALID")
    require(before["bootIdSha256"] == after["bootIdSha256"] and before["totalMemoryBytes"] == after["totalMemoryBytes"],
            "BOOT_OR_MEMORY_IDENTITY_CHANGED")
    require(before["elapsedLowerMillis"] <= after["elapsedUpperMillis"] and
            after["elapsedUpperMillis"] - before["elapsedLowerMillis"] <= 60_000, "OBSERVATION_WINDOW_INVALID")
    observed, summary, code = [], None, None
    pending_sample, stream, pending_stream, result_started = False, False, False, False
    for line in output.splitlines():
        if not line.strip():
            continue
        require(code is None, "SURPLUS_INSTRUMENTATION_OUTPUT")
        if line.startswith("INSTRUMENTATION_STATUS: orionPhysicalBaselineSample="):
            require(not result_started and not pending_sample and len(observed) < samples, "SURPLUS_OR_UNORDERED_SAMPLE")
            value = receipt_json(line.split("=", 1)[1])
            validate_sample(value, len(observed), samples, interval, before, after, observed[-1] if observed else None)
            observed.append(value)
            pending_sample = True
        elif line == "INSTRUMENTATION_STATUS_CODE: 2":
            require(pending_sample and not result_started, "UNEXPECTED_SAMPLE_STATUS")
            pending_sample = False
        elif line.startswith("INSTRUMENTATION_RESULT: orionPhysicalBaselineSummary="):
            require(summary is None and not pending_sample, "DUPLICATE_OR_UNORDERED_SUMMARY")
            summary = receipt_json(line.split("=", 1)[1])
            result_started = True
        elif line.startswith("INSTRUMENTATION_RESULT: stream="):
            require(not pending_sample and not stream and not pending_stream, "INVALID_MEASUREMENT_STREAM")
            value = line.split("=", 1)[1]
            require(value in ("", STREAM), "INVALID_MEASUREMENT_STREAM")
            stream, pending_stream = value == STREAM, value == ""
            result_started = True
        elif pending_stream and line == STREAM:
            stream, pending_stream = True, False
        elif line in ("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"):
            require(summary is not None and stream and not pending_stream and not pending_sample, "INCOMPLETE_RECEIPT_FRAMING")
            code = int(line.rsplit(" ", 1)[1])
        else:
            raise Rejected("UNEXPECTED_INSTRUMENTATION_OUTPUT")
    require(summary is not None and code is not None and set(summary) == SUMMARY_KEYS, "SUMMARY_FIELDS_INVALID")
    # UNVERIFIED runner rejections are deliberately not physical observations.
    # Keep them rejected by identity; never relabel them as PHYSICAL_DEVICE.
    require(type(summary["schemaVersion"]) is int and summary["schemaVersion"] == 1 and
            summary["kind"] == "PHYSICAL_BASELINE_SUMMARY" and summary["environment"] == "PHYSICAL_DEVICE" and
            summary["qualification"] == "NOT_QUALIFIED" and summary["packageName"] == PACKAGE, "SUMMARY_IDENTITY_INVALID")
    require(all(type(summary[key]) is bool and not summary[key] for key in
                ("profileQualified", "productionInferenceEnabled", "fullF5GateClosed", "fullSoakGateClosed", "heavyWorkIssued")),
            "SUMMARY_AUTHORITY_INVALID")
    require(all(type(summary[key]) is int for key in ("samplesRequested", "samplesCollected", "sampleIntervalMillis")) and
            summary["samplesRequested"] == samples and summary["sampleIntervalMillis"] == interval and
            summary["samplesCollected"] == len(observed), "SUMMARY_SAMPLE_COUNT_INVALID")
    result = summary["result"]
    require(result in ("OBSERVATION_COMPLETE_REVIEW_REQUIRED", "INCOMPLETE", "REJECTED"), "SUMMARY_RESULT_INVALID")
    complete = result == "OBSERVATION_COMPLETE_REVIEW_REQUIRED"
    if complete:
        require(code == -1 and summary["failureCode"] is None and len(observed) == samples, "INCOMPLETE_BASELINE")
    else:
        require(code == 0 and type(summary["failureCode"]) is str and summary["failureCode"] in FAILURES, "INVALID_FAILURE_RECEIPT")
    return {"samples": observed, "summary": summary, "complete": complete}


def record(adb, serial, target, source_root, source_manifest, app_apk, test_apk, directory, samples=3, interval=250):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=False, mode=0o700)
    report = {"schemaVersion": 1, "result": "REJECTED", "qualificationResult": "NOT_QUALIFIED", "environment": "HOST",
              "physicalQualification": False, "profileQualified": False, "productionInferenceEnabled": False,
              "fullF5GateClosed": False, "fullSoakGateClosed": False, "cortexRuntimeReadyDeclared": False,
              "collectionPurpose": "UNLOADED_RESOURCE_BASELINE_ONLY", "targetPackage": PACKAGE,
              "modelInputsUsed": False, "heavyWorkIssued": False, "installPerformed": False,
              "deviceSettingsChanged": False, "possibleTechnicalBootstrapWrites": True,
              "productionDataAccessRequested": False, "instrumentationCommandIssued": False, "instrumentationExecuted": False,
              "ownedMeasurementProcessStartRequested": False,
              "ownedMeasurementProcessStarted": False, "recordedAtUtc": datetime.now(timezone.utc).isoformat(),
              "recorderSha256": PREFLIGHT.sha_file(__file__), "buildProvenance": "MANIFEST_BOUND_REVIEW_REQUIRED"}
    try:
        bounds(samples, interval)
        require(type(serial) is str and PREFLIGHT.SERIAL.fullmatch(serial) and not serial.startswith("emulator-"),
                "EXPLICIT_PHYSICAL_SERIAL_REQUIRED")
        source, expected = PREFLIGHT.source_identity(source_root, source_manifest, required_artifacts=frozenset({"app", "test"}))
        local = {"app": PREFLIGHT.sha_file(app_apk, 1024**3), "test": PREFLIGHT.sha_file(test_apk, 1024**3)}
        require(local == expected, "ARTIFACT_PROVENANCE_MISMATCH")
        identity = physical_identity(adb, serial, target)
        installed = {key: PREFLIGHT.installed_hash(adb, package) for key, package in PACKAGES.items()}
        require(installed == local, "INSTALLED_APK_MISMATCH")
        report.update(source=source, artifactSha256=local, installedApkSha256=installed, target=identity)
        before = device_window(adb)
        report["ownedMeasurementProcessStartRequested"] = True
        report["ownedMeasurementProcessStarted"] = None
        # This starts the separate qualification APK and can initialize its own
        # technical DB/Keystore/Core state. It is not a wholly read-only device run.
        report["instrumentationCommandIssued"] = True
        report["instrumentationExecuted"] = None
        output = adb(*instrumentation_command(samples, interval))
        report["instrumentationOutputSha256"] = hashlib.sha256(output.encode()).hexdigest()
        after = device_window(adb)
        require({key: PREFLIGHT.installed_hash(adb, package) for key, package in PACKAGES.items()} == installed, "INSTALLED_APK_CHANGED")
        require(physical_identity(adb, serial, target) == identity, "DEVICE_IDENTITY_CHANGED")
        require(PREFLIGHT.source_identity(source_root, source_manifest, required_artifacts=frozenset({"app", "test"}))[0] == source and
                {"app": PREFLIGHT.sha_file(app_apk, 1024**3), "test": PREFLIGHT.sha_file(test_apk, 1024**3)} == local,
                "SOURCE_OR_LOCAL_ARTIFACT_CHANGED")
        measured = receipts(output, samples, interval, before, after)
        result = "MEASUREMENT_ONLY" if measured["complete"] else measured["summary"]["result"]
        report.update(result=result, measurementEnvironment="PHYSICAL_DEVICE",
                      measurementIntegrity="COMPLETE_REVIEW_REQUIRED" if measured["complete"] else "INCOMPLETE",
                      instrumentationExecuted=True,
                      ownedMeasurementProcessStarted=True, windowBefore=before, windowAfter=after,
                      samples=measured["samples"], summary=measured["summary"])
    except Rejected as error:
        report["rejectionCode"] = str(error)
    except KeyboardInterrupt:
        report["rejectionCode"] = "INTERRUPTED"
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired):
        report["rejectionCode"] = "INPUT_OR_OBSERVATION_UNAVAILABLE"
    finally:
        (directory / "f5-physical-baseline.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
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
    parser.add_argument("--samples", type=int, default=3)
    parser.add_argument("--interval-ms", type=int, default=250)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    args = parser.parse_args()
    if not args.adb:
        parser.error("An explicit HOST ADB executable is required.")
    try:
        report = record(BaselineAdb(args.adb, args.serial), args.serial, args.target, args.source_root,
                        args.source_manifest, args.app_apk, args.test_apk, args.evidence_dir, args.samples, args.interval_ms)
    except (OSError, ValueError):
        parser.error("A valid explicit physical serial and new writable evidence directory are required.")
    print(report["result"] + ": NOT_QUALIFIED; no profile, F5 or soak approval.")
    raise SystemExit(0 if report["result"] == "MEASUREMENT_ONLY" else 2)


if __name__ == "__main__":
    main()
