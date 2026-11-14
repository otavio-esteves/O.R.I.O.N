# F5 runtime evidence protocol — offline consistency only

Work package: [ORION-F5-RUNTIME-EVIDENCE-001](../issues/ORION-F5-RUNTIME-EVIDENCE-001.md).
Source baseline: `739dad80b647574fc7ebf2f4b46fa42a9f51b47d`.
The implementation is [f5_runtime_evidence_protocol.py](../../scripts/android/f5_runtime_evidence_protocol.py).
This is a new offline evidence format, not a new AIDL or production admission
contract. Architecture §§23–30 and Master Plan §§11.2–11.11 remain unchanged;
[ADR-0002](../adr/ADR-0002-bounded-cortex-request-history.md) applies only to the
existing issued request history.

The validator reads two supplied JSON files. It checks their exact identities and
internal consistency without reading a source checkout, APK, model, Android device
or network. Both files remain untrusted. Even completely fabricated but consistent
metadata can pass these checks. Every report therefore states
`evidenceTrust=UNVERIFIED_INPUT`, `qualification=NOT_QUALIFIED`, and all provenance,
physical-observation, profile, production-inference, F5 and soak approval flags
are false. A declared `PHYSICAL_DEVICE` label changes none of those facts.

## Scope and existing sources

One record describes a terminated load attempt, optional inference and optional
unload in one boot with stable MAIN/AI PIDs. It can retain unsuccessful terminals,
missing phases and a process-death interruption. It cannot describe a second bind,
another request, another model or another boot. A subsequent explicit request
needs another record; no replay/rebind is implied by validation.

The record is shaped around the existing sources, without claiming those sources
have produced this format:

| Data | Existing source and limitation |
| --- | --- |
| Wire bounds | `OrionCortexWire`: context 32–32768, threads 1–16, maxTokens 1–4096, input up to 1 MiB; service model descriptor up to 4 GiB. These are representation limits, not safe candidate settings. |
| Request/stream identity | MAIN lifecycle and the private service's separately issued transport identity. Store SHA256 of exact UTF-8 identifiers, not identifiers or request fingerprints containing content. Hash equality cannot prove issuance or callback ownership. |
| Stream accounting | MAIN's accepted, deduplicated, ordered chunks; metadata only, with sequence starting at zero. Raw callback duplicates, buffering and ignored late signals are not silently counted as accepted delivery. This format does not prove that those raw signals were handled correctly. |
| Logical terminal | `OrionInferenceLifecycle`: SUCCEEDED, CANCELLED, TIMED_OUT, FAILED, PROCESS_DIED. A MAIN timeout/cancel/death may terminate without a final callback. |
| Service/model state | `getStatus` and `healthCheck` from the same currently owned private endpoint. A scalar state in a file is only a supplied assertion. |
| PSS | MAIN self-PSS plus AI private self-PSS, complete manifest inventory, same-boot timestamps, pinned PIDs and exact aggregate. The independent physical coverage obligations remain in [F5-MemoryEvidence.md](F5-MemoryEvidence.md). |

`healthCheck.nativeRuntimeLoaded` means `runtime != null`. The current service
retains the JNI wrapper after `unloadModel`; a successfully loaded cycle therefore
keeps this flag true even when `getStatus` becomes UNLOADED. It does not mean that
model tensors remain loaded. `nativeValidationCompleted` is also retained. This
differs from the no-model baseline, which has never created a runtime. A future
producer must record actual values rather than force a false post-unload flag.

## Closed JSON schema, version 1

All listed fields are mandatory. Unknown fields, duplicate keys at any depth,
nonfinite numbers, malformed UTF-8, trailing JSON and wrong types are rejected.
An integer excludes booleans and floating-point values. Unless a narrower bound
is listed, integer counts/timestamps are in 0–`Long.MAX_VALUE`. SHA256 fields are
64 lowercase hex digits and cannot be all zero; the Git SHA1 is similarly 40 hex.
Only the explicitly nullable fields accept JSON null. There are no raw prompts,
outputs, errors, file paths, serials or free-form labels.

Top-level fields:

| Field | Type/value |
| --- | --- |
| `schemaVersion`, `kind` | Integer 1; string `ORION_F5_RUNTIME_EVIDENCE` |
| `declaredEnvironment` | `PHYSICAL_DEVICE`, `ANDROID_EMULATOR` or `SYNTHETIC_INPUT`; declaration only |
| `identity`, `session`, `candidate`, `load` | Objects below |
| `inference`, `unload` | Objects below, or null when not observed |
| `memory` | Array of 0–128 complete sample objects; omission of phases remains visible |

The separate `--expected-identity` file contains exactly the same eight fields as
`identity`; all fields must match. The validator does not derive either identity:

| Field | Constraint |
| --- | --- |
| `sourceCommit` | Full Git SHA1 |
| `sourceManifestSha256` | Hash of the retained complete source-freeze manifest bytes, including tracked-file identities; the validator does not verify that manifest |
| `appPackage`, `testPackage` | `network.orion` or `network.orion.qualification`; corresponding app package plus `.test` |
| `appApkSha256`, `testApkSha256` | Exact intended APK hashes, not an installed-hash assertion verified here |
| `modelSha256`, `modelSizeBytes` | Exact model hash, integer 1–4 GiB; current modelId equals its SHA256 |

`session` fields:

| Fields | Constraint |
| --- | --- |
| `bootCount`, `bootIdSha256` | Boot count 0–Int.MAX_VALUE and SHA256 of the technical boot identity; supplied claims only |
| `mainPid`, `aiPid` | Distinct positive integers up to Int.MAX_VALUE; fixed for the entire record |
| `startedElapsed`, `completedElapsed` | Same-boot elapsed-realtime milliseconds, ordered inclusive observation window |
| `maximumSampleAgeMillis` | Integer 1–1000; format ceiling for supplied samples, not an approved production freshness value |
| `processInventory` | Exactly `[appPackage, appPackage + ":ai"]`, in that order; no extra/disabled voice process can be omitted and called that artifact's complete inventory |
| `api`, `abi`, `pageSizeBytes` | API 31–37; `arm64-v8a` or `x86_64`; 4096 or 16384 bytes |
| `deviceBuildSha256`, `totalMemoryBytes` | Hashed technical build identity; integer 64 MiB–1 TiB, used only for consistency bounds, never a trusted memory budget |

`candidate` contains exactly `contextTokens`, `threads`, `maxTokens`,
`temperature`, within the current wire bounds above. Temperature is a finite JSON
number in 0–2, excluding booleans. These supplied parameters are unqualified.
No `qualifiedPlans`, memory budgets, approval boolean or alternate authority is
accepted. Input bytes, chunks or elapsed duration do not determine a safe profile.

`load` contains exactly `requestIdSha256`, `modelSha256`, `startedElapsed`,
`completedElapsed`, `terminal`. The request hash identifies the service-issued load
identity, the model hash matches `identity`, and both timestamps fit the session.
Terminal is one of the five lifecycle terminal enums above. An unsuccessful load
cannot have an inference or AFTER_LOAD/DURING_INFERENCE/AFTER_TERMINAL sample.
A missing terminal for an ongoing load is outside version 1.

`inference`, when present, contains:

| Fields | Constraint |
| --- | --- |
| `requestIdSha256`, `transportRequestIdSha256`, `modelSha256` | MAIN logical ID hash, private transport ID hash, exact model hash; the two request IDs and load ID are distinct |
| `startedElapsed`, `terminalElapsed`, `deadlineElapsed` | Start after load completion; start ≤ terminal within session; start < deadline ≤ start + 120000ms |
| `inputBytes` | Integer 1–1 MiB; input content is never included |
| `terminal`, `terminalSource`, `failureCode` | A terminal enum; `CALLBACK` or `MAIN_LIFECYCLE`; null or existing `OrionInferenceFailure` enum, nonnull only for FAILED |
| `chunks` | Array of 0–4097 accepted MAIN chunk metadata objects |

SUCCEEDED must precede the logical deadline; TIMED_OUT cannot precede it.
`CALLBACK` requires exactly one final chunk, last in the list, with its received
time equal to `terminalElapsed` and matching reason/error. PROCESS_DIED is a MAIN
terminal. `MAIN_LIFECYCLE` has no accepted final chunk and cannot claim SUCCEEDED.
The event timestamps describe the logical MAIN lifecycle, not a later host poll.
An exporter that only observed a delayed poll cannot invent those event times.

Every chunk contains exactly `requestIdSha256`, `sequence`, `receivedElapsed`,
`textDeltaBytes`, `toolDeltaBytes`, `isFinal`, `finishReason`, `errorCode`.
The request hash matches MAIN, sequence is its zero-based array index, and times
are nondecreasing from inference start through terminal. Text/tool UTF-8 byte
counts are nonnegative integers with sum at most 64 KiB, matching the lifecycle's
content guard. `isFinal` is a strict boolean. Nonfinal reason/error are null;
final reason/error match the logical terminal. No callbacks after terminal,
duplicates or sequence gaps are accepted in this normalized delivery view.

`unload`, when observed, contains exactly `startedElapsed`, `completedElapsed`,
`result` (`UNLOADED` or `FAILED`). It starts after the load/inference terminal and
fits the session. UNLOADED means the exporter actually observed completion of
the asynchronous cleanup, not merely return from the void `unloadModel()` call.
PROCESS_DIED forbids an unload claim or a later AI sample in this record.

## Memory coverage and time checks

Each memory row contains exactly:

```text
phase startedElapsed completedElapsed bootCount mainPid aiPid
mainSampledElapsed aiSampledElapsed mainPssBytes aiPssBytes aggregatePssBytes
mainNativeIncluded aiNativeIncluded source serviceState
nativeRuntimeLoaded nativeValidationCompleted
```

Rows are in phase order and have nonoverlapping, nondecreasing collection windows.
The boot and PIDs match the session. MAIN's timestamp is collection start, before
the platform reads; AI's timestamp is between MAIN and collection completion.
Completion minus MAIN timestamp is strictly below `maximumSampleAgeMillis`, so
both readings are fresh at completion. No retimestamping or wall-clock duration
is accepted. All windows fit the session and the phase's actual operation window.

PSS values must be positive integers, native-inclusive, individually and in
aggregate no greater than declared total physical memory. Aggregate is exactly
MAIN + AI and cannot exceed Long.MAX_VALUE. The source is exactly
`SELF_DEBUG_MEMORY_INFO_PSS`; both native-inclusion flags are strictly true.
The format does not authenticate that API call or prove protected/graphics/native
allocation coverage. A missing, stale, null or zero reading cannot be replaced
with zero or called a complete row. An unavailable phase is absent and therefore
reported missing; malformed supplied rows are rejected rather than discarded.

| Phase | Window and allowed observed service state |
| --- | --- |
| BEFORE_LOAD | Session start through load start; UNLOADED |
| DURING_LOAD | Load start through completion; LOADING or LOADED |
| AFTER_LOAD | Successful load completion through inference/unload start, or session end; LOADED |
| DURING_INFERENCE | Inference start through terminal; INFERENCING or LOADED |
| AFTER_TERMINAL | Live inference terminal through unload start, or session end; LOADED |
| DURING_UNLOAD | Unload start through completion; UNLOADING or UNLOADED |
| AFTER_UNLOAD | Successful observed unload completion through session end; UNLOADED |

The native wrapper cannot disappear after it was observed in this single bound
process. Completed successful load and subsequent rows retain runtime and
validation flags true, including AFTER_UNLOAD. LOADED/INFERENCING observations
also require both flags true. Process death ends the attempt; old AI readings
are not attributed to a replacement PID. This format does not record a Core
survival assertion, durable task contents or automatic restart permission.

## Result meanings and use

`CONSISTENT_CYCLE_METADATA` requires successful load, a non-death inference
terminal, observed successful unload and at least one complete row in each of
the seven phases. A FAILED/CANCELLED/TIMED_OUT inference can have consistent
cycle metadata; `inferenceTerminal` and `successfulInferenceReported` distinguish
that from a supplied success. `CONSISTENT_INCOMPLETE_METADATA` retains internally
consistent partial or interrupted attempts and lists missing phases. Neither
result asserts that a sample was ever physically observed. Invalid input has
`schemaValid=false`, a fixed rejection code and exit status 2; consistent input
has exit status 0 solely for offline parser automation, including incomplete input.

The report returns submitted accepted stream **bytes**, not tokens. Token counts,
tokenization, prefill/decode timings, tokens/second and model-quality results are
not exposed here. `tokenMetrics` and `prefillDecodeMetrics` explicitly remain
`UNAVAILABLE_NO_PRODUCER`; supplied extra metric fields are rejected. A chunk is
not a token and first accepted chunk time is not an authenticated native TTFT.
End-to-end logical timestamps cannot be substituted for missing native metrics.
The 4097-chunk/128-sample/4-MiB limits are defensive format limits, not runtime
capacity guarantees, measured budgets or a soak duration.

Offline use with already supplied files:

```sh
python3 scripts/android/f5_runtime_evidence_protocol.py \
  --evidence /path/to/supplied-runtime.json \
  --expected-identity /path/to/separately-supplied-identity.json
python3 -m unittest discover -s scripts/android \
  -p 'test_f5_runtime_evidence_protocol_harness.py' -v
```

The CLI accepts regular files without following a final symlink, reads at most
4 MiB of evidence and 4 KiB of expected identity, and emits a content-free report.
The JSON parser additionally bounds depth (12), nodes (100000), fields per object
(32) and array length. Errors do not echo supplied values, paths or exceptions.
There are no subprocess, ADB, network, model, source-hashing or device operations
in the validator. Test subprocesses only invoke this offline CLI against temporary
synthetic JSON files, explicitly labelled `SYNTHETIC_INPUT`.

## Dependencies before any real heavy physical execution

This package supplies no producer, physical run or trusted candidate/admission
decision. The existing no-model physical baseline runner and its HOST recorder
are not heavy producers and must not be relabelled as such. The emulator runtime
test remains explicitly synthetic-governor/emulator-only.

Before a heavy producer can be authorized, separate bounded work must define the
measurement-only admission boundary without injecting arbitrary JSON as production
`qualifiedPlans`; how it preserves current Core/inventory/governor/restart gates;
and how it records true load/terminal/unload events and current Binder ownership.
Normal Debug's extra voice probe processes cannot be deleted to manufacture
MAIN+AI completeness. An alternative qualification APK does not qualify production
identity. Any change to IPC semantics or authority needs an ADR first.

A trusted collection/review procedure must independently bind the actual physical
serial/build/API/ABI/pages/boot/window, exact full source freeze, local and installed
APK/test hashes and exact imported model bytes, retaining failures and lifecycle
interruptions. Offline agreement between two JSON files does none of that and
cannot reject a coordinated fabrication or replay of an old consistent record.
Independent native/graphics/process coverage and real headroom measurements from
[F5-MemoryEvidence.md](F5-MemoryEvidence.md) remain necessary before calibration.
Native tokenizer/prefill/decode metrics need a real producer if they are required;
the current callback metadata does not provide them.

S21 execution, API37 target behavior/MemoryLimiter, native matrix, model/profile
review and the physical post-Cortex soak remain open under #89. Core soak #64 is
separate. No JSON result closes either gate, declares CORTEX_RUNTIME_READY, opens
F6 or changes the empty production qualified-plan configuration.
