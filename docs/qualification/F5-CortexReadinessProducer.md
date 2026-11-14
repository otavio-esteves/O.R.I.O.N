# Cortex pre-resource readiness producer

Work package: [ORION-F5-READINESS-PRODUCER-001](../issues/ORION-F5-READINESS-PRODUCER-001.md).
Source base: `947d0b6e6d378d3ff978cd02c74a33af646a715b`.
This is a real Android diagnostic producer, not a heavy-runtime producer or a
production activation mechanism. This implementation package has not executed
Android or a physical device. No hardware/runtime outcome is asserted by these
instructions or HOST source contracts.

The opt-in qualification APK calls the actual `OrionApplication.mainRuntime`, its
existing `OrionFoundationRuntime.cortexRuntime()` port and the Core-owned owner's
read-only `preResourceReadiness()`. It never creates another DB, importer, owner,
client, governor or plan and never reflects private fields. Reflection is limited
to the framework runner's public qualification-only bridge method.

## What the query means

The owner and `run()` now share exactly the same lazy early predicates:

| First applicable condition | Query result / original run refusal |
| --- | --- |
| Owner closed | CLOSED |
| No qualified plans | NO_QUALIFIED_PROFILE |
| Resource timing absent | RESOURCE_CONFIGURATION_UNAVAILABLE |
| Core callback is false | CORE_NOT_READY |
| None of these | DECISION_NOT_EVALUATED; no permit or plan |

The query stops there even if future trusted profiles exist. It never requests an
ID or invokes `run`, model/registry access, manifest inventory, resource/restart
evaluation, binding, inference, cancellation or unload. `run()` retains its
existing behavior and continues with all remaining checks after those same early
predicates. Exceptions and laziness of the Core callback are unchanged. Neither
an enum name nor an open Core barrier authorizes resource-heavy work.

The existing factory itself requires its operational Startup Barrier, health
readiness and DB before returning the owner. A null port is therefore reported as
CORE_PORT_UNAVAILABLE; the producer does not guess which condition caused it.
Point health snapshots before/after are not atomic with that decision and are not
proof of a quiescent Core or readiness throughout the entire collection window.
The current factory passes no plans or timing, so a reached owner reports
NO_QUALIFIED_PROFILE. The diagnostic continues to issue no work if that changes.

## Conditional scope and invocation

Root owns Gradle/CI selection for `orionCortexReadinessProbe=true`, requiring
`orionQualificationRelease=true` and exclusion from baseline/exit/ordinary probe
modes. There is no new variant, ABI, component, permission or IPC. The target
bridge and its keep rule belong only to `app/src/cortexReadinessTarget`; the
framework runner belongs only to `app/src/cortexReadinessAndroidTest`. Normal
Debug/Release and the existing three minified probes keep their own sources.

Component:

```text
network.orion.qualification.test/network.orion.app.compatibility.OrionCortexReadinessInstrumentation
```

Exactly two string arguments are accepted:

| Argument | Value |
| --- | --- |
| `orionEnvironment` | PHYSICAL_DEVICE or ANDROID_EMULATOR |
| `orionCoreWaitMillis` | Integer string 0–10000, at most five decimal digits |

Extra fields, wrong types, invalid environments or out-of-range waits are refused
before bridge invocation. The bridge repeats these bounds and requires the actual
qualification MAIN package/process and `OrionApplication` instance.

Build signals determine `environment`, independently of the request. Bounded
fingerprint/hardware/product/device emulator markers take precedence over the
arm64 physical-candidate classification. Unrecognized builds remain UNVERIFIED.
A PHYSICAL_DEVICE declaration on an identified emulator returns REJECTED /
ENVIRONMENT_MISMATCH with environment ANDROID_EMULATOR before Core access. The
probe supports API31–37. These Build heuristics are not tamper-resistant physical
authentication: `environmentSource=BUILD_SIGNALS_ONLY`, and
`physicalDeviceVerified=false` remains explicit.

All durations use `AndroidOrionClock`, including its actual boot-count source;
there is no wall-clock duration or invented boot identity. A zero wait queries
once. A positive wait polls the existing Core port every 50ms, at most 201 calls,
without trying to recover the Core or invoke requests. Same-boot collection must
complete within the requested wait plus 2000ms to be OBSERVED. Internal coroutine
timeout is 12000ms; the runner bounds the worker at 15 seconds and cancels it on
failure. Synchronous platform/storage calls cannot be forcibly preempted; a timed
out worker can finish technical initialization later, but has no heavy-operation
path or authority. Missing clock identity or changed/regressed clocks cannot
become an OBSERVED receipt.

## Receipt schema

The final instrumentation Bundle contains one JSON string under
`orionCortexReadinessReceipt`, plus stream
`READINESS_ONLY / NOT_QUALIFIED`. There are no sample statuses or raw diagnostics.
The bridge receipt is at most 8 KiB and has exactly these fields:

| Fields | Meaning/type |
| --- | --- |
| `schemaVersion`, `kind`, `packageName` | Integer 1; CORTEX_READINESS_RECEIPT; network.orion.qualification |
| `qualification`, `result`, `failureCode` | NOT_QUALIFIED; OBSERVED/UNAVAILABLE/REJECTED; fixed code or null |
| `requestedEnvironment`, `environment`, `environmentSource` | Declared string; observed PHYSICAL_DEVICE/ANDROID_EMULATOR/UNVERIFIED; BUILD_SIGNALS_ONLY |
| `api`, `abi`, `buildFingerprintSha256` | Actual API integer; arm64-v8a/x86_64/UNVERIFIED; technical SHA256 or null when Build strings cannot be bounded |
| `mainPid`, `bootCount`, `completedBootCount` | Actual MAIN PID and actual start/end boot counts, nullable when unavailable |
| `startedElapsed`, `completedElapsed`, `requestedCoreWaitMillis`, `corePortQueries` | Actual elapsed milliseconds, requested bound, actual factory-call count 0–201 |
| `coreReadinessBefore`, `coreReadinessAfter` | STARTING/RECOVERING/READY/DEGRADED_SAFE/BLOCKED; UNAVAILABLE if mainRuntime absent; NOT_EVALUATED if not reached |
| `ownerQueryPerformed`, `preResourceReadiness` | Boolean indicating invocation of the owner's query, including a throwing invocation; exact owner enum, CORE_PORT_UNAVAILABLE, or NOT_EVALUATED |
| `aiObservation`, `aggregateMemoryObservation`, `resourceGovernorDecision`, `restartDecision`, `modelIdentity` | Always NOT_EVALUATED; no absent-AI zero reading and no inferred resource decision |
| `artifactIdentity` | HOST_VERIFICATION_REQUIRED; the APK does not attest its own source/installed hashes |
| `physicalDeviceVerified`, `provenanceAuthenticated` | Always false |
| `runtimeRequestsIssued`, `probeIpcBindIssued`, `heavyWorkIssued`, `admissionGranted` | Always false, scoped to this diagnostic's actions |
| `profileQualified`, `productionInferenceEnabled`, `fullF5GateClosed`, `fullSoakGateClosed` | Always false; the producer grants/changes none of these |

An OBSERVED receipt may report CORE_PORT_UNAVAILABLE: successful diagnostic
collection is not successful admission. It has `failureCode=null`. Bridge failure
codes are ENVIRONMENT_UNVERIFIED, API_UNSUPPORTED, ENVIRONMENT_MISMATCH,
CLOCK_UNAVAILABLE, OBSERVATION_TIMEOUT, INTERRUPTED, CORE_PORT_OBSERVATION_FAILED
or CLOCK_WINDOW_INVALID. Failed time validation can retain an actual query result
but the receipt remains UNAVAILABLE. Errors never echo exception details.

If the runner cannot validate arguments, target, bridge or returned envelope, its
same Bundle key instead contains CORTEX_READINESS_RUNNER_FAILURE with exactly:
schemaVersion, kind, packageName, environment=UNVERIFIED,
qualification=NOT_QUALIFIED, result=UNAVAILABLE, failureCode and the ten false
authority/provenance flags above. Failure codes are ARGUMENTS_REJECTED,
TARGET_IDENTITY_REJECTED, BRIDGE_UNAVAILABLE, RECEIPT_INVALID,
OBSERVATION_TIMEOUT or INTERRUPTED. It contains no fabricated Core/time fields.
Final instrumentation code is -1 only for an OBSERVED bridge receipt; otherwise
0. Neither code, the stream nor the word OBSERVED means READY or qualified.

This receipt deliberately does not satisfy the heavy-runtime evidence schema:
there is no load/inference/terminal/unload to record. Do not fabricate a failed
load to fit that format, reinterpret core readiness as inference readiness, or
mix baseline bindings with the private endpoint of a non-existent runtime request.

## Execution and review still required

Implemented checks are four JVM lazy-predicate/mapping/exception cases, owner Android gate
assertions plus one new configured-fixture diagnostic case, and eight HOST source
contracts. The tiny preexisting-style profile in the Android gate fixture is only
a mechanical negative-control object behind a closed DB and rejecting inventory/
bind context; it is not provided to this producer or accepted as configuration.
Configured fixture readiness must return DECISION_NOT_EVALUATED without touching
those dependencies. Source contracts alone do not establish Android execution.

Root must compile/lint/test the owner changes canonically, build and R8-inspect
the conditional target/runner, verify no extra bridge/keep in normal/ordinary
artifacts, and execute the explicitly labelled owned-emulator cases. Expected
cases include ANDROID_EMULATOR positive observation (actual early refusal or
Core-port unavailability), PHYSICAL_DEVICE mismatch retaining emulator identity,
extra/wrong arguments and bounded zero wait. Review retained receipts and actual
installed APK hashes; do not infer those results from this document.

Starting the separate qualification APK can create its own technical Room,
Keystore and WorkManager state. The factory may initialize its own technical
model-storage owner after Core readiness. The probe never imports a model or
closes that Core-owned owner; no settings are changed and production identity's
data is not accessed. This is not a wholly read-only-device or idle-Core claim.
Actual physical observations require the allocated target and independent HOST
source/APK/environment binding. No real producer for heavy load/stream/memory
cycles, trusted profile, native timing metric or 24-hour soak is supplied here.
Gates #89/#64, CORTEX_RUNTIME_READY and production inference remain unclosed.
