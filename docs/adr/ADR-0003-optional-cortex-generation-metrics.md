# ADR-0003: Optional Cortex native generation metrics

- Status: **PROPOSED — not accepted and not authorization to implement IPC**
- Date: 2026-11-14
- Decision issue: [#112](https://github.com/otavio-esteves/O.R.I.O.N/issues/112)
- Work package: [ORION-F5-METRICS-ADR-001](../issues/ORION-F5-METRICS-ADR-001.md)
- Scope proposed: Optional, read-only, content-free generation measurements from
  the existing private AI-owned runtime to its existing MAIN client.
- Supersedes now: **Nothing.** Existing architecture and accepted ADRs govern.

## Context and exact precedence

Architecture V3.2 §§22/23/32/33/35/99 defines the private Cortex boundary,
negotiation, lifecycle, governor-first operation, resource backoff and per-inference
observability. Master Plan §§6.3/11.11 requires meaningful benchmark/runtime
evidence. [AGENTS §1.1](../../AGENTS.md) requires an accepted ADR before changing
IPC semantics. A generic Bundle or observability requirement is not permission
to change an existing closed wire envelope silently.

If accepted, this ADR would extend only the current Cortex method/capability
surface with the two optional operations below and advance supporting peers from
minor 1 to minor 2. In [ADR-0002](ADR-0002-bounded-cortex-request-history.md), only
the implementation-version statement “Cortex 1.1” would become “Cortex 1.2 for
peers implementing this optional extension.” Its issued-history capability,
transaction numbers 1–10, legacy handshake, required minor-1 checks, logical and
transport identity rules, retirement watermark, retention/admission behavior and
no-replay semantics would be unchanged. Major stays 1. No architectural authority,
process topology, IPC payload guard, persistence or resource-policy clause is waived.
Only the Cortex subsystem minor would change; common/voice protocol versions do not.

[ADR-0001](ADR-0001-docker-first-host-device.md) remains unchanged. Metrics are
not physical provenance, a qualified profile, memory admission, restart permission,
native/API qualification, real-duration soak evidence or F6 authorization.
INV-002/009/011/012/013/027/028/032/035/036 remain binding.

## Current source facts

Reviewed source: `d2b784e67f0645df3257d890be128ad9771ba99d`.

- [nativeGenerate](../../ai/llama/src/main/cpp/orion_llama_jni.cpp) tokenizes into a
  local count, prefills prompt batches and samples generated tokens. Its return
  is only a finish code. Context and sampler currently set no_perf=true; there is
  no exported native phase timing receipt. EOG is sampled without a piece, and
  the final emitted token at the token limit is not evaluated by another decode.
- [OrionLlamaRuntime](../../ai/llama/src/main/kotlin/network/orion/ai/llama/OrionLlamaRuntime.kt)
  exposes a finish enum plus raw token pieces. The
  [service](../../ai/service/src/main/kotlin/network/orion/ai/service/OrionCortexService.kt)
  decodes UTF-8 and splits text before callbacks; client chunk/byte counts are not
  token counters. Native TOKEN_LIMIT and natural completion currently both map
  to logical SUCCEEDED, which must not be reinterpreted by this proposal.
- [IOrionCortex](../../ai/api/src/main/aidl/network/orion/ai/api/IOrionCortex.aidl)
  has ten transactions. Its status/health/callback envelopes and the
  [client guards](../../app/src/main/kotlin/network/orion/app/ai/OrionCortexClient.kt)
  have no generation-metrics fields. getCapabilities returns the one issued-history
  capability string expected by current clients; replacing it with a list would
  break those clients.
- Logical cancellation/timeout/terminal delivery can precede native return,
  native RAII unwind and service-slot cleanup. An inference terminal, void unload,
  wrapper existence or a live PID cannot prove complete performance measurements.

Native-local instrumentation proposed in
[#111](https://github.com/otavio-esteves/O.R.I.O.N/issues/111) is independent. It can
prepare same-runtime measurement sources without Cortex IPC. This ADR neither
approves that implementation nor prescribes its JNI ABI, holder, counter layout or
collection technique. A future adapter may expose only fields actually supported
by a reviewed source; unavailable fields must remain absent.

## Proposed negotiation and API

All following choices are proposed for decision review, not implemented behavior.
Add at the end of AIDL, without renumbering or changing methods 1–10:

```text
11: Bundle getGenerationMetricsCapabilities()
12: Bundle getGenerationMetrics(in Bundle query)
```

Supporting services advertise major=1/minor=2 in the **unchanged** four-field
getProtocolInfo envelope. The existing getCapabilities envelope and its exact
issued-history string remain unchanged for every caller, including 1.1 clients.
This avoids advertising the new feature by breaking old capability parsing.

The new discovery call returns exactly schemaVersion:Int=1/status:String. For
status=OK it additionally requires exactly capability:String=
orion_generation_metrics_v1, metricsSchemaVersion:Int=1 and supportedMetricsMask:Long
using only the 16 defined metric bits; for UNSUPPORTED those three fields are
absent. supportedMetricsMask is the current adapter/provider's potentially
supported subset, not per-request availability or completeness. It can be zero
when no native source is available; queries must not instantiate one to change it.
Encoded discovery/query Parcels are each at most 1024 bytes.
Capability support describes a wire operation, never source availability or approval.

A new MAIN client first performs the existing major/minor and mandatory
issued-history negotiation. Only minor>=2 permits calling transaction 11, and
only a valid new capability permits transaction 12. Unknown transaction, missing
capability, unsupported schema or malformed optional replies makes **metrics**
unavailable; it must not loosen existing inference checks or make otherwise
compatible inference unavailable merely because optional telemetry is missing.
Major mismatch still rejects communication under the existing contract.
The optional schema/mask agreement belongs to that actual connection and is
cleared on its loss. It is separate from the unchanged mandatory issued-history
capability parsing; no concatenated capability string is introduced there.

An old client does not call the extension and retains its original behavior. A
new client with a 1.1 peer does not probe transaction 11/12. Legacy 1.0 behavior
and the current client's mandatory issued-history requirement remain unchanged.

The query has exactly schemaVersion:Int=1 and requestId:String: the current
service-issued inference transport ID, validated within its existing <=128-character
identifier bound and issued/admitted history. It accepts no logical MAIN ID,
model/prompt, PID, boot, endpoint, artifact hash, profile, “qualified” flag or
caller-supplied clock. It cannot enable instrumentation, start work, create a
runtime, bind/rebind, reset cancellation, retry or recover a retired request.
MAIN must not call acquire/bind solely to discover/query metrics. An absent or
changed owned endpoint stays unavailable; discovery can use an existing compatible
connection obtained through the unchanged gates. A query performs a bounded
lookup/copy and never waits for native/worker completion. No new background poller
is proposed; any later capture loop needs its own finite count/time contract.

## Proposed closed replies and bounds

Every encoded metrics reply is at most **8192 bytes total**, including nested
Bundle/Parcel overhead, and retains the 256 KiB architectural ceiling. Only
explicit primitive fields plus one bounded metrics Bundle are allowed; no arbitrary
arrays, object serialization, descriptors, content, maps or extension keys.
No prompt, output/piece/token IDs, model/path, exception text, raw native log,
trace, device serial or arbitrary label is carried. Actual transport IDs are
internal correlation metadata, not content to print in logs. This ADR adds no
persistence/export permission; future retained HOST evidence requires its own
minimization/provenance contract and cannot treat an IPC/file field as a signature.

Two reply shapes are proposed:

1. Exactly schemaVersion:Int=1/status:String for UNSUPPORTED, INVALID_QUERY,
   NOT_FOUND, RETIRED or UNAVAILABLE. No request content/identifier is echoed from
   an invalid query. NOT_FOUND does not mean safe execution or permission to retry;
   RETIRED follows ADR-0002 and cannot recreate a result.
2. A SNAPSHOT with the exact required header below, and only the conditionally
   present fields defined here. Missing implementation/source or an unusable
   snapshot returns UNAVAILABLE, never zero-filled measurements.

UNSUPPORTED includes an unsupported metrics schema. INVALID_QUERY covers invalid
fields/types/ID syntax or an admitted ID for a non-inference operation. NOT_FOUND
means no admitted inference with that ID in this service scope (including an unused
reservation), not that work can safely be executed. RETIRED uses the original
history's lookup order, checking retained/live entries before the watermark;
active holes below a watermark are not misclassified. UNAVAILABLE covers a
disabled/missing/faulted source or unusable measurement, never a start command.

SNAPSHOT field set (conditional presence is stated explicitly):

| Field | Type and meaning |
| --- | --- |
| schemaVersion/status | Int=1 / String=SNAPSHOT |
| requestId | Actual admitted service transport ID, from service ownership |
| servicePid | Actual positive Int from the serving process, not the query |
| invocationId | Positive Long only when an actual native invocation has been associated; otherwise absent |
| snapshotSequence | Positive Long per request; strictly advances for changed observed facts, otherwise unchanged |
| logicalTerminal | NONE or an existing inference terminal; no new terminal reason |
| nativeState | NOT_STARTED, RUNNING, JNI_RETURNED, NOT_EXECUTED or UNKNOWN, as defined below |
| nativeFinish | NONE, SUCCEEDED, CANCELLED, TOKEN_LIMIT or UNKNOWN from the actual native-wrapper result; never inferred from logical SUCCEEDED |
| slotState/snapshotState | Strings: OWNED/RELEASED/UNKNOWN and UPDATING/FINAL/UNKNOWN; unknown is never coerced to false or complete |
| partialWorkState | NONE_OBSERVED/POSSIBLE/UNKNOWN from actual source facts; abort/failure can leave unknown work inside unsuccessful native calls |
| completedCallStagesMask | Int with only low three bits: successful tokenizer materialization, all prompt API calls returned success, generation loop finished normally; these are control-flow facts, not backend-compute barriers |
| timeState | KNOWN_BOOT, UNAVAILABLE or DISCONTINUOUS |
| availableMetricsMask | Long, only the 16 defined low bits may be set |
| metrics | Bundle containing exactly the metric keys selected by that mask |

availableMetricsMask must be a subset of the negotiated supportedMetricsMask.
Every set bit requires that exact typed field; every clear bit prohibits it.
Support by a future source, including issue #111, does not assert that all fields
exist for an individual cancelled/failed/not-started invocation. No particular
native array ABI or layout is implied by this wire map.

For timeState=KNOWN_BOOT, bootCount:Int>=0 and capturedElapsedMillis:Long>=0 are
required. wrapperStartedElapsed and wrapperFinishedElapsed are optional Longs,
present only for actual paired service-clock observations of the wrapper call.
They must be ordered in that same known boot and no later than capture. For
UNAVAILABLE/DISCONTINUOUS all four time fields are absent. A changed/missing boot
or regression cannot become a KNOWN_BOOT claim. Wrapper times are not exact native
entry/unwind times and do not replace native phase durations.

capturedElapsedMillis dates snapshot capture/publication, **not** a new measurement
on every query or the freshness of retained generation measurements. Queries copy
the retained snapshot without new clock reads/retimestamping. Re-querying must not
reset duration, change invocation identity or manufacture a fresh resource
observation. Final snapshots keep their facts, sequence and capture time immutable.
A new source observation may publish a new sequence, but cannot repair an unknown
past boot/window from a later clock reading or replace the original wrapper times.

No source schema, asserted PID or hash is a signature. Service enforces its
existing actual Binder caller-UID/private-component checks and history ownership.
MAIN correlates only inside its currently owned Binder/ServiceConnection epoch,
maps the transport ID through its existing logical-request closure and checks the
already known process/boot identity when available. A self-reported field does not
authenticate an arbitrary caller/file, attest process inventory or grant policy
authority. If expected identity is unavailable, stale or changed, a consumer must
keep correlation unavailable; it must not accept caller-supplied expected fields
as a substitute or create a second connection/runtime to fill the gap.

invocationId is issued and associated by the service with one actual owned native
generation, not chosen by the query. It is never reused/reassigned within that
service process; exhaustion makes telemetry unavailable without changing inference.
It may repeat after process death. PID/boot/numeric counters alone are not a global
identity: the actual current Binder/connection epoch remains essential.

## Proposed metric semantics

Every selected count is a nonnegative Long except promptTokens, which is positive
when present. Bounds follow current Cortex request limits, without increasing
them: promptTokens and every prefill count <=32768; all generation/decode/callback
counts <=4096, with eogTokens<=1. Unknown fields/bits/types, booleans as integers,
negative values, overflow or impossible relationships reject the metrics snapshot.

| Bit/key | Measurement definition |
| --- | --- |
| 0 promptTokens | Actual successfully materialized prompt-token count with current add_special=true/parse_special=true semantics; not context capacity, bytes or probe allocation size alone |
| 1 sampledTokens | Actual sampler results, including EOG; not a requested limit |
| 2 eogTokens | Sampled EOG tokens, never presented as emitted text |
| 3 callbacksAttempted | Native attempts to invoke Java onPiece for non-EOG pieces |
| 4 callbacksReturnedNormally | Such callbacks that returned normally, including false; not emission, MAIN acceptance/delivery or presentation |
| 5 prefillCalls | Actual prompt-batch decode calls entered |
| 6 prefillSuccessfulCalls | Such calls whose API returned code 0; not a backend synchronization guarantee |
| 7 prefillSubmittedTokens | Sum of prompt tokens submitted to those calls |
| 8 prefillTokensInSuccessfulCalls | Tokens in whole batches whose API returned code 0; partial work inside failed/aborted calls remains unknown |
| 9 decodeCalls | Actual generated-token decode calls entered, excluding prompt prefill |
| 10 decodeSuccessfulCalls | Such calls whose API returned code 0, with the same backend-completion limitation |
| 11 decodeSubmittedTokens | Generated tokens submitted to those calls; current calls submit one token each |
| 12 decodeTokensInSuccessfulCalls | Generated-token inputs in whole API calls that returned code 0 |
| 13 tokenizationApiWallNanos | Sum of wall durations inside actual tokenizer calls, excluding allocation/copy outside those calls |
| 14 prefillApiWallNanos | Sum inside actual prompt-batch llama_decode calls, including observed abort/failure time when available; not synchronized full prefill compute time |
| 15 decodeApiWallNanos | Sum inside actual generated-token llama_decode calls, with the same partial-work/backend-completion distinction |

If any duration is present, the metrics Bundle additionally requires exactly
clockDomain:String=STEADY_CLOCK_NS. Durations are measured nonnegative Long
nanoseconds from one monotonic source; zero may be real clock resolution, but
unknown/unmeasured is **absence**, not zero. A provider with incompatible clock or
measurement boundaries must leave the field absent rather than relabel it. This
does not require an implementation to add timers it does not support.

Native durations have no persisted epoch and are not Android elapsedRealtime,
BootSessionId, lease/freshness timestamps or proof of continuous wall duration.
They cannot be subtracted from MAIN clocks or used as 24-hour evidence. Their
clock/suspend behavior and collection overhead require source/build review and
physical calibration. API return alone may not synchronize total backend compute.
No new synchronize/barrier/work is implied to improve measurements. Do not
reinterpret API-wall or wrapper elapsed time as full prefill/decode compute time.

Where operands are present: successful calls cannot exceed calls entered;
tokens in successful calls cannot exceed submitted tokens; prefillCalls<=
prefillSubmittedTokens<=promptTokens; callbacksReturnedNormally<=callbacksAttempted<=
sampledTokens-eogTokens; decodeTokensInSuccessfulCalls<=decodeSubmittedTokens<=
callbacksReturnedNormally. Current one-token calls require decodeSubmittedTokens=
decodeCalls and decodeTokensInSuccessfulCalls=decodeSuccessfulCalls. The current
maxTokens=1 case may complete one callback with **zero** generated-token decode
calls. Immediate EOG may sample one with zero callbacks. A false callback or
cancellation can stop after a normal callback return without evaluating that token.
These are measured call/count facts, not emitted/accepted/presented-token claims.
Zeros must be known measurements; checks never infer missing operands.

Token counts presented to the user are not available from these native facts and
are not carried. Client chunks/bytes, callback segmentation and requested
maxTokens cannot substitute for measured tokens. No throughput, TTFT, first-piece
latency or native-total-duration field is proposed: they require their own real
source and reviewed measurement boundaries. This ADR does not define token/s or
full prefill/decode throughput from API-wall time. An arithmetic ratio must not be
promoted to either metric; zero/unavailable time never produces infinity, NaN,
assumed throughput or approval.

The tokenizer stage bit requires the materialized promptTokens field.
The prefill-call stage bit requires that tokenizer bit and equality between
prefillTokensInSuccessfulCalls and promptTokens. The generation-loop bit requires
that prefill-call bit, nativeState=JNI_RETURNED and nativeFinish=SUCCEEDED or TOKEN_LIMIT.
None of these bits may be inferred from a logical terminal, an empty payload or
a requested token limit. Partial/failure time is not silently evaluated as a full
successful-stage rate; even set bits establish API/control-flow completion only.

## Partial results, terminality, death and retention

NOT_STARTED means the worker has not entered the wrapper. RUNNING requires actual
source knowledge of native entry; a wrapper call attempt alone is insufficient.
JNI_RETURNED requires observed return/unwind of the original native call, not
worker-finally completion or a device/backend synchronization guarantee.
NOT_EXECUTED is final proof that
the owned worker finished without entering native generation. UNKNOWN is required
where that distinction cannot be observed. A source providing only return-time
measurements may leave in-progress nativeState UNKNOWN and metrics absent.
An UNKNOWN source that remains unavailable after worker cleanup returns UNAVAILABLE
rather than promising that indefinite polling will produce measurements.

Logical terminal and measurement finality are independent. Cancellation, timeout,
callback failure and process-death handling must keep their current single-terminal
behavior. They neither wait for metrics nor force a metric snapshot to complete.
snapshotState=FINAL is allowed only after the native attempt has returned/unwound
or is proven not executed, slotState=RELEASED, an actual logical terminal exists and the
snapshot will no longer change. It does not imply logical SUCCEEDED or every metric
available. Final partial data stays partial; a native/Java exception may leave no
measurement snapshot at all. An original pending generation/callback exception
must never be cleared/replaced to manufacture metrics. A new failure exclusive
to diagnostic copy/publication, proven separate after a clean original result,
may be contained by the local collector while preserving that original finish/
throw outcome and reporting metrics unavailable. The source must prove this
isolation; this ADR does not prescribe its JNI ABI or exception-handling mechanism.

slotState=RELEASED requires a marker from completion of that request's original
worker cleanup, not JNI return or observing an idle service later. UNKNOWN slot/
snapshot state stays unknown. A provider that only reports JNI return cannot set
either RELEASED or FINAL without that independent service-owned observation.

No metric path may change generation output, cancellation, deadlines, watchdog,
unload, restart/resource decisions or exception effects. Collection/query faults
produce unavailable telemetry, without propagating as inference/cleanup failures.
No arbitrary blocking observer is introduced into native, service or client critical
paths. Sampling overhead must be bounded, opt-in where applicable and measurable;
it is not exempted from real resource/performance qualification.

Retain at most one bounded snapshot per retained inference entry in the existing
64-entry issued-history window, never another lifetime-unbounded history. The one
current native collector may keep its bounded in-flight state until owned unwind
even after early logical terminal. It must not extend ADR-0002 query/admission
retention: a retired ID returns RETIRED, and a later native snapshot for that retired
entry is discarded rather than restoring it. Neither eviction nor a failed metrics
query re-executes work. No prompt, output or model bytes accompany retention.

Snapshots die with their service process; no Room/file persistence, restoration or
new process is added. After Binder death MAIN records metrics unavailable for
that endpoint. A new PID/epoch/scope cannot answer for the old invocation, even if
numeric counters repeat. No automatic rebind/replay or resource-fence reset follows
metrics loss. Missing metrics must never become evidence of a missing side effect,
no native work, safe retry or safe model residency.

## Alternatives and implementation obligations

- Keep metrics native-local: valid preparatory work without this IPC change, but
  cannot supply integrated MAIN receipts. Issue #111 remains independently scoped.
- Add fields to current status/health/final chunks or replace getCapabilities:
  rejected here because old strict clients would break; it also couples native
  completion to an early logical terminal and conflates unrelated measurements.
- Put metrics in delta, raw logs, traces or a second runtime: rejected. Those are
  content/side-channel/ownership substitutions, not the versioned private API.
- Make metrics mandatory for inference or accept a CLI/JSON profile to obtain
  them: rejected; measurement does not create admission or qualified configuration.

Before any implementation of this IPC extension, the decision must be explicitly
accepted for this exact scope. Independently authorized native-local work is not
blocked or approved by this proposal. Separate bounded implementation work must prove:

1. Transaction/legacy envelope compatibility for 1.0/1.1/new peers; minor/capability,
   unsupported/unknown-method and malformed optional-reply fallback; mandatory
   issued-history/major checks still refuse the same cases.
2. Closed Parcel size/type/key/mask/count/clock validation, no content/exception
   leakage, no new authority from caller fields and no observation-triggered work.
3. Real source semantics for special tokens, EOG, maxTokens=1, normal false callback,
   cancellation/error partial batches, code0-success counts and defined timer
   boundaries. Same existing runtime/handle only; no extra tokenization/generation.
4. Actual request/invocation/endpoint/boot association; early logical terminal vs
   native/worker completion, absent/changed clocks, death/rebind, stale replies,
   retired IDs and bounded retention. No fake finality from a terminal or live PID.
5. Faulty/slow/saturated collection cannot change original inference, deadline,
   cancellation, cleanup or restart effects; optional absence is safe. Canonical
   unit/Android/R8/native checks and documented overhead remain required.
6. Independent installed-artifact/source/model/environment binding and physical
   model/resource/performance review before qualification. Emulator/build/JSON
   consistency is scoped software evidence, not definitive physical performance.

## Current disposition

This document is a **PROPOSED** decision for a draft PR. It changes no code,
accepted ADR, native instrumentation, configuration, profile or gate. No build,
Android measurement or physical result is supplied. Production plans remain empty;
F4 #64/F5 #89, heavy producer, real limiter stimulus and full-duration workload
remain open. Nothing here declares CORTEX_RUNTIME_READY or opens F6.
