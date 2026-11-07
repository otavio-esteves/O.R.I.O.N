#!/usr/bin/env python3
"""HOST passive technical observations; no install, launch, kill or setting writes."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time

PACKAGE = "network.orion"
MAX_SAMPLES = 20_000
MAX_OUTPUT_BYTES = 1_048_576


def bounds(duration, interval):
    if not (math.isfinite(duration) and math.isfinite(interval)
            and 1 <= duration <= 7 * 86400 and 5 <= interval <= 3600):
        raise ValueError("Duration must be 1s–7d; interval must be 5s–1h.")
    count = math.ceil(duration / interval) + 1
    if count > MAX_SAMPLES:
        raise ValueError("Sampling exceeds the bounded observation budget.")
    return count


def integer(value):
    if not re.fullmatch(r"[0-9]{1,15}", value):
        raise ValueError("Expected a nonnegative technical integer.")
    return int(value)


def pids(output):
    if not output:
        return []
    values = output.split(" ")
    if len(values) > 16 or any(not re.fullmatch(r"[1-9][0-9]{0,9}", p) for p in values):
        raise ValueError("Unrecognized process identity output.")
    return [int(p) for p in values]


def battery(output):
    fields = {}
    for key in ("level", "scale", "status", "temperature", "voltage"):
        match = re.search(r"(?m)^\s*" + key + r": (-?[0-9]{1,8})\s*$", output)
        if match:
            fields[key] = int(match.group(1))
    if not (0 <= fields.get("level", -1) <= fields.get("scale", -1)
            and fields.get("scale", 0) > 0):
        raise ValueError("Battery level and scale are unavailable.")
    fields["simulationActive"] = "UPDATES STOPPED" in output
    for key in ("AC", "USB", "Wireless"):
        match = re.search(r"(?mi)^\s*" + key + r" powered: (true|false)\s*$", output)
        if match:
            fields[key.lower() + "Powered"] = match.group(1).lower() == "true"
    return fields


def memory(output):
    fields = {}
    for name in ("PSS", "RSS"):
        match = re.search(r"\bTOTAL " + name + r":\s*([0-9]{1,15})\b", output)
        if match:
            fields[name.lower() + "KiB"] = int(match.group(1))
    if "pssKiB" not in fields:
        raise ValueError("Supported TOTAL PSS field is unavailable.")
    return fields


def storage(output):
    values = {}
    for line in output.splitlines():
        match = re.fullmatch(r"([0-9]{1,15})\s+(databases|files)", line)
        if not match or match.group(2) + "KiB" in values:
            raise ValueError("Unexpected storage output.")
        values[match.group(2) + "KiB"] = int(match.group(1))
    if set(values) != {"databasesKiB", "filesKiB"}:
        raise ValueError("Private storage sizes are unavailable.")
    return values


def exits(output):
    # Snapshot of Android's retained ring buffer, not a count of new exits.
    if "ACTIVITY MANAGER PROCESS EXIT INFO" not in output:
        raise ValueError("Unsupported exit-info output.")
    rows = re.split(r"ApplicationExitInfo[^\n]*:\n", output)[1:]
    if len(rows) > 256:
        raise ValueError("Exit history exceeds the observation bound.")
    reasons = {}
    for row in rows:
        match = re.search(r"\bprocess=(network\.orion(?::[a-z_]+)?)\s+reason=([0-9]{1,3})\b", row)
        if not match:
            raise ValueError("Unrecognized exit history record.")
        key = match.group(2)
        reasons[key] = reasons.get(key, 0) + 1
    return {"retainedRecords": len(rows), "reasonCounts": reasons}


class Adb:
    def __init__(self, executable, serial):
        self.prefix = [executable, "-s", serial]

    def __call__(self, *command, allow_absent=False):
        # Retain no raw device dumps/errors; cap decoded output and command lifetime.
        with tempfile.TemporaryFile() as output:
            result = subprocess.run(self.prefix + list(command), stdout=output,
                                    stderr=subprocess.DEVNULL, timeout=10, check=False)
            output.seek(0)
            data = output.read(MAX_OUTPUT_BYTES + 1)
        if len(data) > MAX_OUTPUT_BYTES:
            raise ValueError("Command output exceeded bound.")
        text = data.decode("utf-8", errors="strict").strip()
        if result.returncode and not (allow_absent and result.returncode == 1 and not text):
            raise RuntimeError("Technical observation command failed.")
        return text


def installed_hash(adb):
    path = adb("shell", "pm", "path", PACKAGE)
    if not re.fullmatch(r"package:/data/app/[A-Za-z0-9_./=+~\-]+/base\.apk", path):
        raise ValueError("Expected one installed base APK; splits are not supported.")
    digest = adb("shell", "sha256sum", path.removeprefix("package:"))
    if not re.fullmatch(r"[0-9a-f]{64}\s+" + re.escape(path[8:]), digest):
        raise ValueError("Installed APK identity could not be observed.")
    return digest[:64]


def device_identity(adb):
    serial = adb("shell", "getprop", "ro.serialno")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", serial):
        raise ValueError("Device identity is unavailable.")
    return hashlib.sha256(serial.encode()).hexdigest()


def snapshot(adb):
    sample = {"metrics": {}, "unavailable": []}
    def read(name, parser, *command, **kwargs):
        try:
            value = parser(adb(*command, **kwargs))
            sample["metrics"][name] = value
            return value
        except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired):
            sample["unavailable"].append(name)
            return None
    read("bootCount", integer, "shell", "settings", "get", "global", "boot_count")
    current = read("mainPids", pids, "shell", "pidof", PACKAGE, allow_absent=True)
    read("battery", battery, "shell", "dumpsys", "battery")
    if current and len(current) == 1:
        read("mainMemory", memory, "shell", "dumpsys", "meminfo", "-s", str(current[0]))
    elif current:
        sample["unavailable"].append("mainMemory")
    read("privateStorage", storage, "shell", "run-as", PACKAGE, "du", "-sk", "databases", "files")
    read("retainedExitHistory", exits, "shell", "dumpsys", "activity", "exit-info", PACKAGE)
    return sample


def observe(adb, directory, expected_hash, environment, duration, interval,
            monotonic=time.monotonic, sleep=time.sleep):
    limit = bounds(duration, interval)
    directory.mkdir(parents=True, exist_ok=False)
    report = {"result": "INCOMPLETE", "fullSoakGateClosed": False,
              "environment": environment, "expectedApkSha256": expected_hash,
              "requestedSeconds": duration, "intervalSeconds": interval,
              "samples": 0, "samplesWithGaps": 0, "samplingGaps": 0,
              "artifactMismatch": False, "deviceMismatch": False,
              "startedAtUtc": datetime.now(timezone.utc).isoformat(),
              "collectorSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "limitations": ["Passive sampled telemetry requires human review; completion is not PASS.",
                              "No queue, UNKNOWN, delivery, retention or wakelock proof is collected.",
                              "Retained exit snapshots can truncate history and include pre-run exits.",
                              "ADB polling affects battery/idle behavior; it is not battery qualification.",
                              "Missing-process snapshots do not prove a crash, Force Stop or restart loop."]}
    start = monotonic()
    try:
        qemu = adb("shell", "getprop", "ro.kernel.qemu")
        if qemu not in ("", "0", "1"):
            raise ValueError("Unrecognized environment property.")
        emulated = qemu == "1"
        if environment not in ("PHYSICAL_DEVICE", "ANDROID_EMULATOR") or emulated != (environment == "ANDROID_EMULATOR"):
            raise ValueError("Environment mismatch.")
        report["androidRuntimeEvidence"] = "NON_DEFINITIVE" if emulated else "PHYSICAL_OBSERVATION_ONLY"
        report["apiLevel"] = integer(adb("shell", "getprop", "ro.build.version.sdk"))
        report["deviceIdentitySha256"] = device_identity(adb)
        initial_hash = installed_hash(adb)
        report["installedApkSha256Before"] = initial_hash
        if initial_hash != expected_hash:
            report["artifactMismatch"] = True
            raise ValueError("Installed APK mismatch.")
        start = monotonic()
        previous_start = None
        with (directory / "samples.jsonl").open("x") as stream:
            for index in range(limit):
                sample_start = monotonic()
                if previous_start is not None and sample_start - previous_start > interval * 2:
                    report["samplingGaps"] += 1
                previous_start = sample_start
                sample = snapshot(adb)
                sample.update({"index": index, "elapsedSeconds": round(monotonic() - start, 3),
                               "startedElapsedSeconds": round(sample_start - start, 3),
                               "collectionSeconds": round(monotonic() - sample_start, 3)})
                stream.write(json.dumps(sample, sort_keys=True) + "\n")
                stream.flush()
                report["samples"] += 1
                report["samplesWithGaps"] += bool(sample["unavailable"])
                if monotonic() - start >= duration:
                    break
                sleep(max(0, min(interval, duration - (monotonic() - start))))
        report["installedApkSha256After"] = installed_hash(adb)
        report["artifactMismatch"] = report["installedApkSha256After"] != expected_hash
        report["deviceMismatch"] = device_identity(adb) != report["deviceIdentitySha256"]
        if not any(report[k] for k in ("artifactMismatch", "deviceMismatch", "samplesWithGaps", "samplingGaps")) and monotonic() - start >= duration:
            report["result"] = "OBSERVATION_COMPLETE_REVIEW_REQUIRED"
    except KeyboardInterrupt:
        report["interrupted"] = True
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        # Error types only: exception text can contain device/user data.
        report["failureType"] = type(error).__name__
    finally:
        report["elapsedSeconds"] = round(monotonic() - start, 3)
        samples = directory / "samples.jsonl"
        if samples.exists():
            report["samplesSha256"] = hashlib.sha256(samples.read_bytes()).hexdigest()
        (directory / "soak-observation-report.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--environment", required=True, choices=("PHYSICAL_DEVICE", "ANDROID_EMULATOR"))
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--duration-seconds", type=float, default=86400)
    parser.add_argument("--interval-seconds", type=float, default=60)
    args = parser.parse_args()
    try:
        bounds(args.duration_seconds, args.interval_seconds)
        if not args.adb or not args.apk.is_file() or args.evidence_dir.exists():
            raise ValueError("Provide host ADB, a built APK and a NEW evidence directory.")
    except ValueError as error:
        parser.error(str(error))
    report = observe(Adb(args.adb, args.serial), args.evidence_dir,
                     hashlib.sha256(args.apk.read_bytes()).hexdigest(), args.environment,
                     args.duration_seconds, args.interval_seconds)
    print(json.dumps(report, indent=2))
    return 0 if report["result"] == "OBSERVATION_COMPLETE_REVIEW_REQUIRED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
