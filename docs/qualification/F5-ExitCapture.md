# Qualification-only Android exit capture

Work package: [ORION-F5-EXIT-CAPTURE-001](../issues/ORION-F5-EXIT-CAPTURE-001.md).
This producer reads real Android observations. It creates no exit stimulus and
does not start AI, load/infer/import a model, cancel/unload work, change resource
policy, read traces or persist business state. It does not supply a qualification
result. Every receipt is `CAPTURE_ONLY`, `NON_DEFINITIVE`, `NOT_QUALIFIED` with all
authority/gate flags false. Its separate offline validator always reports
`UNVERIFIED_INPUT`; a fabricated but internally consistent file can still pass.

## Opt-in boundary and observation effects

The root-owned wiring selects this source/runner/keep only with
`-PorionQualificationRelease=true -PorionExitEvidenceCapture=true`, exclusive with
the physical baseline and readiness modes. It uses the separate
`network.orion.qualification` identity. Normal Debug/Release and ordinary R8
qualification must exclude these sources and keep rules. No production entry
point or AIDL semantics change is provided.

The framework runner is
`network.orion.app.compatibility.OrionExitEvidenceCaptureInstrumentation`.
It requires exactly four string arguments:

| Argument | Values |
| --- | --- |
| `orionEnvironment` | `ANDROID_EMULATOR` or `PHYSICAL_DEVICE` |
| `orionObservationCount` | Decimal integer 1–10 |
| `orionObservationIntervalMs` | Decimal integer 100–1000 |
| `orionHistoryLimit` | Decimal integer 1–16 |

Validation precedes binding. The one `bindService(..., 0)` has no AUTO_CREATE;
an absent service is not started. False binding, no endpoint and five-second
connection timeout are separately recorded. Only the first endpoint is selected;
disconnect/replacement ends its live phase, and later connections are ignored.
The target bridge uses only existing `getProtocolInfo`, `getStatus`, `healthCheck`
calls on that endpoint. It pins AI name/PID and real boot count before accepting
live observations. It issues no request IDs or resource/admission decisions.

A binding is an observer effect: it can change service/process lifetime or
importance even without AUTO_CREATE. Starting instrumentation also bootstraps the
qualification app's existing MAIN Application/Core, which may create technical
Room/Keystore/WorkManager state. This is not a claim of a wholly read-only device
or an unperturbed production process. The three read calls can add small diagnostic
work (healthCheck includes self-PSS); they do not initialize a native model.

The worker has a 30-second timeout and checks a cancellation signal before/after
reads. Binder calls can block beyond a Java interruption: timeout emits a rejection
and no late worker result is published, without pretending to forcibly terminate
the remote call. All attempted bindings reach an unbind attempt, including false
or exceptional bind calls. Runner cleanup and death-recipient cleanup are visible
as separate enums. Cleanup failure makes instrumentation return cancellation;
metadata may still be retained for diagnosis, never treated as qualification.

## Actual values and missing observations

The bridge uses public `ActivityManager.getHistoricalProcessExitReasons` directly,
bounded by `historyLimit + 1`, before and after the live phase. The query covers the
qualification package; only exact AI process-name records are retained. Counts
include all returned package records so a crowded history cannot be called complete
merely because few AI records were retained. The sentinel makes TRUNCATED explicit.
AVAILABLE means the bounded query returned within the requested limit, not that
Android retained every historical exit. No record or ordinary kill is relabelled
as a MemoryLimiter stimulus.

Classification uses the current `OrionProcessExitClassifier`: the public dedicated
reason is reflected only when actually exposed (>16), or reason 13 plus the exact
Architecture §35.1 marker. Raw description is inspected transiently and discarded;
no description/trace enters JSON, errors or logs. Null description remains
UNAVAILABLE. Incident identity is the tuple PID/name/wall timestamp/reason;
description availability or a later classification change cannot make the same
incident a new death. Record digests identify sanitized content, not authenticate
it or count repeated queries as independent deaths.

The clock is `AndroidOrionClock`: real Settings.Global.BOOT_COUNT and elapsed
realtime, plus paired wall-clock reads. No kernel UUID or exit BootSessionId is
invented. `ApplicationExitInfo` supplies a wall death timestamp and no boot field.
`firstLiveObservedElapsed` is the first completed observation, not process start.
A missing/changed boot ends correlation; later epochs are not compared to old
monotonic readings. A death ends live collection, retains the old PID/window, and
MAIN closes the history snapshot without binding to a replacement.

The window can yield only `WINDOW_MATCH_CANDIDATE`: an incident absent before,
same old PID/name, death observed, same known boot, bounded available histories,
wall timestamp between last live observation and final query, and a single match.
Backward wall steps block that candidate. Forward jumps or undetected wall changes,
PID reuse, delayed/retained history, record loss and source/provenance cannot be
fully ruled out; wall continuity is explicitly `CONTINUITY_NOT_PROVABLE` even when
no backward step was observed. The candidate applies equally to ordinary kills;
it is not limiter verification.

Source freeze, installed APK hashes and model identity are not captured here.
The receipt explicitly states source `UNAVAILABLE_NOT_EMBEDDED`, app/test APK
`NOT_CAPTURED`, model `NOT_OBSERVED`; policy state/revision/fence/cooldown are
`UNAVAILABLE_NOT_EXPOSED`. Native wrapper/validation booleans are actual readings,
not model-residency evidence; an already-running AI may be doing work issued by
another owner. `heavyWorkIssued=false` and `policyMutationIssued=false` describe
this capture component's calls only. No fake governor, calibrated cooldown,
qualified budget or policy revision is synthesized.

## Closed receipt schema, version 1

The key `orionExitCaptureReceipt` contains one JSON object, at most 64 KiB.
All fields below are mandatory; the HOST validator rejects unknown fields and
raw content, duplicate keys at any depth, floats/exponents/nonfinite numbers,
malformed UTF-8, deep nesting (>8), invalid counts/types and trailing documents.
Integers exclude booleans and are nonnegative signed 64-bit unless narrowed.
Digests are 64 lowercase hex characters. A record with unavailable observations
retains explicit status/nulls rather than fake zeros.

| Object | Exact fields |
| --- | --- |
| root | `schemaVersion=1`, `kind=ORION_F5_EXIT_CAPTURE_ONLY`, `packageName=network.orion.qualification`, `declaredEnvironment`, `environment`, `runtimeEvidence=NON_DEFINITIVE`, `qualification=NOT_QUALIFIED`, `mainPid`, `result`, `correlation`, `matchedRecordSha256`, `bindingOutcome`, `binderDeathCleanup`, `firstLiveObservedElapsed`, `identity`, `policy`, `config`, `platform`, `window`, `liveObservations`, `historyBefore`, `historyAfter`, `cleanup`, and the nine false flags below |
| identity | `source`, `appApk`, `testApk`, `model` with the unavailable values above |
| policy | `state`, `revision`, `fence`, `cooldown`, all `UNAVAILABLE_NOT_EXPOSED` |
| config | `observations` (1–10), `intervalMillis` (100–1000), `historyLimit` (1–16) |
| platform | `apiLevel` (31–37), `buildFingerprintSha256`, `abi` (observed os.arch from the closed supported set or UNAVAILABLE), `bootSource=SETTINGS_GLOBAL_BOOT_COUNT`, `dedicatedReason` (null or >16 signed 32-bit), `dedicatedReasonStatus` (UNAVAILABLE or EXPOSED) |
| window | `start`, `end`, `wallClockState` (CONTINUITY_NOT_PROVABLE or BACKWARD_STEP_OBSERVED) |
| start/end | `bootCount` (null or 0–Int.MAX_VALUE), `elapsedMillis`, `wallMillis` |
| live row | `pid`, `processName=network.orion.qualification:ai`, `bootCount`, `readStartedElapsed`, `readCompletedElapsed`, `sampledElapsed`, `observedWallMillis`, `serviceState`, `nativeRuntimeLoaded`, `nativeValidationCompleted` |
| history | `status` (AVAILABLE, TRUNCATED, UNAVAILABLE), `queriedCount` (null only when unavailable; otherwise 0–limit+1), `records` (at most limit+1 deduplicated AI records) |
| record | `recordSha256`, `pid`, `processName=network.orion.qualification:ai`, `timestampWallMillis`, `reason`, `descriptionEvidence`, `classification` |

PIDs are positive signed 32-bit and live AI differs from MAIN. Live rows retain
one PID/boot, ordered read windows, sampled time inside each read window and read
duration <1000ms; this is a defensive format bound, not a calibrated memory budget.
The record digest is SHA256 of UTF-8 fields in the following pipe-separated order:
`pid|processName|timestampWallMillis|reason|descriptionEvidence|classification`.
Names/categories/classifications are closed; no pipe ambiguity or raw content is
accepted. The classification enum matches the current classifier. Description
category is OTHER_ANON_SWAP_MARKER (reason 13 only), NO_MATCH or UNAVAILABLE.

The nine flags are `provenanceAuthenticated`, `physicalDeviceVerified`,
`memoryLimiterVerified`, `profileQualified`, `productionInferenceEnabled`,
`fullF5GateClosed`, `fullSoakGateClosed`, `heavyWorkIssued`, `policyMutationIssued`.
They must all be false, including in rejection receipts.

`bindingOutcome` is CONNECTED, NOT_BOUND, CONNECTION_TIMEOUT or NO_ENDPOINT.
AI_NOT_AVAILABLE describes the unavailable endpoint in this capture; it is not a
claim that no app AI process exists anywhere in the OS.
`cleanup` is NO_BIND_ATTEMPT, UNBOUND, NOT_REGISTERED or FAILED;
`binderDeathCleanup` is NOT_LINKED, UNLINKED, ALREADY_DEAD or FAILED.
`result` is AI_NOT_AVAILABLE, AI_STILL_ALIVE, AI_PROCESS_DIED,
LIVE_OBSERVATION_FAILED, ENDPOINT_IDENTITY_CHANGED, ENDPOINT_DISCONNECTED,
BOOT_UNAVAILABLE or BOOT_CHANGED. AI_STILL_ALIVE requires all requested reads.
`correlation` is NO_LIVE_OBSERVATION, LIVE_PROCESS_NOT_DEAD, BOOT_INSUFFICIENT,
HISTORY_INSUFFICIENT, WALL_WINDOW_INSUFFICIENT, NO_MATCHING_NEW_EXIT,
AMBIGUOUS_EXIT_RECORDS or WINDOW_MATCH_CANDIDATE. The validator derives the expected
category and match digest again from the supplied fields, without authenticating
their truth. Missing match and first-live values use JSON null.

An Android build with emulator markers or x86 arch is ANDROID_EMULATOR; a requested
PHYSICAL_DEVICE on it is rejected before bind. A non-emulator-marked build yields
only PHYSICAL_BUILD_CANDIDATE, never verified physical identity. Declared emulator
on such a build is also rejected. These build heuristics are not hardware identity
authentication. Both paths remain NON_DEFINITIVE and NOT_QUALIFIED.

Failure before a capture receipt yields `ORION_F5_EXIT_CAPTURE_REJECTION` with
exactly `schemaVersion`, `kind`, `environment=UNVERIFIED`, `qualification`,
`runtimeEvidence`, `result=REJECTED`, `failureCode`, `cleanup` and the same flags.
Codes are ARGUMENTS_REJECTED, TARGET_IDENTITY_REJECTED,
ENVIRONMENT_OR_PLATFORM_REJECTED, BIND_UNAVAILABLE, CAPTURE_FAILED,
CAPTURE_TIMEOUT or INTERRUPTED. Raw exceptions/arguments are never emitted.

## Validation and remaining execution

HOST use on supplied JSON only:

```sh
python3 scripts/android/f5_exit_capture_protocol.py /path/to/receipt.json
python3 -m unittest discover -s scripts/android -p test_f5_exit_capture_protocol_harness.py -v
```

The validator reuses the limiter protocol's bounded JSON primitives, without
changing its schema or interpreting this receipt as limiter-v1 consistency.
It reads only a regular local file without following a final symlink, rejects
FIFOs/devices, echoes no paths/content and reports CONSISTENT_CAPTURE_METADATA,
CONSISTENT_REJECTION_METADATA (exit 0), or REJECTED_INPUT (exit 2). Exit 0 is
internal metadata consistency only, including missing AI, rejected captures and
cleanup failures. The summary retains cleanup outcomes and never says qualified.
HOST fixtures contain invented hashes/PIDs only for parser/source contracts.

Author validation is HOST only. Canonical compilation/R8 isolation, actual
instrumentation/absence/environment rejection and passive natural-exit behavior
must be checked separately by root. No Android execution is claimed here. A
successful real capture would still need trusted source/artifact/installed identity
admission, physical identity/window review and a separate authorized stimulus and
observable actual policy transition before MemoryLimiter/INV-027 qualification.
No controlled stress, heavy admission, 24-hour soak or physical gate is delivered.
