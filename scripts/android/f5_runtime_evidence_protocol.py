#!/usr/bin/env python3
"""Offline schema/consistency checks only. Supplied JSON is never physical or profile authority."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat

MAX_DOCUMENT_BYTES = 4 * 1024 * 1024
MAX_IDENTITY_BYTES = 4096
MAX_CHUNKS = 4097
MAX_SAMPLES = 128
LONG_MAX = 2**63 - 1
TERMINALS = {"SUCCEEDED", "CANCELLED", "TIMED_OUT", "FAILED", "PROCESS_DIED"}
FAILURES = {"SCHEMA_MISMATCH", "INVALID_TIME_REFERENCE", "REORDER_LIMIT", "CONFLICTING_CHUNK",
            "CHUNK_TOO_LARGE", "REMOTE_ERROR", "TRANSPORT_FAILURE"}
PHASES = ("BEFORE_LOAD", "DURING_LOAD", "AFTER_LOAD", "DURING_INFERENCE", "AFTER_TERMINAL",
          "DURING_UNLOAD", "AFTER_UNLOAD")
IDENTITY_KEYS = set("sourceCommit sourceManifestSha256 appPackage testPackage appApkSha256 testApkSha256 modelSha256 modelSizeBytes".split())
SESSION_KEYS = set("bootCount bootIdSha256 mainPid aiPid startedElapsed completedElapsed maximumSampleAgeMillis processInventory "
                   "api abi pageSizeBytes deviceBuildSha256 totalMemoryBytes".split())
MEMORY_KEYS = set("phase startedElapsed completedElapsed bootCount mainPid aiPid mainSampledElapsed aiSampledElapsed mainPssBytes "
                  "aiPssBytes aggregatePssBytes mainNativeIncluded aiNativeIncluded source serviceState nativeRuntimeLoaded "
                  "nativeValidationCompleted".split())


class Rejected(ValueError):
    """Only fixed codes, never supplied values, are emitted as diagnostics."""


def require(condition, code):
    if not condition:
        raise Rejected(code)


def fields(value, keys, code):
    require(type(value) is dict and set(value) == keys, code)


def integer(value, minimum=0, maximum=LONG_MAX):
    require(type(value) is int and minimum <= value <= maximum, "INTEGER_INVALID")


def digest(value, length=64):
    require(type(value) is str and re.fullmatch("[a-f0-9]{" + str(length) + "}", value) is not None
            and any(character != "0" for character in value), "HASH_INVALID")


def member(value, choices, code):
    require(type(value) is str and value in choices, code)


def decode(data, maximum):
    require(type(data) is bytes and 0 < len(data) <= maximum, "DOCUMENT_SIZE_INVALID")

    def unique(pairs):
        require(len(pairs) <= 32, "OBJECT_SIZE_INVALID")
        result = {}
        for key, value in pairs:
            require(key not in result, "DUPLICATE_JSON_KEY")
            result[key] = value
        return result

    def constant(_):
        raise Rejected("NONFINITE_NUMBER")

    try:
        value = json.loads(data.decode("utf-8", errors="strict"), object_pairs_hook=unique, parse_constant=constant)
    except Rejected:
        raise
    except (ValueError, UnicodeError, RecursionError, OverflowError):
        raise Rejected("JSON_INVALID") from None
    pending = [(value, 0)]
    nodes = 0
    while pending:
        node, depth = pending.pop()
        nodes += 1
        require(depth <= 12 and nodes <= 100_000, "JSON_COMPLEXITY_INVALID")
        if type(node) is float:
            require(math.isfinite(node), "NONFINITE_NUMBER")
        elif type(node) is dict:
            pending.extend((item, depth + 1) for item in node.values())
        elif type(node) is list:
            require(len(node) <= MAX_CHUNKS, "ARRAY_SIZE_INVALID")
            pending.extend((item, depth + 1) for item in node)
    return value


def identity(value):
    fields(value, IDENTITY_KEYS, "IDENTITY_FIELDS_INVALID")
    digest(value["sourceCommit"], 40)
    for key in ("sourceManifestSha256", "appApkSha256", "testApkSha256", "modelSha256"):
        digest(value[key])
    member(value["appPackage"], {"network.orion", "network.orion.qualification"}, "PACKAGE_INVALID")
    require(value["testPackage"] == value["appPackage"] + ".test", "PACKAGE_INVALID")
    integer(value["modelSizeBytes"], 1, 4 * 1024**3)


def session(value, app):
    fields(value, SESSION_KEYS, "SESSION_FIELDS_INVALID")
    integer(value["bootCount"], 0, 2**31 - 1)
    digest(value["bootIdSha256"])
    digest(value["deviceBuildSha256"])
    for key in ("mainPid", "aiPid"):
        integer(value[key], 1, 2**31 - 1)
    require(value["mainPid"] != value["aiPid"], "PID_COLLISION")
    integer(value["startedElapsed"])
    integer(value["completedElapsed"])
    require(value["startedElapsed"] <= value["completedElapsed"], "SESSION_TIME_INVALID")
    # This is a record-format ceiling, not a calibrated production freshness setting.
    integer(value["maximumSampleAgeMillis"], 1, 1000)
    require(type(value["processInventory"]) is list and
            value["processInventory"] == [app, app + ":ai"], "INVENTORY_INVALID")
    integer(value["api"], 31, 37)
    member(value["abi"], {"arm64-v8a", "x86_64"}, "ABI_INVALID")
    integer(value["pageSizeBytes"], 4096, 16384)
    require(value["pageSizeBytes"] in (4096, 16384), "PAGE_SIZE_INVALID")
    integer(value["totalMemoryBytes"], 64 * 1024**2, 1024**4)


def window(value, start_key, end_key, scope):
    integer(value[start_key])
    integer(value[end_key])
    start, end = value[start_key], value[end_key]
    require(scope["startedElapsed"] <= start <= end <= scope["completedElapsed"], "OPERATION_TIME_INVALID")
    return start, end


def inference(value, model, scope, load):
    fields(value, set("requestIdSha256 transportRequestIdSha256 modelSha256 startedElapsed terminalElapsed deadlineElapsed inputBytes "
                      "terminal terminalSource failureCode chunks".split()), "INFERENCE_FIELDS_INVALID")
    for key in ("requestIdSha256", "transportRequestIdSha256", "modelSha256"):
        digest(value[key])
    require(value["modelSha256"] == model and len({value["requestIdSha256"], value["transportRequestIdSha256"],
            load["requestIdSha256"]}) == 3, "REQUEST_IDENTITY_INVALID")
    start, end = window(value, "startedElapsed", "terminalElapsed", scope)
    require(start >= load["completedElapsed"], "INFERENCE_BEFORE_LOAD")
    integer(value["deadlineElapsed"])
    require(start < value["deadlineElapsed"] <= start + 120_000, "DEADLINE_INVALID")
    integer(value["inputBytes"], 1, 1024**2)
    member(value["terminal"], TERMINALS, "TERMINAL_INVALID")
    member(value["terminalSource"], {"CALLBACK", "MAIN_LIFECYCLE"}, "TERMINAL_SOURCE_INVALID")
    # MAIN checks its deadline before cancellation, transport/death signals and
    # every callback. A remote TIMED_OUT may be delivered before MAIN expires;
    # MAIN's own timeout must instead be at/after its deadline.
    if value["terminalSource"] == "MAIN_LIFECYCLE" and value["terminal"] == "TIMED_OUT":
        require(end >= value["deadlineElapsed"], "TIMEOUT_BEFORE_DEADLINE")
    else:
        require(end < value["deadlineElapsed"], "TERMINAL_AFTER_DEADLINE")
    if value["failureCode"] is not None:
        member(value["failureCode"], FAILURES, "FAILURE_CODE_INVALID")
        require(value["terminal"] == "FAILED", "FAILURE_WITHOUT_FAILED_TERMINAL")
    chunks = value["chunks"]
    require(type(chunks) is list and len(chunks) <= MAX_CHUNKS, "CHUNK_COUNT_INVALID")
    total, last_at, final_count = 0, start, 0
    for index, chunk in enumerate(chunks):
        fields(chunk, set("requestIdSha256 sequence receivedElapsed textDeltaBytes toolDeltaBytes isFinal finishReason errorCode".split()),
               "CHUNK_FIELDS_INVALID")
        require(chunk["requestIdSha256"] == value["requestIdSha256"], "CHUNK_IDENTITY_INVALID")
        integer(chunk["sequence"])
        require(chunk["sequence"] == index, "CHUNK_SEQUENCE_INVALID")
        integer(chunk["receivedElapsed"])
        require(last_at <= chunk["receivedElapsed"] <= end and
                chunk["receivedElapsed"] < value["deadlineElapsed"], "CHUNK_TIME_INVALID")
        last_at = chunk["receivedElapsed"]
        integer(chunk["textDeltaBytes"], 0, 64 * 1024)
        integer(chunk["toolDeltaBytes"], 0, 64 * 1024)
        size = chunk["textDeltaBytes"] + chunk["toolDeltaBytes"]
        require(size <= 64 * 1024, "CHUNK_CONTENT_SIZE_INVALID")
        total += size
        require(type(chunk["isFinal"]) is bool, "CHUNK_BOOLEAN_INVALID")
        if chunk["isFinal"]:
            final_count += 1
            require(index == len(chunks) - 1 and chunk["finishReason"] == value["terminal"] and
                    chunk["errorCode"] == value["failureCode"], "CHUNK_TERMINAL_INVALID")
        else:
            require(chunk["finishReason"] is None and chunk["errorCode"] is None, "NONFINAL_TERMINAL_INVALID")
    if value["terminalSource"] == "CALLBACK":
        require(final_count == 1 and last_at == end and value["terminal"] != "PROCESS_DIED", "FINAL_CALLBACK_INVALID")
    else:
        require(final_count == 0 and value["terminal"] != "SUCCEEDED", "MAIN_TERMINAL_INVALID")
    return total


def memory(rows, scope, load, infer, unload):
    require(type(rows) is list and len(rows) <= MAX_SAMPLES, "MEMORY_COUNT_INVALID")
    present, previous_end, previous_phase = set(), scope["startedElapsed"], -1
    runtime_seen = False
    loaded = load["terminal"] == "SUCCEEDED"
    death_at = (infer["terminalElapsed"] if infer and infer["terminal"] == "PROCESS_DIED" else
                load["completedElapsed"] if load["terminal"] == "PROCESS_DIED" else None)
    for row in rows:
        fields(row, MEMORY_KEYS, "MEMORY_FIELDS_INVALID")
        member(row["phase"], set(PHASES), "MEMORY_PHASE_INVALID")
        phase = PHASES.index(row["phase"])
        start, end = window(row, "startedElapsed", "completedElapsed", scope)
        require(start >= previous_end and phase >= previous_phase, "MEMORY_ORDER_INVALID")
        previous_end, previous_phase = end, phase
        for key in ("bootCount", "mainPid", "aiPid"):
            integer(row[key])
            require(row[key] == scope[key], "MEMORY_SESSION_MISMATCH")
        for key in ("mainSampledElapsed", "aiSampledElapsed"):
            integer(row[key])
        require(start == row["mainSampledElapsed"] <= row["aiSampledElapsed"] <= end and
                end - start < scope["maximumSampleAgeMillis"], "MEMORY_FRESHNESS_INVALID")
        for key in ("mainPssBytes", "aiPssBytes", "aggregatePssBytes"):
            integer(row[key], 1, min(LONG_MAX, scope["totalMemoryBytes"]))
        require(row["aggregatePssBytes"] == row["mainPssBytes"] + row["aiPssBytes"], "MEMORY_TOTAL_INVALID")
        require(type(row["mainNativeIncluded"]) is bool and row["mainNativeIncluded"] and
                type(row["aiNativeIncluded"]) is bool and row["aiNativeIncluded"], "NATIVE_COVERAGE_MISSING")
        require(row["source"] == "SELF_DEBUG_MEMORY_INFO_PSS", "MEMORY_SOURCE_INVALID")
        for key in ("nativeRuntimeLoaded", "nativeValidationCompleted"):
            require(type(row[key]) is bool, "MEMORY_BOOLEAN_INVALID")
        require(not row["nativeValidationCompleted"] or row["nativeRuntimeLoaded"], "NATIVE_STATE_INVALID")
        require(not runtime_seen or row["nativeRuntimeLoaded"], "NATIVE_WRAPPER_DISAPPEARED")
        runtime_seen = runtime_seen or row["nativeRuntimeLoaded"]
        require(death_at is None or end <= death_at, "MEMORY_AFTER_PROCESS_DEATH")
        if phase == 0:
            lower, upper, states = scope["startedElapsed"], load["startedElapsed"], {"UNLOADED"}
        elif phase == 1:
            lower, upper, states = load["startedElapsed"], load["completedElapsed"], {"LOADING", "LOADED"}
        elif phase == 2:
            require(loaded, "MEMORY_AFTER_FAILED_LOAD")
            lower, upper, states = load["completedElapsed"], (infer or unload or scope)["startedElapsed"], {"LOADED"}
            if infer is None and unload is None:
                upper = scope["completedElapsed"]
        elif phase in (3, 4):
            require(infer is not None, "MEMORY_WITHOUT_LIVE_INFERENCE")
            require(phase != 4 or infer["terminal"] != "PROCESS_DIED", "MEMORY_AFTER_PROCESS_DEATH")
            if phase == 3:
                lower, upper, states = infer["startedElapsed"], infer["terminalElapsed"], {"INFERENCING", "LOADED"}
            else:
                lower, upper, states = infer["terminalElapsed"], (unload or scope)["startedElapsed"], {"LOADED"}
                if unload is None:
                    upper = scope["completedElapsed"]
        else:
            require(unload is not None, "MEMORY_WITHOUT_UNLOAD")
            if phase == 5:
                lower, upper, states = unload["startedElapsed"], unload["completedElapsed"], {"UNLOADING", "UNLOADED"}
            else:
                require(unload["result"] == "UNLOADED", "MEMORY_AFTER_FAILED_UNLOAD")
                lower, upper, states = unload["completedElapsed"], scope["completedElapsed"], {"UNLOADED"}
        require(lower <= start <= end <= upper, "MEMORY_PHASE_TIME_INVALID")
        member(row["serviceState"], states, "MEMORY_SERVICE_STATE_INVALID")
        # The current wrapper persists after model unload; runtime != model residency.
        if loaded and phase >= 2 or row["serviceState"] in {"LOADED", "INFERENCING"}:
            require(row["nativeRuntimeLoaded"] and row["nativeValidationCompleted"], "NATIVE_STATE_INVALID")
        present.add(row["phase"])
    return [phase for phase in PHASES if phase not in present]


def report():
    return {"protocolVersion": 1, "result": "INVALID_INPUT", "schemaValid": False,
            "evidenceTrust": "UNVERIFIED_INPUT", "qualification": "NOT_QUALIFIED",
            "provenanceAuthenticated": False, "physicalObservationVerified": False,
            "profileQualified": False, "productionInferenceEnabled": False,
            "fullF5GateClosed": False, "fullSoakGateClosed": False,
            "tokenMetrics": "UNAVAILABLE_NO_PRODUCER", "prefillDecodeMetrics": "UNAVAILABLE_NO_PRODUCER"}


def validate_json(data, expected_identity):
    value = decode(data, MAX_DOCUMENT_BYTES)
    expected = decode(expected_identity, MAX_IDENTITY_BYTES)
    fields(value, set("schemaVersion kind declaredEnvironment identity session candidate load inference unload memory".split()),
           "DOCUMENT_FIELDS_INVALID")
    require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 1 and
            value["kind"] == "ORION_F5_RUNTIME_EVIDENCE", "PROTOCOL_INVALID")
    member(value["declaredEnvironment"], {"PHYSICAL_DEVICE", "ANDROID_EMULATOR", "SYNTHETIC_INPUT"}, "ENVIRONMENT_INVALID")
    identity(value["identity"])
    identity(expected)
    require(value["identity"] == expected, "EXPECTED_IDENTITY_MISMATCH")
    scope, candidate, load, infer, unload = (value[key] for key in ("session", "candidate", "load", "inference", "unload"))
    session(scope, expected["appPackage"])
    fields(candidate, {"contextTokens", "threads", "maxTokens", "temperature"}, "CANDIDATE_FIELDS_INVALID")
    integer(candidate["contextTokens"], 32, 32768)
    integer(candidate["threads"], 1, 16)
    integer(candidate["maxTokens"], 1, 4096)
    require(type(candidate["temperature"]) in (int, float) and 0 <= candidate["temperature"] <= 2 and
            math.isfinite(candidate["temperature"]), "TEMPERATURE_INVALID")
    fields(load, {"requestIdSha256", "modelSha256", "startedElapsed", "completedElapsed", "terminal"}, "LOAD_FIELDS_INVALID")
    digest(load["requestIdSha256"])
    require(load["modelSha256"] == expected["modelSha256"], "LOAD_MODEL_MISMATCH")
    window(load, "startedElapsed", "completedElapsed", scope)
    member(load["terminal"], TERMINALS, "LOAD_TERMINAL_INVALID")
    total = 0
    if infer is not None:
        require(load["terminal"] == "SUCCEEDED", "INFERENCE_WITHOUT_LOADED_MODEL")
        total = inference(infer, expected["modelSha256"], scope, load)
    died = load["terminal"] == "PROCESS_DIED" or infer is not None and infer["terminal"] == "PROCESS_DIED"
    if unload is not None:
        require(not died, "UNLOAD_AFTER_PROCESS_DEATH")
        fields(unload, {"startedElapsed", "completedElapsed", "result"}, "UNLOAD_FIELDS_INVALID")
        window(unload, "startedElapsed", "completedElapsed", scope)
        require(unload["startedElapsed"] >= (infer["terminalElapsed"] if infer else load["completedElapsed"]),
                "UNLOAD_BEFORE_TERMINAL")
        member(unload["result"], {"UNLOADED", "FAILED"}, "UNLOAD_RESULT_INVALID")
    missing = memory(value["memory"], scope, load, infer, unload)
    complete = load["terminal"] == "SUCCEEDED" and infer is not None and not died and unload is not None and unload["result"] == "UNLOADED" and not missing
    result = report()
    result.update(result="CONSISTENT_CYCLE_METADATA" if complete else "CONSISTENT_INCOMPLETE_METADATA", schemaValid=True,
                  declaredEnvironment=value["declaredEnvironment"], expectedIdentityMatched=True,
                  inputSha256=hashlib.sha256(data).hexdigest(), inferenceTerminal=infer["terminal"] if infer else None,
                  successfulInferenceReported=infer is not None and infer["terminal"] == "SUCCEEDED",
                  streamedContentBytes=total, missingMemoryPhases=missing, memorySampleCount=len(value["memory"]))
    return result


def read_document(path, maximum):
    descriptor = os.open(path, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
    with os.fdopen(descriptor, "rb") as source:
        require(stat.S_ISREG(os.fstat(source.fileno()).st_mode), "INPUT_FILE_INVALID")
        data = source.read(maximum + 1)
    require(0 < len(data) <= maximum, "DOCUMENT_SIZE_INVALID")
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--expected-identity", type=Path, required=True)
    args = parser.parse_args()
    result = report()
    try:
        result = validate_json(read_document(args.evidence, MAX_DOCUMENT_BYTES),
                               read_document(args.expected_identity, MAX_IDENTITY_BYTES))
    except Rejected as failure:
        result["rejectionCode"] = str(failure)
    except OSError:
        result["rejectionCode"] = "INPUT_FILE_UNAVAILABLE"
    print(json.dumps(result, sort_keys=True, allow_nan=False))
    raise SystemExit(0 if result["schemaValid"] else 2)


if __name__ == "__main__":
    main()
