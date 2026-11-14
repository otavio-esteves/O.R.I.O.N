# Offline MemoryLimiter correlation protocol v1

This protocol prepares a future technical producer. The validator reads one local
file and checks its internal consistency; every result is `UNVERIFIED_INPUT` /
`NOT_QUALIFIED`. A consistent file does not prove that Android produced an exit,
that a physical device was used, that policy was applied, or that any model/profile
or F5/soak gate is approved. All seven approval/verification flags remain false.
Its exit code zero means only `CONSISTENT_REFUSAL_INPUT`.

The normative basis is [Architecture §35.1](../../1-orion.md), with
[OrionProcessExitClassifier](../../core/exitinfo/src/main/kotlin/network/orion/core/exitinfo/OrionProcessExitClassifier.kt),
[OrionMinimumResourceGovernor](../../core/resources/src/main/kotlin/network/orion/core/resources/OrionMinimumResourceGovernor.kt)
and [OrionCortexRestartGuard](../../core/resources/src/main/kotlin/network/orion/core/resources/OrionCortexRestartGuard.kt).
The dedicated public reason, when actually exposed by a build, and the preview
`REASON_OTHER` plus exact `MemoryLimiter:AnonSwap` marker are distinct supported
encodings. A normal SIGKILL, global `LOW_MEMORY`, JVM/native crash or OOM alone is
insufficient to identify the per-process limiter. Some of those exits still trigger
conservative resource backoff in product code; that does not relabel their cause.

## Missing producers and admission dependencies

There is no Android producer, stimulus, stress runner or trusted admission path in
this package. Current `ProcessExitSignal` carries reason, timestamp, kind and a
sanitized limiter flag; it does not carry the old PID, process name, boot correlation,
artifact identities or policy-transition receipt required here. The current
qualification baseline rejects emulators before collecting samples and never runs
a model. Its `nativeRuntimeLoaded=false` check is specific to the never-loaded
baseline: runtime wrapper existence after model unload is different from residency
and cannot serve as a general unloaded-model proof.

Before real execution, a separately reviewed producer must:

1. Freeze and verify source, exact app/test APKs, producer implementation, model
   bytes and target build/API/extension. Digests in an input file are references,
   not proof that those artifacts were installed, executed or measured. Resolve
   the dedicated public reason on that exact build; do not invent a numeric value
   or infer platform support from an API number alone.
2. Observe the live AI PID/process instance and MAIN PID, boot identity and paired
   wall/monotonic times before the incident; then correlate the historical record
   to that bounded observation window. `ApplicationExitInfo` has no exit
   BootSessionId. The current boot or an exit wall timestamp cannot establish it.
   A reused PID, reboot, wall-clock discontinuity, missing window or stale history
   needs an insufficient/unsupported result, not an inferred match.
3. Inspect a raw description only transiently inside the trusted collector, emit
   the exact match category, and discard raw descriptions/traces. Binding the
   category to the actual historical record needs trusted collection; accepting
   an enum in this file authenticates nothing.
4. Correlate an observed MAIN transition and actual refusal to the same exit before
   another heavy request. Capture monotonic cooldown fences at first observation,
   and deduplicate historical exits rather than treating repeated queries as new
   deaths. Authenticate producers and verify completeness of the observation
   interval; this offline tool does neither.
5. Supply approved qualified fallback plans and current complete resource evidence
   before any later heavy admission. No budgets, calibration thresholds or lower
   tier claims are supplied here. The protocol deliberately cannot approve heavy
   resumption or prove that a different model has a smaller qualified footprint.

Physical execution and a controlled, reviewed limiter stimulus remain absent. A
physical environment string, caller boolean or self-computed digest cannot replace
these dependencies. No producer, policy authority or process ownership changes are
authorized by this document.

## Closed receipt schema

The executable definition is
[f5_limiter_evidence_protocol.py](../../scripts/android/f5_limiter_evidence_protocol.py).
One UTF-8 JSON object is accepted, at most 65,536 bytes and eight lexical container
levels. All objects have exactly the keys below; unknown and missing fields fail.
There are no raw strings for descriptions, traces, paths, user content or budgets.
Integers use JSON integer syntax, exclude booleans, and are nonnegative signed
64-bit unless narrowed below. Floats, exponents, nonfinite values, duplicate fields
at any level, malformed UTF-8 and trailing documents are rejected. SHA256 values
are exactly 64 lowercase hexadecimal characters; source commits are 40 lowercase
hexadecimal characters. These are correlation syntax constraints, not attestations.

| Object | Exact keys and values |
| --- | --- |
| root | `schemaVersion=1`, `kind=F5_LIMITER_CORRELATION_INPUT`, `identity`, `target`, `process`, `exit`, `before`, `policies` |
| identity | `runId` (32 lowercase hex), `sourceCommit`, `appPackage=network.orion.qualification`, `testPackage=network.orion.qualification.test`, `appApkSha256`, `testApkSha256`, `modelSha256`, `producerSourceSha256` |
| target | `apiLevel` (1–1000), `sdkExtension` (0–1000), `buildFingerprintSha256`, `abi` (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), `declaredEnvironment` (`PHYSICAL_DEVICE`, `ANDROID_EMULATOR`, `UNKNOWN`), `dedicatedReason` (null or integer 17–2147483647) |
| process | `name=network.orion.qualification:ai`, `oldPid`, `mainPid` (distinct positive 32-bit PIDs), `bootIdSha256`, `startedElapsedMillis`, `lastAliveElapsedMillis`, `lastAliveWallMillis` |
| exit | `name` (same AI name), `pid` (same old PID), `windowBootIdSha256`, `correlation` (`PAIRED_LIVE_WINDOW`, `UNAVAILABLE`), `timestampWallMillis`, `observedWallMillis`, `observedElapsedMillis`, `reason` (0–2147483647), `descriptionEvidence`, `classification` |
| before | `revision`, `cortexState` (`HEALTHY`, `DEGRADED`, `UNAVAILABLE`), `preloadAllowed` (boolean), `keepAliveMillis` |
| policies | Array of 1–16 observations of the **same one exit**, each with the exact policy keys below |
| policy | `exitEvidenceSha256`, `mainPid`, `bootIdSha256`, `observedElapsedMillis`, `revision`, `cortexState`, `preloadAllowed`, `keepAliveMillis`, `restartBlock`, `governorBlock`, `admission`, `heavyWorkIssued`, `fenceStartedElapsedMillis`, `cooldownMillis` |

`descriptionEvidence` is `OTHER_ANON_SWAP_MARKER`, `NO_MATCH` or `UNAVAILABLE`.
The marker category means the producer claims the exact Architecture §35.1 match;
it is legal only with reason 13. `classification` must agree with the current
classifier mapping for reasons 0–16, or `UNKNOWN` for another unmatched reason.
It is `MEMORY_LIMITER` only for reason 13 plus that category, or an exact match to
the declared dedicated reason. The latter declaration still needs independent
build/symbol evidence; numeric equality is not MemoryLimiter verification.

`windowBootIdSha256` describes the producer's **observation window**, not a boot
field obtained from `ApplicationExitInfo`. `PAIRED_LIVE_WINDOW` is also an unverified
input assertion. Boot hashes must agree across the live process/window/policies
for a supported same-boot correlation. The live start must precede last-alive time;
the historical exit wall time must fall between last-alive and query wall times,
and the query monotonic time must not precede last-alive. These checks can detect
contradictions; they cannot authenticate a process instance, prove clock continuity,
or distinguish every stale historical record/PID reuse by themselves.

The evidence digest is SHA256 of the root subset `identity`, `target`, `process`,
`exit`, serialized as UTF-8 JSON with sorted keys, ASCII escapes and separators
`,` and `:` without whitespace (`exit_evidence_sha256`). Every policy references
that exact digest. It is a reproducible correlation key, not a signature. Root
`before` and policy entries are not authenticated by this digest.

Policy `mainPid` must match the surviving distinct MAIN PID. Revisions increase
strictly from `before.revision`, and observation times never decrease. These
revisions are producer-owned observation sequence numbers, not a new durable
version or a field already exposed by product APIs. In the same
boot each fence is at or after the exit query and at or before its evaluation.
All entries retain the same fence and positive `cooldownMillis`: another
observation of this one exit cannot renew its cooldown. For `COOLDOWN`, elapsed
time since the fence is strictly less than the duration, without adding values
that could overflow. No cooldown value in a file becomes a qualified parameter.

`restartBlock` accepts `COOLDOWN`, `FALLBACK_EXHAUSTED`, `HISTORY_UNAVAILABLE`,
`HISTORY_INVALID`, `HISTORY_STALE`, `NONE`. `governorBlock` accepts `MEMORY_EXIT`,
`MODEL_UNQUALIFIED`, `NOT_EVALUATED`, `NONE`. `admission` accepts `REFUSED`,
`NOT_REQUESTED`, `ALLOWED`. The two boolean fields are exact JSON booleans;
`cortexState` uses the same enum as `before`.

A consistent refusal sequence requires every entry to be DEGRADED, preload false,
keep-alive zero, admission REFUSED, heavy-work false, and a restart block of
COOLDOWN or FALLBACK_EXHAUSTED. Governor NONE is insufficient; NOT_EVALUATED is
permitted when restart refusal prevents reaching the governor. At least one
actual state dimension must differ from `before` (an incremented revision alone
is insufficient). FALLBACK_EXHAUSTED records only a declared refusal; it cannot
prove the number of independent deaths or the validity/exhaustion of a qualified
fallback ladder. There is no death-count field or multi-incident admission path.

## Outcomes and local use

Run on HOST without Android tools:

```sh
python3 scripts/android/f5_limiter_evidence_protocol.py /path/to/receipt.json
python3 -m unittest discover -s scripts/android -p test_f5_limiter_evidence_protocol_harness.py -v
```

The CLI opens a regular file without following a final symlink and uses a bounded
read; FIFOs/devices/directories are rejected. It prints one fixed-key summary to
stdout, without echoing input, file paths, stack traces or identifiers. Malformed
input returns exit 2 / REJECTED_INPUT with a fixed technical failure code. A
well-formed unsupported/insufficient record returns exit 1. Consistent refusal
returns exit 0 and still has no verified/provenance/qualification meaning.

| Status | Meaning within the supplied file only |
| --- | --- |
| `CONSISTENT_REFUSAL_INPUT` | Supported limiter encoding and same-window policy refusal are internally consistent |
| `INSUFFICIENT_LIMITER_EVIDENCE` | Reason/category does not identify limiter encoding |
| `INSUFFICIENT_EXIT_TIME_CORRELATION` | Missing paired correlation or contradictory wall/monotonic exit window |
| `INSUFFICIENT_POLICY_CHANGE` | Transition/refusal is missing, unchanged or insufficient |
| `UNSUPPORTED_APPLICATION_EXIT_INFO_API` | Target API below 30 |
| `UNSUPPORTED_BOOT_CHANGE` | Supplied boot identities differ; monotonic epochs are not compared |
| `UNSUPPORTED_HEAVY_RESUMPTION` | Any entry declares work issued or admission allowed; this tool cannot approve it |
| `REJECTED_INPUT` | Schema, identity, digest, order, bounds or file checks failed |

When several limitations apply, priority is API, boot, heavy resumption, encoding,
window, then policy. All summaries use `UNVERIFIED_INPUT`, `NOT_QUALIFIED` and false
`provenanceAuthenticated`, `memoryLimiterVerified`, `physicalDeviceVerified`,
`profileQualified`, `productionInferenceEnabled`, `fullF5GateClosed`,
`fullSoakGateClosed`. A supplied PHYSICAL_DEVICE label never changes these values.

The HOST test fixtures are intentionally synthetic and contain invented hashes,
PIDs and durations solely to exercise grammar/correlation. They are not retained
measurement receipts, production resource plans or examples of qualified budgets.
Runtime, physical, build and provenance validation remain separate pending work.
