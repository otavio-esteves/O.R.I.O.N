#!/usr/bin/env python3
"""Pure closed SOURCE/status assembly. No acquisition, persistence, transport or authority."""
import json
from typing import NamedTuple

import f5_runtime_evidence_protocol as v1

MAX_SOURCE_BYTES = 96 * 1024
MAX_STATUS_BYTES = 1024
MAX_HOST_BYTES = 4096
MAX_OUTPUT_BYTES = v1.MAX_DOCUMENT_BYTES
MAX_REPORT_BYTES = 4096
APPROVAL_FLAGS = ("sourceAuthenticated", "physicalVerified", "profileApproved", "productionInferenceApproved",
                  "runtimeEvidenceV1Complete", "fullF5GateClosed", "fullSoakGateClosed", "f6Authorized")
FAILURES = {"NO_QUALIFIED_PROFILE", "CONFIGURATION_UNAVAILABLE", "SOURCE_UNAVAILABLE", "IDENTITY_UNAVAILABLE",
            "IDENTITY_CHANGED", "CLOCK_UNAVAILABLE", "CLOCK_DISCONTINUOUS", "LOAD_TERMINAL_UNAVAILABLE",
            "INFERENCE_EVENT_UNAVAILABLE", "UNLOAD_UNCONFIRMED", "PHASE_MISSED", "CAPTURE_LOSS", "CAPTURE_LIMIT",
            "EXPORT_TOO_LARGE", "FRAMING_INVALID", "TRANSPORT_UNAVAILABLE", "INTERRUPTED"}
STATUS_KEYS = set(("schemaVersion kind qualification recordState captureIntegrity failureCode ownerCallInvoked "
                   "droppedAtLeast recordingFailuresAtLeast lossAccountingComplete").split()) | set(APPROVAL_FLAGS)
SOURCE_KEYS = set("schemaVersion kind mainIdentity session candidate load inference unload memory".split())
MAIN_IDENTITY_KEYS = set("appPackage testPackage modelSha256 modelSizeBytes".split())
MAIN_SESSION_KEYS = set("bootCount mainPid aiPid startedElapsed completedElapsed maximumSampleAgeMillis processInventory".split())
HOST_KEYS = set(("declaredEnvironment bootCount bootIdSha256 startedElapsed completedElapsed api abi pageSizeBytes "
                 "deviceBuildSha256 totalMemoryBytes").split())
HOST_ADDITIONS = set("bootIdSha256 api abi pageSizeBytes deviceBuildSha256 totalMemoryBytes".split())
INFERENCE_KEYS = set(("requestIdSha256 transportRequestIdSha256 modelSha256 startedElapsed terminalElapsed deadlineElapsed "
                      "inputBytes terminal terminalSource failureCode chunks").split())
CHUNK_KEYS = set("sequence receivedElapsed textDeltaBytes toolDeltaBytes isFinal finishReason errorCode".split())
Rejected = v1.Rejected


class Assembly(NamedTuple):
    """Detached immutable bytes only; a no-record result never manufactures a runtime document."""
    runtime_json: bytes | None
    status_json: bytes
    report_json: bytes


def _json(value, maximum):
    data = json.dumps(value, ensure_ascii=True, allow_nan=False, separators=(",", ":"), sort_keys=True).encode("ascii")
    v1.require(len(data) <= maximum, "OUTPUT_SIZE_INVALID")
    return data


def _ascii(data, maximum):
    v1.require(type(data) is bytes and data.isascii(), "ASCII_INPUT_REQUIRED")
    # The existing unchanged parser bounds bytes, duplicate keys, fields, depth, nodes and arrays.
    return v1.decode(data, maximum)


def _status(data, source_present):
    value = _ascii(data, MAX_STATUS_BYTES)
    v1.fields(value, STATUS_KEYS, "STATUS_FIELDS_INVALID")
    v1.require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 1 and
               value["kind"] == "ORION_RUNTIME_CAPTURE_STATUS" and value["qualification"] == "NOT_QUALIFIED",
               "STATUS_PROTOCOL_INVALID")
    v1.member(value["recordState"], {"NO_LOAD_REFUSAL", "NO_V1_RECORD", "V1_SOURCE"}, "RECORD_STATE_INVALID")
    v1.member(value["captureIntegrity"], {"COMPLETE", "INCOMPLETE", "UNAVAILABLE"}, "CAPTURE_INTEGRITY_INVALID")
    v1.require(value["ownerCallInvoked"] is None or type(value["ownerCallInvoked"]) is bool, "OWNER_CALL_INVALID")
    for key in APPROVAL_FLAGS:
        v1.require(value[key] is False, "APPROVAL_FLAG_INVALID")
    for key in ("droppedAtLeast", "recordingFailuresAtLeast"):
        v1.integer(value[key])
    v1.require(type(value["lossAccountingComplete"]) is bool, "LOSS_ACCOUNTING_INVALID")
    if value["droppedAtLeast"] or value["recordingFailuresAtLeast"]:
        v1.require(value["lossAccountingComplete"] is False, "LOSS_ACCOUNTING_INVALID")
    if value["failureCode"] is not None:
        v1.member(value["failureCode"], FAILURES, "STATUS_FAILURE_INVALID")
    if value["recordState"] == "V1_SOURCE":
        v1.require(source_present and value["ownerCallInvoked"] is not False, "SOURCE_STATUS_MISMATCH")
        if value["captureIntegrity"] == "COMPLETE":
            v1.require(value["failureCode"] is None and value["droppedAtLeast"] == 0 and
                       value["recordingFailuresAtLeast"] == 0 and value["lossAccountingComplete"], "SOURCE_STATUS_MISMATCH")
        else:
            v1.require(value["captureIntegrity"] == "INCOMPLETE", "SOURCE_STATUS_MISMATCH")
    else:
        v1.require(not source_present and value["captureIntegrity"] == "UNAVAILABLE", "SOURCE_STATUS_MISMATCH")
        if value["recordState"] == "NO_LOAD_REFUSAL":
            v1.require(value["ownerCallInvoked"] is False, "NO_LOAD_WITHOUT_REFUSAL_PROOF")
    return value


def _host(data):
    value = v1.decode(data, MAX_HOST_BYTES)
    v1.fields(value, HOST_KEYS, "HOST_FIELDS_INVALID")
    v1.member(value["declaredEnvironment"], {"PHYSICAL_DEVICE", "ANDROID_EMULATOR", "SYNTHETIC_INPUT"}, "ENVIRONMENT_INVALID")
    v1.integer(value["bootCount"], 0, 2**31 - 1)
    for key in ("bootIdSha256", "deviceBuildSha256"):
        v1.digest(value[key])
    for key in ("startedElapsed", "completedElapsed"):
        v1.integer(value[key])
    v1.require(value["startedElapsed"] <= value["completedElapsed"], "HOST_WINDOW_INVALID")
    v1.integer(value["api"], 31, 37)
    v1.member(value["abi"], {"arm64-v8a", "x86_64"}, "ABI_INVALID")
    v1.integer(value["pageSizeBytes"], 4096, 16384)
    v1.require(value["pageSizeBytes"] in (4096, 16384), "PAGE_SIZE_INVALID")
    v1.integer(value["totalMemoryBytes"], 64 * 1024**2, 1024**4)
    return value


def _document(source, expected, host):
    v1.fields(source, SOURCE_KEYS, "SOURCE_FIELDS_INVALID")
    v1.require(type(source["schemaVersion"]) is int and source["schemaVersion"] == 1 and
               source["kind"] == "ORION_MAIN_RUNTIME_CAPTURE", "SOURCE_PROTOCOL_INVALID")
    main = source["mainIdentity"]
    v1.fields(main, MAIN_IDENTITY_KEYS, "MAIN_IDENTITY_FIELDS_INVALID")
    v1.digest(main["modelSha256"])
    v1.integer(main["modelSizeBytes"], 1, 4 * 1024**3)
    v1.require(main["appPackage"] == "network.orion.qualification" and
               all(main[key] == expected[key] for key in MAIN_IDENTITY_KEYS), "MAIN_EXPECTED_IDENTITY_MISMATCH")
    scope = source["session"]
    v1.fields(scope, MAIN_SESSION_KEYS, "MAIN_SESSION_FIELDS_INVALID")
    for key in ("bootCount", "startedElapsed", "completedElapsed"):
        v1.integer(scope[key])
    v1.require(scope["bootCount"] == host["bootCount"] and
               host["startedElapsed"] <= scope["startedElapsed"] <= scope["completedElapsed"] <= host["completedElapsed"],
               "MAIN_HOST_WINDOW_MISMATCH")
    # MAIN values are never filled from HOST or repaired. Only the proven common chunk hash is repeated.
    infer = source["inference"]
    if infer is not None:
        v1.fields(infer, INFERENCE_KEYS, "INFERENCE_FIELDS_INVALID")
        v1.digest(infer["requestIdSha256"])
        v1.require(type(infer["chunks"]) is list and len(infer["chunks"]) <= v1.MAX_CHUNKS, "CHUNK_COUNT_INVALID")
        chunks = []
        for chunk in infer["chunks"]:
            v1.fields(chunk, CHUNK_KEYS, "SOURCE_CHUNK_FIELDS_INVALID")
            chunks.append(dict(chunk, requestIdSha256=infer["requestIdSha256"]))
        infer = dict(infer, chunks=chunks)
    v1.require(type(source["memory"]) is list and len(source["memory"]) <= v1.MAX_SAMPLES, "MEMORY_COUNT_INVALID")
    identity = {key: expected[key] for key in v1.IDENTITY_KEYS - MAIN_IDENTITY_KEYS}
    identity.update(main)
    session = dict(scope)
    session.update({key: host[key] for key in HOST_ADDITIONS})
    return {"schemaVersion": 1, "kind": "ORION_F5_RUNTIME_EVIDENCE", "declaredEnvironment": host["declaredEnvironment"],
            "identity": identity, "session": session, "candidate": source["candidate"], "load": source["load"],
            "inference": infer, "unload": source["unload"], "memory": source["memory"]}


def assemble_json(source, status, expected_identity=None, host_observations=None):
    """Assemble already-checked caller metadata; this function itself authenticates nothing.

    SOURCE/status are ASCII byte strings. Independent identity and closed HOST window/platform
    observations are UTF-8 bytes and required only when SOURCE exists. Optional supplied metadata
    is still strictly checked for no-record outcomes. No filesystem or device access occurs.
    """
    header = _status(status, source is not None)
    expected = None
    if expected_identity is not None:
        expected = v1.decode(expected_identity, v1.MAX_IDENTITY_BYTES)
        v1.identity(expected)
    host = _host(host_observations) if host_observations is not None else None
    validation = None
    runtime = None
    result = header["recordState"]
    if source is not None:
        v1.require(expected is not None and host is not None, "INDEPENDENT_METADATA_REQUIRED")
        document = _document(_ascii(source, MAX_SOURCE_BYTES), expected, host)
        if header["failureCode"] == "UNLOAD_UNCONFIRMED":
            v1.require(document["unload"] is None, "SOURCE_STATUS_MISMATCH")
        runtime = _json(document, MAX_OUTPUT_BYTES)
        validation = v1.validate_json(runtime, _json(expected, v1.MAX_IDENTITY_BYTES))
        complete = validation["result"] == "CONSISTENT_CYCLE_METADATA"
        if header["captureIntegrity"] == "COMPLETE":
            v1.require(complete, "SOURCE_STATUS_MISMATCH")
        result = "ASSEMBLED_COMPLETE_METADATA" if complete and header["captureIntegrity"] == "COMPLETE" else "ASSEMBLED_INCOMPLETE_METADATA"
    report = {"schemaVersion": 1, "kind": "ORION_RUNTIME_SOURCE_ASSEMBLY_REPORT", "result": result,
              "evidenceTrust": "UNVERIFIED_INPUT", "qualification": "NOT_QUALIFIED",
              "captureIntegrity": header["captureIntegrity"], "runtimeValidation": validation}
    report.update({key: False for key in APPROVAL_FLAGS})
    return Assembly(runtime, _json(header, MAX_STATUS_BYTES), _json(report, MAX_REPORT_BYTES))
