#!/usr/bin/env python3
"""HOST read-only maintenance log history; sampled evidence never closes a phase gate."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid

SPEC = importlib.util.spec_from_file_location("maintenance_observer", Path(__file__).with_name("record-soak-observation.py"))
OBS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(OBS)
TAG = "orion_maintenance"
METRICS = {"actionTotal", "actionQueued", "actionRunning", "actionUnknown", "actionManualReview",
           "eventTotal", "eventPending", "eventDeadLetter", "ingressTotal", "ingressQueued",
           "taskTotal", "reminderTotal", "timerTotal", "memoryTotal", "prunedEvents", "prunedIngress",
           "prunedActions", "prunedAudit", "prunedTechnical", "databaseBytes"}
FIELDS = {"schemaVersion", "correlationId", "component", "event", "severity", "timestampMillis", "bootCount",
          "processId", "elapsedMillis", "sequence", "origin", "healthy", "metrics"}
ORIGINS = {"SYSTEM_TRIGGER", "HEARTBEAT", "RECONCILIATION", "WORKER_UNCLASSIFIED"}


def record(value):
    try:
        if type(value) is not dict or set(value) != FIELDS or type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
            raise ValueError()
        if str(uuid.UUID(value["correlationId"])) != value["correlationId"]:
            raise ValueError()
        if value["component"] != "SCHEDULER" or value["event"] != "MAINTENANCE_PASS_FINISHED" or value["origin"] not in ORIGINS:
            raise ValueError()
        if type(value["healthy"]) is not bool or value["severity"] != ("INFO" if value["healthy"] else "WARN"):
            raise ValueError()
        bounds = {"timestampMillis": (-(2**63), 2**63 - 1), "bootCount": (0, 2**31 - 1),
                  "processId": (1, 2**31 - 1), "elapsedMillis": (0, 2**63 - 1), "sequence": (1, 2**63 - 1)}
        for name, (low, high) in bounds.items():
            if type(value[name]) is not int or not low <= value[name] <= high: raise ValueError()
        metrics = value["metrics"]
        if type(metrics) is not dict or set(metrics) != METRICS or any(type(n) is not int or not 0 <= n <= 2**63 - 1 for n in metrics.values()):
            raise ValueError()
        if (sum(metrics[k] for k in ("actionQueued", "actionRunning", "actionUnknown", "actionManualReview")) > metrics["actionTotal"] or
                metrics["eventPending"] + metrics["eventDeadLetter"] > metrics["eventTotal"] or metrics["ingressQueued"] > metrics["ingressTotal"]):
            raise ValueError()
    except (TypeError, KeyError, AttributeError, ValueError):
        raise ValueError("Unavailable or malformed maintenance protocol.") from None
    return value


def records(output):
    if len(output.encode()) > 1024 * 1024: raise ValueError("Maintenance buffer exceeds limit.")
    values = []
    for line in output.splitlines():
        if not line or re.fullmatch(r"--------- beginning of (main|system|crash|events)", line): continue
        if len(line.encode()) > 4096 or len(values) >= 512: raise ValueError("Maintenance record bound exceeded.")
        values.append(record(json.loads(line, object_pairs_hook=unique_fields)))
    return values


def unique_fields(pairs):
    value = {}
    for key, item in pairs:
        if key in value: raise ValueError("Duplicate maintenance field.")
        value[key] = item
    return value


class History:
    def __init__(self):
        self.seen = {}
        self.positions = {}
        self.sequence_gaps = 0
        self.duplicates = 0

    def accept(self, values, baseline=False):
        # Stage the whole poll so one malformed/conflicting event never hides later
        # data behind a partially advanced dedup cursor.
        staged_seen = dict(self.seen); staged_positions = dict(self.positions)
        fresh = []; gaps = duplicates = 0
        for value in values:
            value = record(value)
            identity = value["correlationId"]
            digest = hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()
            if identity in staged_seen:
                if staged_seen[identity] != digest: raise ValueError("Conflicting maintenance observation.")
                duplicates += 1; continue
            if len(staged_seen) >= 20_000: raise ValueError("Maintenance history bound exceeded.")
            key = (value["bootCount"], value["processId"])
            previous = staged_positions.get(key)
            if previous is not None:
                if value["sequence"] != previous[0] + 1 or value["elapsedMillis"] < previous[1]: gaps += 1
            elif not baseline and value["sequence"] != 1:
                gaps += 1
            staged_seen[identity] = digest
            staged_positions[key] = (value["sequence"], value["elapsedMillis"])
            fresh.append(value)
        self.seen = staged_seen; self.positions = staged_positions
        self.sequence_gaps += gaps; self.duplicates += duplicates
        return fresh


def own_uid(adb):
    output = adb("shell", "cmd", "package", "list", "packages", "-U", OBS.PACKAGE)
    matches = re.findall(r"(?m)^package:network\.orion uid:([0-9]+)$", output)
    if len(matches) != 1 or not 0 < int(matches[0]) <= 2**31 - 1: raise ValueError("Application UID is unavailable.")
    return int(matches[0])


def observe(adb, directory, expected_hash, environment, duration, interval,
            monotonic=time.monotonic, sleep=time.sleep):
    limit = OBS.bounds(duration, interval)
    directory.mkdir(parents=True, exist_ok=False)
    report = {"result": "INCOMPLETE", "environment": environment, "fullSoakGateClosed": False, "fullF4GateClosed": False,
              "continuousCoverageProven": False, "heartbeatAutonomyProven": False, "requestedSeconds": duration,
              "intervalSeconds": interval, "startedAtUtc": datetime.now(timezone.utc).isoformat(), "samples": 0,
              "samplesWithGaps": 0, "samplingGaps": 0, "baselineEvents": 0, "firstSeenSubsequentEvents": 0,
              "heartbeatPassesFirstSeenAfterBaseline": 0, "expectedApkSha256": expected_hash,
              "collectorSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "identityCheckedAtEndpointsOnly": True,
              "limitations": ["Logcat is a bounded buffer; events before baseline are not new progress.",
                  "Subsequent means first seen after baseline, not independently proven event time or autonomy.",
                  "A heartbeat-origin record proves an observed worker maintenance pass, not exact scheduling or successful external delivery.",
                  "Queue/UNKNOWN and database sizes are sampled; no observations between passes are inferred.",
                  "Pruning counters describe the explicit maintenance-policy batch, not every recovery cleanup.",
                  "ADB polling changes battery/idle conditions; this is not undisturbed battery qualification."]}
    history = History(); start = monotonic(); previous_poll = None
    try:
        qemu = adb("shell", "getprop", "ro.kernel.qemu")
        if qemu not in ("", "0", "1") or environment not in ("PHYSICAL_DEVICE", "ANDROID_EMULATOR") or (qemu == "1") != (environment == "ANDROID_EMULATOR"):
            raise ValueError("Environment mismatch.")
        report["deviceIdentitySha256"] = OBS.device_identity(adb)
        if OBS.installed_hash(adb) != expected_hash: raise ValueError("Installed APK mismatch.")
        uid = own_uid(adb)
        report["androidRuntimeEvidence"] = "NON_DEFINITIVE" if qemu == "1" else "PHYSICAL_OBSERVATION_ONLY"
        start = monotonic()
        with (directory / "maintenance-history.jsonl").open("x") as stream:
            for index in range(limit):
                poll = monotonic()
                if previous_poll is not None and poll - previous_poll > interval * 2: report["samplingGaps"] += 1
                previous_poll = poll
                sample = {"index": index, "elapsedHostSeconds": round(poll - start, 3), "baseline": index == 0,
                          "events": [], "unavailable": []}
                try:
                    boot = OBS.integer(adb("shell", "settings", "get", "global", "boot_count"))
                    values = records(adb("logcat", "-d", "-v", "raw", "--uid=" + str(uid), TAG + ":I", "*:S"))
                    if any(v["bootCount"] > boot for v in values): raise ValueError("Inconsistent boot snapshot.")
                    sample["bootCount"] = boot
                    sample["events"] = history.accept(values, baseline=index == 0)
                    field = "baselineEvents" if index == 0 else "firstSeenSubsequentEvents"
                    report[field] += len(sample["events"])
                    if index != 0:
                        report["heartbeatPassesFirstSeenAfterBaseline"] += sum(v["origin"] == "HEARTBEAT" for v in sample["events"])
                except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired):
                    sample["unavailable"].append("maintenanceProtocol")
                    report["samplesWithGaps"] += 1
                stream.write(json.dumps(sample, sort_keys=True) + "\n"); stream.flush()
                report["samples"] += 1
                if monotonic() - start >= duration: break
                sleep(max(0, min(interval, duration - (monotonic() - start))))
        if OBS.installed_hash(adb) != expected_hash or own_uid(adb) != uid or OBS.device_identity(adb) != report["deviceIdentitySha256"]:
            raise ValueError("Endpoint identity mismatch.")
        if not any(report[k] for k in ("samplesWithGaps", "samplingGaps")) and history.sequence_gaps == 0 and monotonic() - start >= duration:
            report["result"] = "HISTORY_COLLECTION_COMPLETE_REVIEW_REQUIRED"
    except KeyboardInterrupt:
        report["interrupted"] = True
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        report["failureType"] = type(error).__name__
    finally:
        report.update(elapsedHostSeconds=round(monotonic() - start, 3), sequenceGaps=history.sequence_gaps,
                      duplicateObservations=history.duplicates,
                      queueHistoryAvailable=bool(history.seen))
        samples = directory / "maintenance-history.jsonl"
        if samples.exists(): report["historySha256"] = hashlib.sha256(samples.read_bytes()).hexdigest()
        (directory / "maintenance-history-report.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=shutil.which("adb")); parser.add_argument("--serial", required=True)
    parser.add_argument("--environment", required=True, choices=("PHYSICAL_DEVICE", "ANDROID_EMULATOR"))
    parser.add_argument("--apk", type=Path, required=True); parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--duration-seconds", type=float, default=3600); parser.add_argument("--interval-seconds", type=float, default=60)
    args = parser.parse_args()
    try:
        OBS.bounds(args.duration_seconds, args.interval_seconds)
        if not args.adb or not args.apk.is_file() or args.evidence_dir.exists(): raise ValueError("Provide host ADB, an APK and a NEW evidence directory.")
    except ValueError as error: parser.error(str(error))
    result = observe(OBS.Adb(args.adb, args.serial), args.evidence_dir, hashlib.sha256(args.apk.read_bytes()).hexdigest(),
                     args.environment, args.duration_seconds, args.interval_seconds)
    print(json.dumps(result, indent=2))
    return 0 if result["result"] == "HISTORY_COLLECTION_COMPLETE_REVIEW_REQUIRED" else 1


if __name__ == "__main__": raise SystemExit(main())
