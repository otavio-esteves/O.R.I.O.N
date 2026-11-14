#!/usr/bin/env python3
"""Pure offline consistency checks. No input can establish provenance or qualification."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys

MAX_BYTES = 64 * 1024
MAX_DEPTH = 8
MAX_POLICIES = 16
LONG_MAX = 2**63 - 1
PACKAGE = "network.orion.qualification"
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
ROOT = "schemaVersion kind identity target process exit before policies".split()
IDENTITY = "runId sourceCommit appPackage testPackage appApkSha256 testApkSha256 modelSha256 producerSourceSha256".split()
TARGET = "apiLevel sdkExtension buildFingerprintSha256 abi declaredEnvironment dedicatedReason".split()
PROCESS = "name oldPid mainPid bootIdSha256 startedElapsedMillis lastAliveElapsedMillis lastAliveWallMillis".split()
EXIT = "name pid windowBootIdSha256 correlation timestampWallMillis observedWallMillis observedElapsedMillis reason descriptionEvidence classification".split()
BEFORE = "revision cortexState preloadAllowed keepAliveMillis".split()
POLICY = ("exitEvidenceSha256 mainPid bootIdSha256 observedElapsedMillis revision cortexState preloadAllowed keepAliveMillis "
          "restartBlock governorBlock admission heavyWorkIssued fenceStartedElapsedMillis cooldownMillis").split()
KINDS = {0: "UNKNOWN", 1: "NORMAL_EXIT", 2: "SIGNALLED", 3: "LOW_MEMORY", 4: "JVM_CRASH",
         5: "NATIVE_CRASH", 6: "ANR", 7: "INITIALIZATION_FAILURE", 8: "PERMISSION_CHANGED",
         9: "EXCESSIVE_RESOURCE_USAGE", 10: "USER_REQUESTED", 11: "USER_STOPPED",
         12: "DEPENDENCY_DIED", 13: "OTHER_SYSTEM", 14: "FREEZER", 15: "PACKAGE_CHANGED",
         16: "PACKAGE_UPDATED"}


class Rejected(ValueError):
    """Only a fixed technical code, never supplied content."""


def require(condition, code):
    if not condition:
        raise Rejected(code)


def fields(value, keys):
    require(type(value) is dict and set(value) == set(keys), "FIELDS_INVALID")


def integer(value, minimum=0, maximum=LONG_MAX):
    require(type(value) is int and minimum <= value <= maximum, "INTEGER_INVALID")


def choice(value, values):
    require(type(value) is str and value in values, "ENUM_INVALID")


def digest(value):
    require(type(value) is str and SHA256.fullmatch(value) is not None, "DIGEST_INVALID")


def boolean(value):
    require(type(value) is bool, "BOOLEAN_INVALID")


def parse_receipt(data):
    """Bytes only; bounded before decode, bounded lexical nesting before JSON parsing."""
    require(type(data) is bytes and 0 < len(data) <= MAX_BYTES, "INPUT_SIZE_INVALID")
    try:
        text = data.decode("utf-8", errors="strict")
    except UnicodeError:
        raise Rejected("UTF8_INVALID") from None
    depth, quoted, escaped = 0, False, False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in "[{":
            depth += 1
            require(depth <= MAX_DEPTH, "NESTING_INVALID")
        elif char in "]}":
            depth -= 1
            require(depth >= 0, "JSON_INVALID")

    def unique(pairs):
        obj = {}
        for key, value in pairs:
            require(key not in obj, "DUPLICATE_FIELD")
            obj[key] = value
        return obj

    def number(value):
        # No floats (including exponent notation), arbitrary-size ints or nonfinite values.
        require(re.fullmatch(r"-?[0-9]{1,19}", value) is not None, "NUMBER_INVALID")
        result = int(value)
        require(-LONG_MAX <= result <= LONG_MAX, "NUMBER_INVALID")
        return result

    def invalid_number(_):
        raise Rejected("NUMBER_INVALID")

    try:
        return json.loads(text, object_pairs_hook=unique, parse_int=number,
                          parse_float=invalid_number, parse_constant=invalid_number)
    except (json.JSONDecodeError, RecursionError):
        raise Rejected("JSON_INVALID") from None


def exit_evidence_sha256(receipt):
    """Correlation key, not a signature: canonical identity+target+process+exit."""
    body = {key: receipt[key] for key in ("identity", "target", "process", "exit")}
    return hashlib.sha256(json.dumps(body, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=True).encode("ascii")).hexdigest()


def validate_receipt(receipt):
    fields(receipt, ROOT)
    integer(receipt["schemaVersion"], 1, 1)
    choice(receipt["kind"], {"F5_LIMITER_CORRELATION_INPUT"})
    identity, target, process, event, before = [receipt[k] for k in ("identity", "target", "process", "exit", "before")]
    for obj, keys in [(identity, IDENTITY), (target, TARGET), (process, PROCESS), (event, EXIT), (before, BEFORE)]:
        fields(obj, keys)
    require(type(identity["runId"]) is str and re.fullmatch(r"[0-9a-f]{32}", identity["runId"]), "RUN_ID_INVALID")
    require(type(identity["sourceCommit"]) is str and re.fullmatch(r"[0-9a-f]{40}", identity["sourceCommit"]), "SOURCE_INVALID")
    require(identity["appPackage"] == PACKAGE and identity["testPackage"] == PACKAGE + ".test", "PACKAGE_INVALID")
    for key in ("appApkSha256", "testApkSha256", "modelSha256", "producerSourceSha256"):
        digest(identity[key])
    integer(target["apiLevel"], 1, 1000)
    integer(target["sdkExtension"], 0, 1000)
    digest(target["buildFingerprintSha256"])
    choice(target["abi"], {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"})
    choice(target["declaredEnvironment"], {"PHYSICAL_DEVICE", "ANDROID_EMULATOR", "UNKNOWN"})
    if target["dedicatedReason"] is not None:
        integer(target["dedicatedReason"], 17, 2**31 - 1)
    for obj in (process, event):
        require(obj["name"] == PACKAGE + ":ai", "PROCESS_INVALID")
    digest(process["bootIdSha256"])
    digest(event["windowBootIdSha256"])
    choice(event["correlation"], {"PAIRED_LIVE_WINDOW", "UNAVAILABLE"})
    integer(process["oldPid"], 1, 2**31 - 1)
    integer(process["mainPid"], 1, 2**31 - 1)
    require(process["mainPid"] != process["oldPid"], "PROCESS_IDENTITY_INVALID")
    integer(event["pid"], 1, 2**31 - 1)
    require(event["pid"] == process["oldPid"], "PID_MISMATCH")
    for key in ("startedElapsedMillis", "lastAliveElapsedMillis", "lastAliveWallMillis"):
        integer(process[key])
    for key in ("timestampWallMillis", "observedWallMillis", "observedElapsedMillis"):
        integer(event[key])
    require(process["startedElapsedMillis"] <= process["lastAliveElapsedMillis"], "PROCESS_TIME_INVALID")
    integer(event["reason"], 0, 2**31 - 1)
    choice(event["descriptionEvidence"], {"OTHER_ANON_SWAP_MARKER", "NO_MATCH", "UNAVAILABLE"})
    # The marker enum is a sanitized assertion of the exact §35.1 match, not raw text.
    require(event["descriptionEvidence"] != "OTHER_ANON_SWAP_MARKER" or event["reason"] == 13, "MARKER_REASON_INVALID")
    limiter = (target["dedicatedReason"] is not None and event["reason"] == target["dedicatedReason"] or
               event["reason"] == 13 and event["descriptionEvidence"] == "OTHER_ANON_SWAP_MARKER")
    classification = "MEMORY_LIMITER" if limiter else KINDS.get(event["reason"], "UNKNOWN")
    require(type(event["classification"]) is str and event["classification"] == classification, "CLASSIFICATION_MISMATCH")
    integer(before["revision"])
    choice(before["cortexState"], {"HEALTHY", "DEGRADED", "UNAVAILABLE"})
    boolean(before["preloadAllowed"])
    integer(before["keepAliveMillis"])
    policies = receipt["policies"]
    require(type(policies) is list and 1 <= len(policies) <= MAX_POLICIES, "POLICY_COUNT_INVALID")
    reference = exit_evidence_sha256(receipt)
    last_revision, last_elapsed = before["revision"], event["observedElapsedMillis"]
    boot_changed = event["windowBootIdSha256"] != process["bootIdSha256"]
    insufficient_policy, unsupported_resume = False, False
    first_fence = None
    for policy in policies:
        fields(policy, POLICY)
        digest(policy["exitEvidenceSha256"])
        require(policy["exitEvidenceSha256"] == reference, "EXIT_REFERENCE_MISMATCH")
        integer(policy["mainPid"], 1, 2**31 - 1)
        require(policy["mainPid"] == process["mainPid"], "MAIN_PID_MISMATCH")
        digest(policy["bootIdSha256"])
        boot_changed |= policy["bootIdSha256"] != process["bootIdSha256"]
        for key in ("observedElapsedMillis", "revision", "keepAliveMillis", "fenceStartedElapsedMillis"):
            integer(policy[key])
        integer(policy["cooldownMillis"], 1)
        fence = (policy["fenceStartedElapsedMillis"], policy["cooldownMillis"])
        if first_fence is None:
            first_fence = fence
        require(first_fence == fence, "DUPLICATE_EXIT_RENEWED_FENCE")
        require(policy["revision"] > last_revision, "POLICY_REVISION_INVALID")
        if not boot_changed:
            require(policy["observedElapsedMillis"] >= last_elapsed and
                    event["observedElapsedMillis"] <= policy["fenceStartedElapsedMillis"] <= policy["observedElapsedMillis"],
                    "POLICY_TIME_INVALID")
        last_revision, last_elapsed = policy["revision"], policy["observedElapsedMillis"]
        choice(policy["cortexState"], {"HEALTHY", "DEGRADED", "UNAVAILABLE"})
        boolean(policy["preloadAllowed"])
        boolean(policy["heavyWorkIssued"])
        choice(policy["restartBlock"], {"COOLDOWN", "FALLBACK_EXHAUSTED", "HISTORY_UNAVAILABLE", "HISTORY_INVALID", "HISTORY_STALE", "NONE"})
        choice(policy["governorBlock"], {"MEMORY_EXIT", "MODEL_UNQUALIFIED", "NOT_EVALUATED", "NONE"})
        choice(policy["admission"], {"REFUSED", "NOT_REQUESTED", "ALLOWED"})
        if policy["restartBlock"] == "COOLDOWN" and not boot_changed:
            require(policy["observedElapsedMillis"] - policy["fenceStartedElapsedMillis"] < policy["cooldownMillis"], "COOLDOWN_EXPIRED")
        unsupported_resume |= policy["heavyWorkIssued"] or policy["admission"] == "ALLOWED"
        insufficient_policy |= (policy["cortexState"] != "DEGRADED" or policy["preloadAllowed"] or
                                policy["keepAliveMillis"] != 0 or policy["admission"] != "REFUSED" or
                                policy["restartBlock"] not in {"COOLDOWN", "FALLBACK_EXHAUSTED"} or
                                policy["governorBlock"] == "NONE")
    status = "CONSISTENT_REFUSAL_INPUT"
    if target["apiLevel"] < 30:
        status = "UNSUPPORTED_APPLICATION_EXIT_INFO_API"
    elif boot_changed:
        status = "UNSUPPORTED_BOOT_CHANGE"
    elif unsupported_resume:
        status = "UNSUPPORTED_HEAVY_RESUMPTION"
    elif not limiter:
        status = "INSUFFICIENT_LIMITER_EVIDENCE"
    elif not (event["correlation"] == "PAIRED_LIVE_WINDOW" and
              process["lastAliveElapsedMillis"] <= event["observedElapsedMillis"] and
              process["lastAliveWallMillis"] <= event["timestampWallMillis"] <= event["observedWallMillis"]):
        status = "INSUFFICIENT_EXIT_TIME_CORRELATION"
    elif insufficient_policy or (before["cortexState"] == "DEGRADED" and not before["preloadAllowed"] and before["keepAliveMillis"] == 0):
        status = "INSUFFICIENT_POLICY_CHANGE"
    return summary(status)


def summary(status):
    return {"schemaVersion": 1, "kind": "F5_LIMITER_OFFLINE_VALIDATION", "environment": "UNVERIFIED_INPUT",
            "qualification": "NOT_QUALIFIED", "status": status, "provenanceAuthenticated": False,
            "memoryLimiterVerified": False, "physicalDeviceVerified": False, "profileQualified": False,
            "productionInferenceEnabled": False, "fullF5GateClosed": False, "fullSoakGateClosed": False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("receipt", type=Path)
    args = parser.parse_args(argv)
    try:
        # O_NONBLOCK prevents special files from hanging before the fstat check.
        fd = os.open(args.receipt, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, "rb") as stream:
            require(stat.S_ISREG(os.fstat(stream.fileno()).st_mode), "INPUT_NOT_REGULAR")
            data = stream.read(MAX_BYTES + 1)
        result = validate_receipt(parse_receipt(data))
    except (OSError, Rejected) as error:
        result = summary("REJECTED_INPUT")
        result["failureCode"] = str(error) if isinstance(error, Rejected) else "INPUT_UNAVAILABLE"
        print(json.dumps(result, sort_keys=True))
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0 if result["status"] == "CONSISTENT_REFUSAL_INPUT" else 1


if __name__ == "__main__":
    sys.exit(main())
