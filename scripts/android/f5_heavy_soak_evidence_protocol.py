#!/usr/bin/env python3
"""Pure offline structural validation. Supplied records are never execution evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import uuid

DAY = 86_400_000
MAX_BYTES = 2 * 1024 * 1024
MAX_ENTRIES = 4096
MAX_INT = 2**63 - 1
SHA = re.compile(r"[0-9a-f]{64}")
IDENTITY_KEYS = {"sourceCommit", "sourceManifestSha256", "appSha256", "testSha256", "modelSha256"}
LIMIT_KEYS = {"targetDurationMillis", "maximumAttempts", "minimumAttemptIntervalMillis", "resourceExitCooldownMillis",
              "maximumResourceDeaths", "maximumObservationGapMillis"}
REFUSALS = {"RESOURCE_COOLDOWN", "RESOURCE_LIMIT", "RESOURCE_UNAVAILABLE", "RUNNER_UNAVAILABLE",
            "CONFIGURATION_UNAVAILABLE", "ADMISSION_REFUSED"}
SERVICE_STATES = {"UNLOADED", "LOADING", "LOADED", "INFERENCING", "UNLOADING"}


class Invalid(ValueError):
    pass


def require(condition, code):
    if not condition:
        raise Invalid(code)


def fields(value, expected):
    require(type(value) is dict and set(value) == set(expected), "FIELDS_INVALID")


def integer(value, minimum=0, maximum=MAX_INT):
    require(type(value) is int and minimum <= value <= maximum, "NUMBER_INVALID")


def one_of(value, allowed):
    require(type(value) is str and value in allowed, "ENUM_INVALID")


def digest(value, optional=False):
    require(optional and value is None or type(value) is str and SHA.fullmatch(value) is not None,
            "HASH_INVALID")


def identifier(value):
    require(type(value) is str and len(value) == 36, "ID_INVALID")
    try:
        require(str(uuid.UUID(value)) == value and uuid.UUID(value).int != 0, "ID_INVALID")
    except ValueError:
        raise Invalid("ID_INVALID") from None


def identity(value):
    fields(value, IDENTITY_KEYS)
    require(type(value["sourceCommit"]) is str and re.fullmatch(r"[0-9a-f]{40}", value["sourceCommit"]) is not None,
            "COMMIT_INVALID")
    for key in IDENTITY_KEYS - {"sourceCommit"}:
        digest(value[key])


def document(path):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            require(key not in value, "DUPLICATE_FIELD")
            value[key] = item
        return value
    def constant(_):
        raise Invalid("NONFINITE_VALUE")
    selected = Path(path)
    metadata = selected.lstat()
    require(stat.S_ISREG(metadata.st_mode) and 0 < metadata.st_size <= MAX_BYTES, "INPUT_FILE_INVALID")
    # Pin the selected regular inode; a replacement link/FIFO must not be followed or block.
    descriptor = os.open(selected, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        pinned = os.fstat(stream.fileno())
        require(stat.S_ISREG(pinned.st_mode) and 0 < pinned.st_size <= MAX_BYTES, "INPUT_FILE_INVALID")
        data = stream.read(MAX_BYTES + 1)
    require(len(data) <= MAX_BYTES, "INPUT_FILE_INVALID")
    return json.loads(data.decode("utf-8"), object_pairs_hook=unique, parse_constant=constant)


def validate_plan(plan):
    fields(plan, {"schemaVersion", "kind", "planId", "identity", "requestedEnvironment", "deviceIdentitySha256",
                  "runnerSha256", "trustedConfigurationSha256", "limits", "ownedFixtureIds"})
    integer(plan["schemaVersion"], 1, 1)
    one_of(plan["kind"], {"POST_CORTEX_HEAVY_SOAK_PLAN"})
    one_of(plan["requestedEnvironment"], {"PHYSICAL_DEVICE"})
    identifier(plan["planId"])
    identity(plan["identity"])
    for key in ("runnerSha256", "trustedConfigurationSha256", "deviceIdentitySha256"):
        digest(plan[key], optional=True)
    limits = plan["limits"]
    fields(limits, LIMIT_KEYS)
    for key, minimum, maximum in (
        ("targetDurationMillis", DAY, 7 * DAY), ("maximumAttempts", 1, 256),
        ("minimumAttemptIntervalMillis", 1000, 3_600_000), ("resourceExitCooldownMillis", 1000, DAY),
        ("maximumResourceDeaths", 1, 16), ("maximumObservationGapMillis", 1000, 3_600_000),
    ):
        integer(limits[key], minimum, maximum)
    owned = plan["ownedFixtureIds"]
    require(type(owned) is list and 1 <= len(owned) <= 32, "OWNERSHIP_INVALID")
    for item in owned:
        identifier(item)
    require(len(set(owned)) == len(owned), "OWNERSHIP_INVALID")
    return plan


def plan_digest(plan):
    """Content binding only, never a signature or trusted configuration approval."""
    return hashlib.sha256(json.dumps(plan, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")).hexdigest()


def observation(data):
    fields(data, {"mainPid", "aiPid", "mainPssBytes", "aiPssBytes", "aggregatePssBytes", "thermalStatus",
                  "memoryPressure", "serviceState", "nativeRuntimeLoaded", "actionPendingCount", "actionUnknownCount"})
    # Unavailable fields are explicit nulls, never manufactured zero readings.
    resources = ("mainPid", "aiPid", "mainPssBytes", "aiPssBytes", "aggregatePssBytes", "thermalStatus", "nativeRuntimeLoaded")
    available = all(data[key] is not None for key in resources)
    if available:
        for key in ("mainPid", "aiPid"):
            integer(data[key], 1, 2**31 - 1)
        require(data["mainPid"] != data["aiPid"], "PID_INVALID")
        for key in ("mainPssBytes", "aiPssBytes", "aggregatePssBytes"):
            integer(data[key], 1, 1024**4)
        require(data["aggregatePssBytes"] == data["mainPssBytes"] + data["aiPssBytes"], "MEMORY_TOTAL_INVALID")
        integer(data["thermalStatus"], 0, 6)
        require(type(data["nativeRuntimeLoaded"]) is bool, "BOOLEAN_INVALID")
        one_of(data["serviceState"], SERVICE_STATES)
        require(data["serviceState"] not in {"LOADED", "INFERENCING"} or data["nativeRuntimeLoaded"],
                "SERVICE_RUNTIME_STATE_INVALID")
        one_of(data["memoryPressure"], {"NONE", "MODERATE", "CRITICAL"})
        # UNLOADED can retain the native wrapper. This is not the never-loaded baseline.
    else:
        require(all(data[key] is None for key in resources) and data["serviceState"] == "UNAVAILABLE" and
                data["memoryPressure"] == "UNAVAILABLE", "PARTIAL_RESOURCE_OBSERVATION")
    queues = (data["actionPendingCount"], data["actionUnknownCount"])
    require(all(value is None for value in queues) or all(type(value) is int and 0 <= value <= 1_000_000 for value in queues),
            "QUEUE_OBSERVATION_INVALID")
    return available, all(value is not None for value in queues)


def validate_journal(plan, journal):
    fields(journal, {"schemaVersion", "kind", "planId", "planSha256", "identity", "entries"})
    integer(journal["schemaVersion"], 1, 1)
    one_of(journal["kind"], {"POST_CORTEX_HEAVY_SOAK_JOURNAL"})
    require(journal["planId"] == plan["planId"] and journal["identity"] == plan["identity"], "IDENTITY_MISMATCH")
    identity(journal["identity"])
    digest(journal["planSha256"])
    require(journal["planSha256"] == plan_digest(plan), "PLAN_CONTENT_MISMATCH")
    entries = journal["entries"]
    require(type(entries) is list and len(entries) <= MAX_ENTRIES, "ENTRY_BOUND_INVALID")
    issues = set()
    intended, ready, cleanup_pending, cleaned = set(), set(), set(), set()
    attempted = set()
    pending = None
    last_work = None
    fence = None
    deaths = observations = queue_observations = interruptions = admitted = 0
    previous = first = last_observation = None
    host_sessions = set()
    pids = None
    ended = False
    limits = plan["limits"]
    for index, event in enumerate(entries):
        fields(event, {"sequence", "hostSessionId", "hostElapsedMillis", "bootCount", "deviceElapsedMillis", "kind", "data"})
        integer(event["sequence"], index, index)
        identifier(event["hostSessionId"])
        integer(event["hostElapsedMillis"])
        integer(event["bootCount"], 0, 2**31 - 1)
        integer(event["deviceElapsedMillis"])
        require(type(event["kind"]) is str, "ENUM_INVALID")
        require(not ended, "ENTRY_AFTER_END")
        if first is None:
            require(event["kind"] == "RUN_BEGIN", "BEGIN_MISSING")
            first = event
        if previous is not None:
            if event["hostSessionId"] == previous["hostSessionId"]:
                require(event["hostElapsedMillis"] >= previous["hostElapsedMillis"], "HOST_CLOCK_REGRESSION")
            else:
                require(event["hostSessionId"] not in host_sessions, "HOST_SESSION_REUSED")
                issues.add("HOST_SESSION_DISCONTINUITY")
            require(event["bootCount"] >= previous["bootCount"], "BOOT_REGRESSION")
            if event["bootCount"] == previous["bootCount"]:
                require(event["deviceElapsedMillis"] >= previous["deviceElapsedMillis"], "DEVICE_CLOCK_REGRESSION")
            else:
                pids = None
                if pending is not None:
                    issues.add("UNRESOLVED_WORK_AT_REBOOT")
                    pending = None
        host_sessions.add(event["hostSessionId"])
        kind, data = event["kind"], event["data"]
        if kind == "RUN_BEGIN":
            fields(data, set())
            require(index == 0, "DUPLICATE_BEGIN")
        elif kind in ("FIXTURE_INTENT", "FIXTURE_READY", "CLEANUP_INTENT", "CLEANUP_RESULT"):
            fields(data, {"fixtureId", "outcome"} if kind == "CLEANUP_RESULT" else {"fixtureId"})
            fixture = data["fixtureId"]
            identifier(fixture)
            require(fixture in plan["ownedFixtureIds"], "FOREIGN_FIXTURE")
            if kind == "FIXTURE_INTENT":
                require(fixture not in intended, "DUPLICATE_FIXTURE_INTENT")
                intended.add(fixture)
            elif kind == "FIXTURE_READY":
                require(fixture in intended and fixture not in ready and fixture not in cleaned and
                        fixture not in cleanup_pending, "FIXTURE_NOT_INTENDED")
                ready.add(fixture)
            elif kind == "CLEANUP_INTENT":
                require(fixture in intended and fixture not in cleanup_pending and pending is None, "CLEANUP_NOT_ALLOWED")
                cleanup_pending.add(fixture)
            else:
                require(fixture in cleanup_pending, "CLEANUP_INTENT_MISSING")
                one_of(data["outcome"], {"REMOVED", "ABSENT", "FAILED", "UNAVAILABLE"})
                cleanup_pending.remove(fixture)
                if data["outcome"] in ("REMOVED", "ABSENT"):
                    cleaned.add(fixture)
                    ready.discard(fixture)
                else:
                    cleaned.discard(fixture)
                    issues.add("CLEANUP_FAILURE_REPORTED")
        elif kind == "WORK_INTENT":
            fields(data, {"attemptId", "fixtureId", "admissionDecisionSha256"})
            identifier(data["attemptId"])
            identifier(data["fixtureId"])
            digest(data["admissionDecisionSha256"], optional=True)
            require(pending is None and data["attemptId"] not in attempted and data["fixtureId"] in ready and
                    data["fixtureId"] not in cleanup_pending, "WORK_OWNERSHIP_OR_ORDER_INVALID")
            require(len(attempted) < limits["maximumAttempts"], "ATTEMPT_BOUND_EXCEEDED")
            pending = event
            attempted.add(data["attemptId"])
        elif kind in ("WORK_RESULT", "RESOURCE_EXIT"):
            fields(data, {"attemptId", "outcome", "reason"} if kind == "WORK_RESULT" else {"attemptId", "classification"})
            require(pending is not None and data["attemptId"] == pending["data"]["attemptId"], "WORK_INTENT_MISSING")
            require(event["bootCount"] == pending["bootCount"], "WORK_BOOT_CHANGED")
            refused = False
            if kind == "WORK_RESULT":
                one_of(data["outcome"], {"SUCCEEDED", "CANCELLED", "TIMED_OUT", "FAILED", "REFUSED"})
                refused = data["outcome"] == "REFUSED"
                one_of(data["reason"], REFUSALS if refused else {"NONE"})
            else:
                one_of(data["classification"], {"MEMORY_LIMITER", "LOW_MEMORY", "EXCESSIVE_RESOURCE", "UNCLASSIFIED"})
                if data["classification"] == "UNCLASSIFIED":
                    issues.add("RESOURCE_EXIT_CLASSIFICATION_UNAVAILABLE")
            if not refused:
                require(all(plan[key] is not None for key in ("runnerSha256", "trustedConfigurationSha256", "deviceIdentitySha256")) and
                        pending["data"]["admissionDecisionSha256"] is not None, "WORK_REFERENCES_UNAVAILABLE")
            if deaths >= limits["maximumResourceDeaths"]:
                require(refused and data["reason"] == "RESOURCE_LIMIT", "RESOURCE_REFUSAL_REQUIRED")
            elif fence is not None:
                if pending["bootCount"] != fence["bootCount"]:
                    require(refused and data["reason"] == "RESOURCE_UNAVAILABLE", "POST_REBOOT_HISTORY_REQUIRED")
                else:
                    elapsed = pending["deviceElapsedMillis"] - fence["deviceElapsedMillis"]
                    if elapsed < limits["resourceExitCooldownMillis"]:
                        require(refused and data["reason"] == "RESOURCE_COOLDOWN", "RESOURCE_COOLDOWN_REQUIRED")
            if not refused and last_work is not None:
                if pending["hostSessionId"] == last_work["hostSessionId"]:
                    require(pending["hostElapsedMillis"] - last_work["hostElapsedMillis"] >= limits["minimumAttemptIntervalMillis"],
                            "WORK_CADENCE_INVALID")
                else:
                    issues.add("WORK_CADENCE_UNVERIFIABLE")
            if not refused:
                admitted += 1
                last_work = pending
            if kind == "RESOURCE_EXIT":
                deaths += 1
                fence = event
            pending = None
        elif kind == "OBSERVATION":
            available, queues = observation(data)
            observations += 1
            queue_observations += int(queues)
            if not available:
                issues.add("RESOURCE_OBSERVATION_UNAVAILABLE")
            else:
                current = (data["mainPid"], data["aiPid"])
                if pids is not None and current != pids:
                    issues.add("PROCESS_IDENTITY_CHANGED")
                pids = current
            if not queues:
                issues.add("QUEUE_OBSERVATION_UNAVAILABLE")
            last = last_observation or first
            if event["hostSessionId"] != last["hostSessionId"] or event["hostElapsedMillis"] - last["hostElapsedMillis"] > limits["maximumObservationGapMillis"]:
                issues.add("OBSERVATION_GAP")
            last_observation = event
        elif kind == "OBSERVATION_GAP":
            fields(data, {"reason"})
            one_of(data["reason"], {"TRANSPORT", "CADENCE", "METRIC_UNAVAILABLE", "DEVICE_REBOOT", "HOST_INTERRUPTED"})
            issues.add("OBSERVATION_GAP")
        elif kind == "INTERRUPTION":
            fields(data, {"reason"})
            one_of(data["reason"], {"HOST_INTERRUPTED", "DEVICE_DISCONNECTED", "PROCESS_DIED", "UNKNOWN"})
            interruptions += 1
            issues.add("INTERRUPTED")
            if pending is not None:
                issues.add("UNRESOLVED_WORK_AT_INTERRUPTION")
                pending = None
        elif kind == "RUN_END":
            fields(data, {"outcome"})
            one_of(data["outcome"], {"FINISHED", "INTERRUPTED"})
            ended = True
            if data["outcome"] == "INTERRUPTED":
                issues.add("INTERRUPTED")
        else:
            raise Invalid("EVENT_KIND_INVALID")
        previous = event
    if not ended:
        issues.add("END_UNAVAILABLE")
    if pending is not None:
        issues.add("UNRESOLVED_WORK")
    if cleanup_pending or intended - cleaned:
        issues.add("OWNED_CLEANUP_INCOMPLETE")
    if not observations:
        issues.add("OBSERVATIONS_UNAVAILABLE")
    elif ended and (previous["hostSessionId"] != last_observation["hostSessionId"] or
                    previous["hostElapsedMillis"] - last_observation["hostElapsedMillis"] > limits["maximumObservationGapMillis"]):
        issues.add("OBSERVATION_GAP")
    if not attempted:
        issues.add("WORK_UNAVAILABLE")
    if not admitted:
        issues.add("HEAVY_WORK_UNAVAILABLE")
    span = None
    if first is not None and previous is not None and len(host_sessions) == 1:
        span = previous["hostElapsedMillis"] - first["hostElapsedMillis"]
    if span is None or span < limits["targetDurationMillis"]:
        issues.add("REPORTED_DURATION_INSUFFICIENT_OR_DISCONTINUOUS")
    return {"issues": sorted(issues), "entries": len(entries), "reportedHostSpanMillis": span,
            "attempts": len(attempted), "reportedAdmittedAttempts": admitted, "resourceExits": deaths, "observations": observations,
            "queueObservations": queue_observations, "interruptions": interruptions}


def validate(plan, journal=None):
    report = {"schemaVersion": 1, "result": "UNVERIFIED_INPUT", "qualification": "NOT_QUALIFIED",
              "environment": "HOST_OFFLINE", "structuralStatus": "REJECTED", "evidenceState": "UNAVAILABLE",
              "physicalEvidenceVerified": False, "real24HourDurationProven": False, "autonomyProven": False,
              "workloadQualified": False, "profileQualified": False, "productionInferenceEnabled": False, "fullF5GateClosed": False,
              "fullSoakGateClosed": False, "commandsDispatched": False,
              "runnerAvailability": "UNAVAILABLE", "trustedConfigurationAvailability": "UNAVAILABLE",
              "physicalTargetAvailability": "UNAVAILABLE", "issues": []}
    try:
        validate_plan(plan)
        missing = []
        for key, field, issue in (("runnerSha256", "runnerAvailability", "RUNNER_UNAVAILABLE"),
                                   ("trustedConfigurationSha256", "trustedConfigurationAvailability", "TRUSTED_CONFIGURATION_UNAVAILABLE"),
                                   ("deviceIdentitySha256", "physicalTargetAvailability", "PHYSICAL_TARGET_UNAVAILABLE")):
            if plan[key] is None:
                missing.append(issue)
            else:
                report[field] = "REFERENCED_UNVERIFIED"
        if journal is None:
            report.update(structuralStatus="VALID", issues=missing + ["JOURNAL_UNAVAILABLE"])
        else:
            summary = validate_journal(plan, journal)
            report.update(structuralStatus="VALID", evidenceState="UNAVAILABLE" if missing else
                          "INCOMPLETE" if summary["issues"] else "CONSISTENT_REPORTED_RECORDS",
                          issues=sorted(set(missing + summary.pop("issues"))), reported=summary)
    except Invalid as error:
        report["rejectionCode"] = str(error)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", required=True, type=Path)
    parser.add_argument("--journal", type=Path)
    args = parser.parse_args()
    try:
        report = validate(document(args.plan), document(args.journal) if args.journal else None)
    except Invalid as error:
        report = validate(None)
        report["rejectionCode"] = str(error)
    except (OSError, ValueError, RecursionError):
        report = validate(None)
        report["rejectionCode"] = "INPUT_UNAVAILABLE_OR_INVALID"
    print(json.dumps(report, sort_keys=True, indent=2))
    # Zero means structural acceptance only, including explicitly unavailable/incomplete evidence.
    return 0 if report["structuralStatus"] == "VALID" else 2


if __name__ == "__main__":
    raise SystemExit(main())
