#!/usr/bin/env python3
"""Offline validation of untrusted CAPTURE_ONLY output, never limiter/policy qualification."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat

from f5_limiter_evidence_protocol import Rejected, require, parse_receipt, fields, integer, choice, digest

PACKAGE = "network.orion.qualification"
AI = PACKAGE + ":ai"
FLAGS = "provenanceAuthenticated physicalDeviceVerified memoryLimiterVerified profileQualified productionInferenceEnabled fullF5GateClosed fullSoakGateClosed heavyWorkIssued policyMutationIssued".split()
COMMON = "schemaVersion kind environment qualification runtimeEvidence result cleanup".split() + FLAGS
CAPTURE = COMMON + "packageName declaredEnvironment mainPid correlation bindingOutcome binderDeathCleanup matchedRecordSha256 firstLiveObservedElapsed identity policy config platform window liveObservations historyBefore historyAfter".split()
REJECTION = COMMON + ["failureCode"]
FAILURES = {"ARGUMENTS_REJECTED", "TARGET_IDENTITY_REJECTED", "ENVIRONMENT_OR_PLATFORM_REJECTED", "BIND_UNAVAILABLE",
            "CAPTURE_FAILED", "CAPTURE_TIMEOUT", "INTERRUPTED"}
RESULTS = {"AI_NOT_AVAILABLE", "BOOT_UNAVAILABLE", "BOOT_CHANGED", "LIVE_OBSERVATION_FAILED", "AI_PROCESS_DIED",
           "AI_STILL_ALIVE", "ENDPOINT_IDENTITY_CHANGED", "ENDPOINT_DISCONNECTED"}
KINDS = {0: "UNKNOWN", 1: "NORMAL_EXIT", 2: "SIGNALLED", 3: "LOW_MEMORY", 4: "JVM_CRASH", 5: "NATIVE_CRASH",
         6: "ANR", 7: "INITIALIZATION_FAILURE", 8: "PERMISSION_CHANGED", 9: "EXCESSIVE_RESOURCE_USAGE",
         10: "USER_REQUESTED", 11: "USER_STOPPED", 12: "DEPENDENCY_DIED", 13: "OTHER_SYSTEM", 14: "FREEZER",
         15: "PACKAGE_CHANGED", 16: "PACKAGE_UPDATED"}


def incident(record):
    return tuple(record[key] for key in ("pid", "processName", "timestampWallMillis", "reason"))


def stamp(value):
    fields(value, "bootCount elapsedMillis wallMillis".split())
    if value["bootCount"] is not None:
        integer(value["bootCount"], 0, 2**31 - 1)
    integer(value["elapsedMillis"])
    integer(value["wallMillis"])


def history(value, limit, dedicated):
    fields(value, "status queriedCount records".split())
    choice(value["status"], {"AVAILABLE", "TRUNCATED", "UNAVAILABLE"})
    require(type(value["records"]) is list and len(value["records"]) <= limit + 1, "HISTORY_SIZE_INVALID")
    if value["status"] == "UNAVAILABLE":
        require(value["queriedCount"] is None and not value["records"], "UNAVAILABLE_HISTORY_INVALID")
        return
    integer(value["queriedCount"], 0, limit + 1)
    require((value["status"] == "TRUNCATED") == (value["queriedCount"] > limit) and
            len(value["records"]) <= value["queriedCount"], "HISTORY_COUNT_INVALID")
    seen = set()
    for record in value["records"]:
        fields(record, "recordSha256 pid processName timestampWallMillis reason descriptionEvidence classification".split())
        integer(record["pid"], 1, 2**31 - 1)
        require(record["processName"] == AI, "PROCESS_NAME_INVALID")
        integer(record["timestampWallMillis"])
        integer(record["reason"], 0, 2**31 - 1)
        choice(record["descriptionEvidence"], {"OTHER_ANON_SWAP_MARKER", "NO_MATCH", "UNAVAILABLE"})
        require(record["descriptionEvidence"] != "OTHER_ANON_SWAP_MARKER" or record["reason"] == 13, "MARKER_REASON_INVALID")
        limiter = record["reason"] == dedicated or record["reason"] == 13 and record["descriptionEvidence"] == "OTHER_ANON_SWAP_MARKER"
        require(record["classification"] == ("MEMORY_LIMITER" if limiter else KINDS.get(record["reason"], "UNKNOWN")), "CLASSIFICATION_INVALID")
        key = "|".join(str(record[k]) for k in ("pid", "processName", "timestampWallMillis", "reason", "descriptionEvidence", "classification"))
        expected = hashlib.sha256(key.encode()).hexdigest()
        digest(record["recordSha256"])
        require(record["recordSha256"] == expected and incident(record) not in seen, "RECORD_DIGEST_INVALID")
        seen.add(incident(record))


def validate_json(data):
    value = parse_receipt(data)
    require(type(value) is dict, "DOCUMENT_INVALID")
    rejection = value.get("kind") == "ORION_F5_EXIT_CAPTURE_REJECTION"
    fields(value, REJECTION if rejection else CAPTURE)
    integer(value["schemaVersion"], 1, 1)
    require(value["qualification"] == "NOT_QUALIFIED" and value["runtimeEvidence"] == "NON_DEFINITIVE", "AUTHORITY_INVALID")
    require(all(value[key] is False for key in FLAGS), "AUTHORITY_INVALID")
    choice(value["cleanup"], {"NO_BIND_ATTEMPT", "UNBOUND", "NOT_REGISTERED", "FAILED"})
    if rejection:
        require(value["result"] == "REJECTED" and value["environment"] == "UNVERIFIED", "REJECTION_INVALID")
        choice(value["failureCode"], FAILURES)
        return report("CONSISTENT_REJECTION_METADATA", value)
    require(value["kind"] == "ORION_F5_EXIT_CAPTURE_ONLY" and value["packageName"] == PACKAGE, "IDENTITY_INVALID")
    choice(value["declaredEnvironment"], {"ANDROID_EMULATOR", "PHYSICAL_DEVICE"})
    require(value["environment"] == ("ANDROID_EMULATOR" if value["declaredEnvironment"] == "ANDROID_EMULATOR" else "PHYSICAL_BUILD_CANDIDATE"), "ENVIRONMENT_INVALID")
    fields(value["identity"], "source appApk testApk model".split())
    require(value["identity"] == {"source": "UNAVAILABLE_NOT_EMBEDDED", "appApk": "NOT_CAPTURED", "testApk": "NOT_CAPTURED", "model": "NOT_OBSERVED"}, "IDENTITY_INVALID")
    fields(value["policy"], "state revision fence cooldown".split())
    require(all(v == "UNAVAILABLE_NOT_EXPOSED" for v in value["policy"].values()), "POLICY_ASSERTION_INVALID")
    config, platform, window = value["config"], value["platform"], value["window"]
    fields(config, "observations intervalMillis historyLimit".split())
    integer(config["observations"], 1, 10)
    integer(config["intervalMillis"], 100, 1000)
    integer(config["historyLimit"], 1, 16)
    fields(platform, "apiLevel buildFingerprintSha256 abi bootSource dedicatedReason dedicatedReasonStatus".split())
    integer(platform["apiLevel"], 31, 37)
    digest(platform["buildFingerprintSha256"])
    choice(platform["abi"], {"aarch64", "arm64", "armv7l", "arm", "x86", "x86_64", "UNAVAILABLE"})
    require(value["environment"] != "PHYSICAL_BUILD_CANDIDATE" or platform["abi"] not in {"x86", "x86_64"}, "ENVIRONMENT_INVALID")
    require(platform["bootSource"] == "SETTINGS_GLOBAL_BOOT_COUNT", "BOOT_SOURCE_INVALID")
    if platform["dedicatedReason"] is None:
        require(platform["dedicatedReasonStatus"] == "UNAVAILABLE", "DEDICATED_REASON_INVALID")
    else:
        integer(platform["dedicatedReason"], 17, 2**31 - 1)
        require(platform["dedicatedReasonStatus"] == "EXPOSED", "DEDICATED_REASON_INVALID")
    fields(window, "start end wallClockState".split())
    start, end = window["start"], window["end"]
    stamp(start)
    stamp(end)
    integer(value["mainPid"], 1, 2**31 - 1)
    choice(value["bindingOutcome"], {"CONNECTED", "NOT_BOUND", "CONNECTION_TIMEOUT", "NO_ENDPOINT"})
    choice(value["binderDeathCleanup"], {"NOT_LINKED", "UNLINKED", "ALREADY_DEAD", "FAILED"})
    choice(value["result"], RESULTS)
    live = value["liveObservations"]
    require(type(live) is list and len(live) <= config["observations"], "LIVE_COUNT_INVALID")
    same_boot = start["bootCount"] is not None and start["bootCount"] == end["bootCount"]
    if start["bootCount"] is None or end["bootCount"] is None:
        require(value["result"] == "BOOT_UNAVAILABLE", "BOOT_RESULT_INVALID")
    elif start["bootCount"] != end["bootCount"]:
        require(value["result"] == "BOOT_CHANGED", "BOOT_RESULT_INVALID")
    if same_boot:
        require(start["elapsedMillis"] <= end["elapsedMillis"], "WINDOW_INVALID")
    previous, previous_wall, backward, pid = start["elapsedMillis"], start["wallMillis"], False, None
    for row in live:
        fields(row, "pid processName bootCount readStartedElapsed readCompletedElapsed sampledElapsed observedWallMillis serviceState nativeRuntimeLoaded nativeValidationCompleted".split())
        integer(row["pid"], 1, 2**31 - 1)
        require(row["pid"] != value["mainPid"] and (pid is None or pid == row["pid"]) and row["processName"] == AI, "LIVE_IDENTITY_INVALID")
        pid = row["pid"]
        integer(row["bootCount"], 0, 2**31 - 1)
        require(row["bootCount"] == start["bootCount"], "LIVE_BOOT_INVALID")
        for key in ("readStartedElapsed", "readCompletedElapsed", "sampledElapsed", "observedWallMillis"):
            integer(row[key])
        require(previous <= row["readStartedElapsed"] <= row["sampledElapsed"] <= row["readCompletedElapsed"] and
                row["readCompletedElapsed"] - row["readStartedElapsed"] < 1000, "LIVE_TIME_INVALID")
        if same_boot:
            require(row["readCompletedElapsed"] <= end["elapsedMillis"], "LIVE_TIME_INVALID")
        previous = row["readCompletedElapsed"]
        backward |= row["observedWallMillis"] < previous_wall
        previous_wall = row["observedWallMillis"]
        choice(row["serviceState"], {"UNLOADED", "LOADING", "LOADED", "INFERENCING", "UNLOADING"})
        require(type(row["nativeRuntimeLoaded"]) is bool and type(row["nativeValidationCompleted"]) is bool and
                (not row["nativeValidationCompleted"] or row["nativeRuntimeLoaded"]), "NATIVE_FLAG_INVALID")
        require(row["serviceState"] not in {"LOADED", "INFERENCING"} or
                row["nativeRuntimeLoaded"] and row["nativeValidationCompleted"], "NATIVE_STATE_INVALID")
    expected_first = live[0]["readCompletedElapsed"] if live else None
    if value["firstLiveObservedElapsed"] is not None:
        integer(value["firstLiveObservedElapsed"])
    require(value["firstLiveObservedElapsed"] == expected_first, "FIRST_OBSERVATION_INVALID")
    require(not live or value["bindingOutcome"] == "CONNECTED", "BINDING_RESULT_INVALID")
    require(value["bindingOutcome"] == "CONNECTED" or value["result"] in {"AI_NOT_AVAILABLE", "BOOT_UNAVAILABLE", "BOOT_CHANGED"}, "BINDING_RESULT_INVALID")
    require(value["binderDeathCleanup"] == "NOT_LINKED" or value["bindingOutcome"] == "CONNECTED", "BINDING_RESULT_INVALID")
    require(not live or value["binderDeathCleanup"] != "NOT_LINKED", "BINDING_RESULT_INVALID")
    require(value["result"] not in {"AI_STILL_ALIVE", "AI_PROCESS_DIED"} or live, "LIVE_RESULT_INVALID")
    require(value["result"] != "AI_NOT_AVAILABLE" or not live, "LIVE_RESULT_INVALID")
    require(value["result"] != "AI_STILL_ALIVE" or len(live) == config["observations"], "LIVE_RESULT_INVALID")
    backward |= end["wallMillis"] < previous_wall
    require(window["wallClockState"] == ("BACKWARD_STEP_OBSERVED" if backward else "CONTINUITY_NOT_PROVABLE"), "WALL_STATE_INVALID")
    for key in ("historyBefore", "historyAfter"):
        history(value[key], config["historyLimit"], platform["dedicatedReason"])
    before, after = value["historyBefore"], value["historyAfter"]
    match = None
    if value["result"] in {"BOOT_UNAVAILABLE", "BOOT_CHANGED"}:
        correlation = "BOOT_INSUFFICIENT"
    elif not live:
        correlation = "NO_LIVE_OBSERVATION"
    elif value["result"] != "AI_PROCESS_DIED":
        correlation = "LIVE_PROCESS_NOT_DEAD"
    elif before["status"] != "AVAILABLE" or after["status"] != "AVAILABLE":
        correlation = "HISTORY_INSUFFICIENT"
    elif backward:
        correlation = "WALL_WINDOW_INSUFFICIENT"
    else:
        existing = {incident(r) for r in before["records"]}
        candidates = [r for r in after["records"] if r["pid"] == pid and incident(r) not in existing and
                      live[-1]["observedWallMillis"] <= r["timestampWallMillis"] <= end["wallMillis"]]
        correlation = "WINDOW_MATCH_CANDIDATE" if len(candidates) == 1 else "NO_MATCHING_NEW_EXIT" if not candidates else "AMBIGUOUS_EXIT_RECORDS"
        if len(candidates) == 1:
            match = candidates[0]["recordSha256"]
    require(value["correlation"] == correlation and value["matchedRecordSha256"] == match, "CORRELATION_INVALID")
    return report("CONSISTENT_CAPTURE_METADATA", value)


def report(status, value=None):
    result = {"schemaVersion": 1, "kind": "ORION_F5_EXIT_CAPTURE_OFFLINE_VALIDATION", "environment": "UNVERIFIED_INPUT",
              "qualification": "NOT_QUALIFIED", "runtimeEvidence": "NON_DEFINITIVE", "status": status}
    result.update({flag: False for flag in FLAGS})
    if value:
        result.update(captureResult=value["result"], correlation=value.get("correlation"), cleanup=value["cleanup"],
                      binderDeathCleanup=value.get("binderDeathCleanup"))
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("receipt", type=Path)
    args = parser.parse_args(argv)
    try:
        fd = os.open(args.receipt, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, "rb") as stream:
            require(stat.S_ISREG(os.fstat(stream.fileno()).st_mode), "INPUT_NOT_REGULAR")
            result = validate_json(stream.read(64 * 1024 + 1))
    except (OSError, Rejected) as error:
        result = report("REJECTED_INPUT")
        result["failureCode"] = str(error) if isinstance(error, Rejected) else "INPUT_UNAVAILABLE"
    print(json.dumps(result, sort_keys=True))
    return 2 if result["status"] == "REJECTED_INPUT" else 0


if __name__ == "__main__":
    raise SystemExit(main())
