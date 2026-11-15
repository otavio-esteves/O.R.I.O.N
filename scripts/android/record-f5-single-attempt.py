#!/usr/bin/env python3
"""Linux HOST, physical-only single-attempt capture. Always NOT_QUALIFIED; no admission inputs."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
import selectors
from pathlib import Path
import subprocess
import uuid


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


BASE = module("orion_single_baseline", "record-f5-physical-baseline.py")
CLOCK = module("orion_single_clock", "qualification_host_clock.py")
PREFLIGHT = BASE.PREFLIGHT
Rejected, require = BASE.Rejected, BASE.require
PACKAGES = BASE.PACKAGES
RUNNER = PACKAGES["test"] + "/network.orion.app.compatibility.OrionCortexSingleAttemptInstrumentation"
HEADER, PARTIAL, RELEASE = "orionSingleAttemptStatus", "orionSingleAttemptPartial", "orionSingleAttemptReleaseStatus"
STREAM_OK = "SINGLE_ATTEMPT_PARTIAL / NOT_QUALIFIED"
STREAM_NOT_ATTEMPTED = "DRIVER_NOT_ATTEMPTED / NOT_QUALIFIED"
STREAM_FAILURE = "SINGLE_ATTEMPT_UNAVAILABLE / NOT_QUALIFIED"
MAX_OUTPUT = 64 * 1024
TRANSPORT_TIMEOUT_SECONDS = 180  # HOST transport watchdog only, never a request/calibration duration.
LONG_MAX = 2**63 - 1
FLAGS = set("sourceAuthenticated physicalVerified profileApproved productionInferenceApproved runtimeEvidenceV1Complete "
            "fullF5GateClosed fullSoakGateClosed f6Authorized".split())
HEADER_KEYS = FLAGS | set("schemaVersion kind qualification phase failureCode refusal environment environmentSource counterScope "
                         "prepareCalls executeCalls readCalls releaseCalls ownerCallInvoked partialPresent".split())
FAILURE_KEYS = FLAGS | set("schemaVersion kind qualification phase failureCode environment ownerCallInvoked".split())
REFUSALS = set("OWNER_UNAVAILABLE CLOSED NO_QUALIFIED_PROFILE RESOURCE_CONFIGURATION_UNAVAILABLE CORE_NOT_READY OBSERVER_UNAVAILABLE "
               "TEMPLATE_UNAVAILABLE TEMPLATE_INVALID MODEL_NOT_READY PROFILE_MISMATCH ALREADY_CONSUMED SELECTION_UNAVAILABLE".split())
BRIDGE_FAILURES = {None, "ALREADY_USED", "PHASE_ORDER", "OWNER_UNAVAILABLE", "METADATA_UNAVAILABLE", "OBSERVATION_UNAVAILABLE", "RELEASE_UNAVAILABLE"}
RUNNER_FAILURES = set("ARGUMENTS_REJECTED BRIDGE_UNAVAILABLE PREPARE_FAILED EXECUTE_FAILED OBSERVE_FAILED RELEASE_FAILED INTERRUPTED FRAMING_INVALID".split())
ENVIRONMENTS = {"PHYSICAL_DEVICE", "ANDROID_EMULATOR", "UNVERIFIED"}
COUNTS = ("prepareCalls", "executeCalls", "readCalls", "releaseCalls")
TERMINALS = set("SUCCEEDED CANCELLED TIMED_OUT FAILED PROCESS_DIED".split())
OWNER_UNAVAILABLE = set("CLOSED NO_QUALIFIED_PROFILE RESOURCE_CONFIGURATION_UNAVAILABLE CORE_NOT_READY PROCESS_INVENTORY_UNAVAILABLE "
                        "MODEL_NOT_READY MODEL_UNAVAILABLE PROFILE_MISMATCH REQUEST_RETIRED".split())
UNLOADS = set("UNLOADED_OBSERVED NO_ENDPOINT IDENTITY_UNAVAILABLE ENDPOINT_CHANGED CLIENT_CLOSED EXECUTION_CHANGED DEADLINE_EXPIRED "
             "TIME_REFERENCE_INVALID STATUS_INVALID TRANSPORT_FAILURE UNLOAD_REQUEST_FAILED POLL_TIMEOUT CANCELLED OBSERVATION_UNAVAILABLE".split())
METRICS = ("promptTokens", "sampledTokens", "eogTokens", "callbacksAttempted", "callbacksReturnedNormally", "prefillCalls",
           "prefillSuccessfulCalls", "prefillSubmittedTokens", "prefillTokensInSuccessfulCalls", "decodeCalls", "decodeSuccessfulCalls",
           "decodeSubmittedTokens", "decodeTokensInSuccessfulCalls", "tokenizationApiWallNanos", "prefillApiWallNanos", "decodeApiWallNanos")


FAILURE_CODES = frozenset("""
HOST_CLOCK_METADATA_INVALID METRICS_RELATION_INVALID METRICS_STAGES_INVALID ADB_COMMAND_NOT_ALLOWED ADB_OBSERVATION_UNAVAILABLE ADB_OUTPUT_BOUND_EXCEEDED ADB_TRANSPORT_TIMEOUT
ANDROID_BOOT_OR_WINDOW_CHANGED API_UNAVAILABLE ARTIFACT_PROVENANCE_MISMATCH BOOT_CHANGED_DURING_OBSERVATION
BOOT_ID_UNAVAILABLE BOOT_OR_MEMORY_IDENTITY_CHANGED DEVICE_IDENTITY_CHANGED DEVICE_MODEL_HASH_MISMATCH
DEVICE_MODEL_NOT_REGULAR DEVICE_OFFLINE_OR_AMBIGUOUS DUPLICATE_MANIFEST_FIELD DUPLICATE_OR_UNORDERED_SUMMARY
DUPLICATE_RECEIPT_FIELD DUPLICATE_RESULT_KEY EXPLICIT_PHYSICAL_SERIAL_REQUIRED FAILURE_PARTIAL_FORBIDDEN
HOST_PROGRAM_GAP INCOMPLETE_BASELINE INCOMPLETE_RECEIPT_FRAMING INSTALLED_APK_CHANGED
INSTALLED_APK_HASH_UNAVAILABLE INSTALLED_APK_MISMATCH INSTRUMENTATION_OUTPUT_INVALID INVALID_ARTIFACT_MANIFEST
INVALID_FAILURE_RECEIPT INVALID_MANIFEST INVALID_MEASUREMENT_STREAM INVALID_MODEL_MANIFEST
INVALID_REQUIRED_ARTIFACTS INVALID_SAMPLE_BOUNDS INVALID_SOURCE_COMMIT INVALID_SOURCE_MANIFEST
MANIFEST_TOO_LARGE_OR_UNSAFE METRICS_CLOCK_INVALID METRICS_FINAL_INVALID METRICS_INVALID METRICS_MASK_INVALID
METRICS_STATE_INVALID MODEL_HASH_OR_SIZE_MISMATCH NONFINITE_RECEIPT_VALUE NOT_ATTEMPTED_INVALID
OBSERVATION_PHASE_INVALID OBSERVATION_WINDOW_INVALID OUTCOME_INVALID OUTPUT_BOUND_INVALID PARTIAL_IDENTITY_INVALID
PARTIAL_PAIR_INVALID PHASE_COUNTS_INVALID PHYSICAL_SIGNALS_UNAVAILABLE_OR_EMULATED PREPARE_REFUSAL_INVALID
REAL_HOST_CLOCK_REQUIRED RECEIPT_AUTHORITY_INVALID RECEIPT_BOOLEAN_INVALID RECEIPT_ENUM_INVALID
RECEIPT_FIELDS_INVALID RECEIPT_JSON_INVALID RECEIPT_NOT_OBJECT RECEIPT_NUMBER_INVALID RECEIPT_SIZE_INVALID
RELEASE_CONTROL_INVALID RELEASE_CONTROL_MISSING RESOURCE_BOUNDS_INVALID RESOURCE_DECISION_INVALID
RESOURCE_KIND_INVALID RESOURCE_SNAPSHOT_INVALID RESULT_CODE_INVALID RESULT_FRAMING_INCOMPLETE RESULT_KEYS_INVALID
RESULT_TEXT_BOUND_INVALID RUNNER_ENVIRONMENT_MISMATCH RUNNER_FAILURE_INVALID SAMPLE_BOOT_OR_PROCESS_CHANGED
SAMPLE_CADENCE_INVALID SAMPLE_FIELDS_INVALID SAMPLE_IDENTITY_INVALID SAMPLE_MEMORY_INVALID
SAMPLE_NOT_LIGHTWEIGHT_OR_COMPLETE SAMPLE_NUMBERS_INVALID SAMPLE_PLATFORM_METRICS_INVALID SAMPLE_PROCESS_INVALID
SAMPLE_SEQUENCE_INVALID SAMPLE_STALE_OR_OUTSIDE_WINDOW SECOND_INVOCATION_FORBIDDEN SECTION_PRESENCE_INVALID
SOURCE_COMMIT_MISMATCH SOURCE_COVERAGE_MISMATCH SOURCE_HASH_MISMATCH SOURCE_IDENTITY_UNAVAILABLE
SOURCE_OR_LOCAL_ARTIFACT_CHANGED SOURCE_ROOT_MISMATCH SOURCE_TREE_DIRTY STATUS_IDENTITY_INVALID
STREAM_FRAMING_INVALID SUMMARY_AUTHORITY_INVALID SUMMARY_FIELDS_INVALID SUMMARY_IDENTITY_INVALID
SUMMARY_RESULT_INVALID SUMMARY_SAMPLE_COUNT_INVALID SURPLUS_INSTRUMENTATION_OUTPUT SURPLUS_OR_UNORDERED_SAMPLE
SURPLUS_OUTPUT SYNTHETIC_DEVICE_LABEL SYNTHETIC_OR_UNCLASSIFIED_MODEL TARGET_ABI_OR_PAGE_SIZE_UNSUPPORTED
TARGET_API_MISMATCH TARGET_INVALID TARGET_MODEL_MISMATCH TARGET_MODEL_UNAVAILABLE TOTAL_MEMORY_UNAVAILABLE
UNEXPECTED_INSTRUMENTATION_OUTPUT UNEXPECTED_OUTPUT UNEXPECTED_SAMPLE_STATUS UNLOAD_INVALID UNSAFE_MODEL_FILENAME
UNSAFE_OR_EMPTY_FILE UNSAFE_OR_SPLIT_APK_PATH UNSAFE_SOURCE_MANIFEST UPTIME_UNAVAILABLE
""".split())


def instrumentation_command():
    return ("shell", "am", "instrument", "-w", "-r", RUNNER)


def allowed_command(command):
    return command == instrumentation_command() or (command[:3] != ("shell", "am", "instrument") and BASE.allowed_command(command))


class CaptureAdb:
    """Closed HOST ADB queries plus one fixed instrumentation command; no device cleanup/kill."""
    def __init__(self, executable, serial):
        require(type(serial) is str and PREFLIGHT.SERIAL.fullmatch(serial) and not serial.startswith("emulator-"),
                "EXPLICIT_PHYSICAL_SERIAL_REQUIRED")
        self.prefix = [executable, "-s", serial]
        self.issued = False

    def __call__(self, *command):
        require(allowed_command(command), "ADB_COMMAND_NOT_ALLOWED")
        instrument = command == instrumentation_command()
        if instrument:
            require(not self.issued, "SECOND_INVOCATION_FORBIDDEN")
            self.issued = True
        timeout = TRANSPORT_TIMEOUT_SECONDS if instrument else 10
        timing = CLOCK.HostClock.linux()  # No fallback; no child is created without the real HOST source.
        started = timing.read_ns()
        deadline = started + timeout * CLOCK.NANOS
        selector = selectors.DefaultSelector()
        process = None
        try:
            data = bytearray()
            require(timing.read_ns() < deadline, "ADB_TRANSPORT_TIMEOUT")
            process = subprocess.Popen(self.prefix + list(command), stdin=subprocess.DEVNULL,
                                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            output = process.stdout
            require(output is not None, "ADB_OBSERVATION_UNAVAILABLE")
            os.set_blocking(output.fileno(), False)
            selector.register(output, selectors.EVENT_READ)
            eof = False
            while True:
                now = timing.read_ns()
                require(now < deadline, "ADB_TRANSPORT_TIMEOUT")
                # HOST pipe readiness only, never another Android command or completion poll.
                events = selector.select(min(0.1, (deadline - now) / CLOCK.NANOS))
                if events and not eof:
                    try:
                        block = os.read(output.fileno(), min(16 * 1024, MAX_OUTPUT + 1 - len(data)))
                    except BlockingIOError:
                        continue
                    if block:
                        require(len(data) + len(block) <= MAX_OUTPUT, "ADB_OUTPUT_BOUND_EXCEEDED")
                        data.extend(block)
                    else:
                        eof = True
                        selector.unregister(output)
                if eof and process.poll() is not None:
                    break
            require(process.returncode == 0, "ADB_OBSERVATION_UNAVAILABLE")
            return data.decode("utf-8", errors="strict").strip()
        finally:
            selector.close()
            # Only this Popen's local HOST client. Never a process group, ADB server,
            # Android PID/package or a second command. Android outcome stays unknown.
            if process is not None:
                try:
                    if process.poll() is None:
                        process.kill()
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=1)
                except (OSError, subprocess.TimeoutExpired):
                    pass
                if process.stdout is not None:
                    process.stdout.close()



def keys(value, expected):
    require(type(value) is dict and set(value) == set(expected), "RECEIPT_FIELDS_INVALID")


def integer(value, minimum=0, maximum=LONG_MAX):
    require(type(value) is int and minimum <= value <= maximum, "RECEIPT_NUMBER_INVALID")


def boolean(value, nullable=False):
    require(type(value) is bool or nullable and value is None, "RECEIPT_BOOLEAN_INVALID")


def enum(value, choices, nullable=False):
    require(nullable and value is None or type(value) is str and value in choices, "RECEIPT_ENUM_INVALID")


def flags(value, names=FLAGS):
    require(all(type(value[key]) is bool and value[key] is False for key in names), "RECEIPT_AUTHORITY_INVALID")


def document(text, maximum):
    require(type(text) is str and 0 < len(text.encode("utf-8")) <= maximum, "RECEIPT_SIZE_INVALID")
    try:
        return BASE.receipt_json(text)
    except (ValueError, RuntimeError):
        raise Rejected("RECEIPT_JSON_INVALID") from None


def status(text):
    value = document(text, 1024)
    failure = value.get("kind") == "CORTEX_SINGLE_ATTEMPT_RUNNER_FAILURE"
    keys(value, FAILURE_KEYS if failure else HEADER_KEYS)
    integer(value["schemaVersion"], 1, 1)
    require(value["qualification"] == "NOT_QUALIFIED", "RECEIPT_AUTHORITY_INVALID")
    flags(value)
    boolean(value["ownerCallInvoked"], True)
    if failure:
        require(value["phase"] == "RUNNER_FAILED" and value["environment"] == "UNVERIFIED" and
                value["ownerCallInvoked"] is None, "RUNNER_FAILURE_INVALID")
        enum(value["failureCode"], RUNNER_FAILURES)
        return value
    require(value["kind"] == "CORTEX_SINGLE_ATTEMPT_STATUS" and value["environmentSource"] == "BUILD_SIGNALS_ONLY" and
            value["counterScope"] == "DRIVER_METHOD_CALLS", "STATUS_IDENTITY_INVALID")
    enum(value["environment"], ENVIRONMENTS)
    enum(value["phase"], {"PREPARE_REFUSED", "SELECTED", "EXECUTE_NOT_ATTEMPTED", "EXECUTE_INVOKED", "OBSERVED",
                          "OBSERVATION_UNAVAILABLE", "RELEASED", "RELEASE_NOT_AVAILABLE", "BRIDGE_REFUSED",
                          "BRIDGE_UNAVAILABLE", "EXECUTE_RETURNED_METADATA_UNAVAILABLE"})
    enum(value["refusal"], REFUSALS, True)
    enum(value["failureCode"], BRIDGE_FAILURES, True)
    boolean(value["partialPresent"])
    for name in COUNTS:
        integer(value[name], 0, 1)
    return value


def counts(value, expected):
    require(tuple(value[key] for key in COUNTS) == expected, "PHASE_COUNTS_INVALID")


def section(value, callback):
    keys(value, {"presence", "value"})
    enum(value["presence"], {"ABSENT", "PRESENT", "UNAVAILABLE"})
    if value["presence"] != "PRESENT":
        require(value["value"] is None, "SECTION_PRESENCE_INVALID")
    else:
        callback(value["value"])


def outcome(value):
    require(type(value) is dict, "RECEIPT_FIELDS_INVALID")
    if value.get("kind") == "UNAVAILABLE":
        keys(value, {"kind", "reason"}); enum(value["reason"], OWNER_UNAVAILABLE)
    else:
        keys(value, {"kind", "stage", "terminal", "failure"})
        require(value["kind"] == "COMPLETED", "OUTCOME_INVALID")
        enum(value["stage"], {"CREATED", "LOADING", "DECODING", "TERMINAL"})
        enum(value["terminal"], TERMINALS, True)
        enum(value["failure"], set("SCHEMA_MISMATCH INVALID_TIME_REFERENCE REORDER_LIMIT CONFLICTING_CHUNK CHUNK_TOO_LARGE REMOTE_ERROR TRANSPORT_FAILURE".split()), True)
        require((value["stage"] == "TERMINAL") == (value["terminal"] is not None), "OUTCOME_INVALID")


def stream(value):
    keys(value, {"acceptedChunkObservationsAtLeast", "callbackAttemptsAtLeast", "callbacksReturnedNormallyAtLeast"})
    for item in value.values():
        integer(item)
    # Independently lossy lower bounds are not comparable; missing writes can reverse apparent order.


def resources(value):
    require(type(value) is list and len(value) <= 12, "RESOURCE_BOUNDS_INVALID")
    base = set("callback phase endpointPresent snapshotPresent snapshotLimitationsMask kind block".split())
    for item in value:
        require(type(item) is dict, "RECEIPT_FIELDS_INVALID")
        governor = item.get("kind") == "GOVERNOR"
        keys(item, base | ({"allowedAsReported", "memoryRisk"} if governor else
                          {"eligibleAsReported", "memoryDeathsAsReported", "resourceDeathsAsReported", "degradedAsReported", "preloadAllowedAsReported"}))
        enum(item["callback"], {"RESOURCE"} if governor else {"RESTART"})
        enum(item["phase"], {"BEFORE_LOAD", "LOAD", "INFERENCE"})
        boolean(item["endpointPresent"]); boolean(item["snapshotPresent"])
        mask = item["snapshotLimitationsMask"]
        require((mask is not None) == item["snapshotPresent"], "RESOURCE_SNAPSHOT_INVALID")
        if mask is not None:
            integer(mask, 0, 127)
        if governor:
            boolean(item["allowedAsReported"]); enum(item["memoryRisk"], {"UNKNOWN", "LOW", "HIGH", "CRITICAL"})
            enum(item["block"], set("OBSERVATION_UNAVAILABLE OBSERVATION_INVALID OBSERVATION_STALE EXIT_HISTORY_UNAVAILABLE MODEL_UNQUALIFIED BACKGROUND BATTERY THERMAL POWER_SAVER REQUEST_LIMITS MEMORY_PRESSURE MEMORY_EXIT MEMORY_BUDGET RESOURCE_EXIT".split()), True)
            require(item["allowedAsReported"] == (item["block"] is None), "RESOURCE_DECISION_INVALID")
        else:
            require(item["kind"] == "RESTART", "RESOURCE_KIND_INVALID")
            boolean(item["eligibleAsReported"])
            enum(item["block"], set("HISTORY_UNAVAILABLE HISTORY_INVALID HISTORY_STALE COOLDOWN FALLBACK_EXHAUSTED".split()), True)
            require(item["eligibleAsReported"] == (item["block"] is None), "RESOURCE_DECISION_INVALID")
            boolean(item["degradedAsReported"]); boolean(item["preloadAllowedAsReported"])
            for name in ("memoryDeathsAsReported", "resourceDeathsAsReported"):
                integer(item[name], 0, 64)
            require(item["memoryDeathsAsReported"] + item["resourceDeathsAsReported"] <= 64, "RESOURCE_BOUNDS_INVALID")


def native(value):
    names = set("logicalTerminalAsReported nativeStateAsReported nativeFinishAsReported slotStateAsReported snapshotStateAsReported "
                "partialWorkAsReported completedCallStagesMask timeStateAsReported availableMetricsMask apiWallClockDomain metrics".split())
    keys(value, names)
    enum(value["logicalTerminalAsReported"], TERMINALS, True)
    for name, choices in {
        "nativeStateAsReported": "NOT_STARTED RUNNING JNI_RETURNED NOT_EXECUTED UNKNOWN",
        "nativeFinishAsReported": "NONE SUCCEEDED CANCELLED TOKEN_LIMIT UNKNOWN",
        "slotStateAsReported": "OWNED RELEASED UNKNOWN", "snapshotStateAsReported": "UPDATING FINAL UNKNOWN",
        "partialWorkAsReported": "NONE_OBSERVED POSSIBLE UNKNOWN", "timeStateAsReported": "KNOWN_BOOT UNAVAILABLE DISCONTINUOUS",
    }.items():
        enum(value[name], set(choices.split()))
    integer(value["completedCallStagesMask"], 0, 7); integer(value["availableMetricsMask"], 0, 65535)
    require(type(value["metrics"]) is dict, "METRICS_INVALID")
    expected = {name for index, name in enumerate(METRICS) if value["availableMetricsMask"] & (1 << index)}
    require(set(value["metrics"]) == expected, "METRICS_MASK_INVALID")
    durations = False
    for index, name in enumerate(METRICS):
        if name not in expected:
            continue
        limit = LONG_MAX if index >= 13 else 1 if index == 2 else 32768 if index in (0, 5, 6, 7, 8) else 4096
        integer(value["metrics"][name], 1 if index == 0 else 0, limit)
        durations |= index >= 13
    require(value["apiWallClockDomain"] == ("STEADY_CLOCK_NS" if durations else None), "METRICS_CLOCK_INVALID")
    if value["snapshotStateAsReported"] == "FINAL":
        require(value["logicalTerminalAsReported"] is not None and value["slotStateAsReported"] == "RELEASED" and
                value["nativeStateAsReported"] in {"JNI_RETURNED", "NOT_EXECUTED"}, "METRICS_FINAL_INVALID")
    if value["nativeStateAsReported"] in {"NOT_STARTED", "NOT_EXECUTED"}:
        require(not expected and value["completedCallStagesMask"] == 0 and value["nativeFinishAsReported"] == "NONE", "METRICS_STATE_INVALID")
    if value["nativeFinishAsReported"] not in {"NONE", "UNKNOWN"}:
        require(value["nativeStateAsReported"] == "JNI_RETURNED", "METRICS_STATE_INVALID")


    # Check only intrinsic relations whose operands are actually present. The
    # stripped partial cannot recover admitted model/request bounds or identity.
    metrics = value["metrics"]
    for a, b in [("eogTokens", "sampledTokens"), ("callbacksAttempted", "sampledTokens"), ("callbacksReturnedNormally", "callbacksAttempted"),
                 ("prefillSuccessfulCalls", "prefillCalls"), ("prefillSuccessfulCalls", "prefillTokensInSuccessfulCalls"),
                 ("prefillTokensInSuccessfulCalls", "prefillSubmittedTokens"), ("prefillCalls", "prefillSubmittedTokens"),
                 ("prefillSubmittedTokens", "promptTokens"), ("decodeSuccessfulCalls", "decodeCalls"),
                 ("decodeTokensInSuccessfulCalls", "decodeSubmittedTokens"), ("decodeSubmittedTokens", "callbacksReturnedNormally")]:
        if a in metrics and b in metrics:
            require(metrics[a] <= metrics[b], "METRICS_RELATION_INVALID")
    for a, b in [("decodeSubmittedTokens", "decodeCalls"), ("decodeTokensInSuccessfulCalls", "decodeSuccessfulCalls")]:
        if a in metrics and b in metrics:
            require(metrics[a] == metrics[b], "METRICS_RELATION_INVALID")
    for a, b in [("prefillCalls", "prefillSubmittedTokens"), ("prefillSuccessfulCalls", "prefillTokensInSuccessfulCalls")]:
        if a in metrics and b in metrics:
            require((metrics[a] == 0) == (metrics[b] == 0), "METRICS_RELATION_INVALID")
    if {"sampledTokens", "eogTokens", "callbacksAttempted"} <= set(metrics):
        require(metrics["callbacksAttempted"] <= metrics["sampledTokens"] - metrics["eogTokens"], "METRICS_RELATION_INVALID")
    stages = value["completedCallStagesMask"]
    if stages & 1:
        require("promptTokens" in metrics, "METRICS_STAGES_INVALID")
    if stages & 2:
        require(stages & 1 and "promptTokens" in metrics and metrics.get("prefillTokensInSuccessfulCalls") == metrics["promptTokens"], "METRICS_STAGES_INVALID")
    if stages & 4:
        require(stages & 2 and value["nativeStateAsReported"] == "JNI_RETURNED" and
                value["nativeFinishAsReported"] in {"SUCCEEDED", "TOKEN_LIMIT"}, "METRICS_STAGES_INVALID")


def unload(value):
    keys(value, set("endpointPresent deadlineReferencePresent pollReferencePresent noEndpointAsReported unloadedReplyAsReported validUnloadedReplyAsReported failure".split()))
    for key in set(value) - {"failure"}:
        boolean(value[key])
    enum(value["failure"], UNLOADS, True)
    require(not value["validUnloadedReplyAsReported"] or value["unloadedReplyAsReported"], "UNLOAD_INVALID")


def loss(value):
    keys(value, {"observerGlobalDroppedAtLeast", "observerGlobalRecordingFailuresAtLeast", "lossAccountingCompleteAsReported"})
    integer(value["observerGlobalDroppedAtLeast"]); integer(value["observerGlobalRecordingFailuresAtLeast"])
    boolean(value["lossAccountingCompleteAsReported"])


def partial(text):
    value = document(text, 8192)
    sections = {"outcome": outcome, "stream": stream, "resources": resources, "native": native, "unload": unload,
                "sourceIssueMask": lambda v: integer(v, 0, 255), "observerLoss": loss}
    fixed_false = (FLAGS - {"runtimeEvidenceV1Complete"}) | {"runtimeProtocolV1Complete", "identifiersRetained", "timestampsRetained"}
    keys(value, fixed_false | set(sections) | set("format schemaVersion qualification retainedSnapshot sourceStateAsReported sourceAsReported "
                                                "throwClassificationAsReported actualTurnEnteredAsReported inferenceAcceptedAsReported".split()))
    require(value["format"] == "ORION_MAIN_CYCLE_PARTIAL" and value["qualification"] == "NOT_QUALIFIED" and
            value["retainedSnapshot"] is True, "PARTIAL_IDENTITY_INVALID")
    integer(value["schemaVersion"], 1, 1); flags(value, fixed_false)
    enum(value["sourceStateAsReported"], {"PENDING", "NO_ATTEMPT", "RETAINED_RESULT", "INCOMPLETE"})
    enum(value["sourceAsReported"], {"NOT_REACHED", "NO_ATTEMPT", "RETAINED_RESULT", "LOGICAL_OWNER", "TURN_ENTERED"})
    enum(value["throwClassificationAsReported"], {"CANCELLED", "INTERRUPTED", "OTHER"}, True)
    boolean(value["actualTurnEnteredAsReported"], True); boolean(value["inferenceAcceptedAsReported"], True)
    for name, callback in sections.items():
        section(value[name], callback)
    return value


def validate_pairs(bundle, code, stream_value):
    require(HEADER in bundle and set(bundle) <= {HEADER, PARTIAL, RELEASE}, "RESULT_KEYS_INVALID")
    primary = status(bundle[HEADER]); failure = primary["kind"] == "CORTEX_SINGLE_ATTEMPT_RUNNER_FAILURE"
    projected = partial(bundle[PARTIAL]) if PARTIAL in bundle else None
    control = status(bundle[RELEASE]) if RELEASE in bundle else None
    expected_stream = STREAM_FAILURE if failure else STREAM_NOT_ATTEMPTED if primary["phase"] in {
        "PREPARE_REFUSED", "EXECUTE_NOT_ATTEMPTED"} else STREAM_OK
    require(code == (0 if failure else -1) and stream_value == expected_stream, "RESULT_CODE_INVALID")
    if failure:
        require(projected is None, "FAILURE_PARTIAL_FORBIDDEN")
    else:
        require(primary["partialPresent"] == (projected is not None), "PARTIAL_PAIR_INVALID")
        phase = primary["phase"]
        if phase == "PREPARE_REFUSED":
            counts(primary, (1, 0, 0, 0))
            require(primary["refusal"] in REFUSALS and primary["ownerCallInvoked"] is None and projected is None and control is None and
                    primary["failureCode"] is None, "PREPARE_REFUSAL_INVALID")
        elif phase == "EXECUTE_NOT_ATTEMPTED":
            counts(primary, (1, 1, 0, 0))
            require(primary["refusal"] in REFUSALS and primary["ownerCallInvoked"] is False and projected is None and control is None and
                    primary["failureCode"] is None, "NOT_ATTEMPTED_INVALID")
        else:
            require(phase in {"OBSERVED", "OBSERVATION_UNAVAILABLE"} and primary["ownerCallInvoked"] is True and primary["refusal"] is None,
                    "OBSERVATION_PHASE_INVALID")
            require(primary["prepareCalls"] == primary["executeCalls"] == 1 and primary["releaseCalls"] == 0, "PHASE_COUNTS_INVALID")
            require(control is not None, "RELEASE_CONTROL_MISSING")
            if phase == "OBSERVED":
                require(primary["readCalls"] == 1 and primary["failureCode"] is None, "OBSERVATION_PHASE_INVALID")
            else:
                require(projected is None and primary["failureCode"] == "OBSERVATION_UNAVAILABLE", "OBSERVATION_PHASE_INVALID")
    if control is not None:
        require(control["kind"] == "CORTEX_SINGLE_ATTEMPT_STATUS" and control["phase"] in {"RELEASED", "RELEASE_NOT_AVAILABLE"} and
                not control["partialPresent"], "RELEASE_CONTROL_INVALID")
        require(control["prepareCalls"] == control["executeCalls"] == control["releaseCalls"] == 1, "RELEASE_CONTROL_INVALID")
        require((control["refusal"] is not None) == (control["ownerCallInvoked"] is False), "RELEASE_CONTROL_INVALID")
        if not failure:
            require(control["environment"] == primary["environment"] and control["readCalls"] == primary["readCalls"] and
                    control["ownerCallInvoked"] is True and control["refusal"] is None, "RELEASE_CONTROL_INVALID")
        require(control["failureCode"] == (None if control["phase"] == "RELEASED" else control["failureCode"]) and
                control["failureCode"] in {None, "RELEASE_UNAVAILABLE"}, "RELEASE_CONTROL_INVALID")
    return {"header": primary, "partial": projected, "releaseControl": control,
            "programOutcome": "RUNNER_FAILURE" if failure else primary["phase"]}


def receipts(output):
    """Offline framing parser only; accepting a transcript never verifies physical origin."""
    require(type(output) is str and 0 < len(output.encode("utf-8")) <= MAX_OUTPUT, "OUTPUT_BOUND_INVALID")
    bundle, stream_value, pending_stream, code = {}, None, False, None
    for line in output.splitlines():
        if not line.strip():
            continue
        require(code is None, "SURPLUS_OUTPUT")
        if line.startswith("INSTRUMENTATION_RESULT: "):
            require(not pending_stream, "STREAM_FRAMING_INVALID")
            name, separator, text = line.removeprefix("INSTRUMENTATION_RESULT: ").partition("=")
            require(separator and name in {HEADER, PARTIAL, RELEASE, "stream"}, "RESULT_KEYS_INVALID")
            if name == "stream":
                require(stream_value is None, "DUPLICATE_RESULT_KEY")
                stream_value = text
                pending_stream = not text
            else:
                require(name not in bundle, "DUPLICATE_RESULT_KEY")
                bundle[name] = text
        elif pending_stream and line in {STREAM_OK, STREAM_FAILURE, STREAM_NOT_ATTEMPTED}:
            stream_value, pending_stream = line, False
        elif line in {"INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"}:
            require(not pending_stream and stream_value is not None, "STREAM_FRAMING_INVALID")
            code = int(line.rsplit(" ", 1)[1])
        else:
            raise Rejected("UNEXPECTED_OUTPUT")
    require(code is not None and not pending_stream and stream_value is not None, "RESULT_FRAMING_INCOMPLETE")
    # Producer puts a newline before/after stream; account for both, conservatively
    # also when a transport rendered it inline. HOST does not measure Parcel bytes.
    text_bytes = sum(len(k.encode()) + len(v.encode()) for k, v in bundle.items()) + len("stream") + len(stream_value.encode()) + 2
    require(text_bytes <= 10 * 1024, "RESULT_TEXT_BOUND_INVALID")
    return validate_pairs(bundle, code, stream_value)



def clock_metadata(timing):
    value = timing.metadata
    keys(value, {"schemaVersion", "clockSource", "bootIdentitySha256", "sessionId", "evidence", "qualification"})
    integer(value["schemaVersion"], 1, 1)
    enum(value["clockSource"], {"CLOCK_BOOTTIME", "SYNTHETIC_INJECTED", "UNAVAILABLE"})
    enum(value["evidence"], {"HOST_CLOCK_ONLY", "UNVERIFIED_INPUT", "UNAVAILABLE"})
    require(value["qualification"] == "NOT_QUALIFIED", "HOST_CLOCK_METADATA_INVALID")
    require(value["bootIdentitySha256"] is None or type(value["bootIdentitySha256"]) is str and
            PREFLIGHT.SHA.fullmatch(value["bootIdentitySha256"]), "HOST_CLOCK_METADATA_INVALID")
    require(type(value["sessionId"]) is str and len(value["sessionId"]) == 36 and
            str(uuid.UUID(value["sessionId"])) == value["sessionId"], "HOST_CLOCK_METADATA_INVALID")
    return dict(value)


def record(adb, serial, target, source_root, source_manifest, app_apk, test_apk, directory, clock=None):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=False, mode=0o700)
    report = {"schemaVersion": 1, "captureIntegrity": "REJECTED", "qualification": "NOT_QUALIFIED", "environment": "HOST",
              "sourceAuthenticated": False, "physicalVerified": False, "profileApproved": False, "productionInferenceApproved": False,
              "runtimeEvidenceV1Complete": False, "fullF5GateClosed": False, "fullSoakGateClosed": False, "f6Authorized": False,
              "modelReadyAssociationProven": False, "inferenceLatencyMeasured": False, "continuityProven": False,
              "installPerformed": False, "deviceSettingsChanged": False, "possibleTechnicalBootstrapWrites": True,
              "instrumentationCommandIssued": False, "invocationOutcome": "NOT_ISSUED", "automaticReplayPermitted": False,
              "recordedAtUtc": datetime.now(timezone.utc).isoformat(), "recorderSha256": PREFLIGHT.sha_file(__file__),
              "buildProvenance": "MANIFEST_BOUND_REVIEW_REQUIRED", "targetPackage": PACKAGES["app"]}
    try:
        require(type(serial) is str and PREFLIGHT.SERIAL.fullmatch(serial) and not serial.startswith("emulator-"), "EXPLICIT_PHYSICAL_SERIAL_REQUIRED")
        require(target in {"S21", "API37"}, "TARGET_INVALID")
        timing = CLOCK.clock_for(clock=clock)
        report["hostTiming"] = clock_metadata(timing)
        require(timing.metadata["clockSource"] == "CLOCK_BOOTTIME" and timing.metadata["evidence"] == "HOST_CLOCK_ONLY", "REAL_HOST_CLOCK_REQUIRED")
        timing.read_ns()
        source, expected = PREFLIGHT.source_identity(source_root, source_manifest, required_artifacts=frozenset({"app", "test"}))
        local = {"app": PREFLIGHT.sha_file(app_apk, 1024**3), "test": PREFLIGHT.sha_file(test_apk, 1024**3)}
        require(local == expected, "ARTIFACT_PROVENANCE_MISMATCH")
        identity = BASE.physical_identity(adb, serial, target)
        installed = {key: PREFLIGHT.installed_hash(adb, package) for key, package in PACKAGES.items()}
        require(installed == local, "INSTALLED_APK_MISMATCH")
        before = BASE.device_window(adb)
        started = timing.read_ns()
        report.update(source=source, artifactSha256=local, installedApkSha256=installed,
                      target={k: v for k, v in identity.items() if k not in {"manufacturer", "model"}}, windowBefore=before)
        report["instrumentationCommandIssued"] = True
        report["invocationOutcome"] = "UNKNOWN"
        output = adb(*instrumentation_command())
        ended = timing.read_ns()
        report["hostProgramSpanNanoseconds"] = ended - started
        require(ended - started <= TRANSPORT_TIMEOUT_SECONDS * CLOCK.NANOS, "HOST_PROGRAM_GAP")
        after = BASE.device_window(adb)
        require(before["bootIdSha256"] == after["bootIdSha256"] and before["elapsedLowerMillis"] <= after["elapsedLowerMillis"] and
                before["totalMemoryBytes"] == after["totalMemoryBytes"], "ANDROID_BOOT_OR_WINDOW_CHANGED")
        require({key: PREFLIGHT.installed_hash(adb, package) for key, package in PACKAGES.items()} == installed, "INSTALLED_APK_CHANGED")
        require(BASE.physical_identity(adb, serial, target) == identity, "DEVICE_IDENTITY_CHANGED")
        require(PREFLIGHT.source_identity(source_root, source_manifest, required_artifacts=frozenset({"app", "test"}))[0] == source and
                {"app": PREFLIGHT.sha_file(app_apk, 1024**3), "test": PREFLIGHT.sha_file(test_apk, 1024**3)} == local, "SOURCE_OR_LOCAL_ARTIFACT_CHANGED")
        finished = timing.read_ns()
        require(finished - started <= TRANSPORT_TIMEOUT_SECONDS * CLOCK.NANOS, "HOST_PROGRAM_GAP")
        parsed = receipts(output)
        require(parsed["header"]["environment"] in {"PHYSICAL_DEVICE", "UNVERIFIED"} and
                (parsed["releaseControl"] is None or parsed["releaseControl"]["environment"] != "ANDROID_EMULATOR"),
                "RUNNER_ENVIRONMENT_MISMATCH")
        if parsed["header"]["kind"] != "CORTEX_SINGLE_ATTEMPT_RUNNER_FAILURE":
            require(parsed["header"]["environment"] == "PHYSICAL_DEVICE", "RUNNER_ENVIRONMENT_MISMATCH")
        report.update(captureIntegrity="COMPLETE" if parsed["programOutcome"] != "RUNNER_FAILURE" else "INCOMPLETE",
                      invocationOutcome="PROGRAM_OBSERVED" if parsed["programOutcome"] != "RUNNER_FAILURE" else "UNKNOWN",
                      windowAfter=after, capture=parsed, hostTiming=clock_metadata(timing))
    except Rejected as error:
        code = str(error)
        report["failureCode"] = code if code in FAILURE_CODES else "INPUT_OR_OBSERVATION_UNAVAILABLE"
        if code in {"HOST_PROGRAM_GAP", "ADB_TRANSPORT_TIMEOUT"} and report["instrumentationCommandIssued"]:
            report["captureIntegrity"] = "INCOMPLETE"
    except CLOCK.HostTimingError as error:
        report["failureCode"] = error.code if error.code in CLOCK.FAILURES else "HOST_CLOCK_UNAVAILABLE"
        if report["instrumentationCommandIssued"]:
            report["captureIntegrity"] = "INCOMPLETE"
    except KeyboardInterrupt:
        report["failureCode"] = "INTERRUPTED"
        if report["instrumentationCommandIssued"]:
            report["captureIntegrity"] = "INCOMPLETE"
    except Exception:
        report["failureCode"] = "INPUT_OR_OBSERVATION_UNAVAILABLE"
        if report["instrumentationCommandIssued"]:
            report["captureIntegrity"] = "INCOMPLETE"
    finally:
        (directory / "f5-single-attempt.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    return report


class ClosedArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, "CLI_ARGUMENTS_INVALID: explicit physical inputs required; no execution configuration accepted.\n")


def main():
    parser = ClosedArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--target", required=True, choices=("S21", "API37"))
    for name in ("source-root", "source-manifest", "app-apk", "test-apk", "evidence-dir"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    if not args.adb:
        parser.error("An explicit HOST ADB executable is required.")
    try:
        result = record(CaptureAdb(args.adb, args.serial), args.serial, args.target, args.source_root,
                        args.source_manifest, args.app_apk, args.test_apk, args.evidence_dir)
    except (OSError, ValueError):
        parser.error("A new writable evidence directory and explicit physical serial are required.")
    print(result["captureIntegrity"] + ": NOT_QUALIFIED; no profile, runtime, F5 or soak approval.")
    raise SystemExit(0 if result["captureIntegrity"] == "COMPLETE" else 2)


if __name__ == "__main__":
    main()
