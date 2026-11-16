# Complete MAIN/HOST RuntimeEvidence v1 capture contract

[Work package](../issues/ORION-F5-RUNTIME-CAPTURE-CONTRACT-001.md),
[#141](https://github.com/otavio-esteves/O.R.I.O.N/issues/141).
Reviewed main: `83fec5f4cd483d14e05774cfb31a3b0078aa6d6c`, on 2026-11-16.
This specifies future implementation. It supplies no producer, executable mode,
new admission configuration, run, calibrated profile or qualification result.

## Decision and authority

Implement a bounded, qualification-only capture on the **existing MAIN owner**,
then a pure HOST assembler for the unchanged
[RuntimeEvidence v1](F5-RuntimeEvidenceProtocol.md). The producer must collect
every field from the sources below; it cannot turn format125 into v1 by renaming
fields. Split bytes from [#140](https://github.com/otavio-esteves/O.R.I.O.N/issues/140)
are integrated by [PR #143](https://github.com/otavio-esteves/O.R.I.O.N/pull/143).
New dispatch/terminal/cleanup checkpoints, a dedicated
observation clock and a fenced memory sampler are other required sources.

Keep the original Core barrier, registry/READY/file checks, request ledger and
turnstile, capability/permission checks, ResourceGovernor, restart/backoff,
deadlines, cancellation, callback and cleanup behavior. Capture never supplies a
resource decision, starts/binds/rebinds AI for missing evidence, retries, imports
a model, repairs a registry row or selects a workload. The production factory's
plans remain empty, timing and attempt template remain null. No CLI, Bundle,
JSON, hash or parser limit can populate those values or grant admission.

The selected mechanism stays inside existing boundaries: final internal MAIN
recorders/DTOs; existing read-only private Cortex operations; existing
qualification instrumentation transport; HOST-owned technical files. Normal
Debug/Release have capture disabled. The separate qualification identity uses
the same existing Application/FoundationRuntime/Room/owner/client, never a clone
or second runtime. If an existing owner cannot accept the reviewed collector,
report source unavailable instead of replacing it.

This is not an architecture waiver. Before Android composition, review the actual
manifest, dependency, caller-validation, wire and storage diff. In-process capture,
pure rendering and HOST assembly require no new architectural authority. A new
service/provider/process, exported bridge, Binder/AIDL method or changed existing
wire envelope, authority/ownership transfer, production profile bypass, new
business persistence/lease/recovery strategy is **ADR REQUIRED before code**
under [AGENTS §1.1](../../AGENTS.md). Do not substitute such a mechanism silently.
An extension of the existing test-only instrumentation mode is acceptable only
while it preserves its existing same-UID/non-debuggable qualification boundary,
component/process topology and all old modes. Any inability to prove that scope
requires the ADR screen to be resolved before composition.
Accepted [ADR-0003](../adr/ADR-0003-optional-cortex-generation-metrics.md) does not
implicitly authorize this new hook/source capsule/export; this separate bounded
contract defines them. Its existing Cortex operations/envelopes stay unchanged.

## Baseline sources and genuine gaps

| Source | Available now | Required addition or limitation |
| --- | --- | --- |
| [Owner](../../app/src/main/kotlin/network/orion/app/ai/OrionCortexRuntimeOwner.kt) | Actual trusted plan/READY/file association and existing client/runOnce ownership | Copy a bounded technical identity only after those original checks; never infer READY from a HOST file hash |
| [Client](../../app/src/main/kotlin/network/orion/app/ai/OrionCortexClient.kt) | Issued load/inference IDs, load's original local start, actual calls/replies, current endpoint/epoch/PID | Explicit typed dispatch/terminal/cleanup markers; current `specification.startedElapsed` for inference is the older request deadline start, not dispatch |
| [Lifecycle facts #132/#140](../../ai/api/src/main/kotlin/network/orion/ai/api/OrionInferenceLifecycleFacts.kt) | Associated original deadline samples, first-terminal origin, ordered-release metadata, optional validated byte parts and loss | No exact event clock or complete v1 events; never rename `associatedOriginalDeadlineSample` as event time |
| [Byte guard](../../ai/api/src/main/kotlin/network/orion/ai/api/OrionInferenceContract.kt) / #140 | Two original UTF-8 encodings, total guard and integrated passive split-byte source | Reuse actual successful text/tool byte results for the exact ordered chunk; no reencoding, rescanning content or division of a total |
| [Resource observer](../../app/src/main/kotlin/network/orion/app/resources/OrionAndroidResourceObserver.kt) | MAIN self-PSS, private AI telemetry and original resource-policy observations | These are not seven phase-complete rows: preserve real collection boundaries, service state and endpoint identity |
| [Memory adapter](../../app/src/main/kotlin/network/orion/app/resources/OrionCortexMemoryTelemetry.kt) | Strict same-boot/PID/inventory/freshness validation | A qualification sampler must retain raw typed timing/flags and exact windows; not just aggregate totals |
| [Partial projector](F5-CyclePartialProjection.md) | Bounded deliberately stripped JSON | Exactly unchanged; omitted IDs/times cannot be recovered from its output |
| [Single attempt](F5-SingleAttemptCore.md), [R8 entry](F5-SingleAttemptR8.md), [HOST130](F5-SingleAttemptHostCapture.md) | Existing owner and one source-bound partial program | All closed formats remain unchanged; no loop of HOST130 and no new keys in its output |

The following names denote semantic typed ports, not delivered classes/APIs.
Implementation must introduce the sources and their tests before declaring the
producer complete; a mapper populated with null guesses is not that producer.

## Session, fencing and observation clock

One opaque capture belongs to one actual original `runOnce`, one model, one MAIN
process and one private AI endpoint. Associate it with the exact owner, client
scope, ServiceConnection/Binder reference, independently advancing epoch,
execution/turn generation, known AI PID and boot. A replay/join/queued request is
not a fresh native turn; a constructor's samples cannot attach to retained work.
Admission/turn entry, IPC acceptance, native entry and native completion remain
different facts. Do not acquire any of them from request ordering or a global
"latest request" pointer.

Before and after any optional RPC/measurement/manual export, validate the same
fence and actual model/request association. Never hold the client monitor across
Binder, provider, filesystem or sampling work. Close, another turn, epoch/PID/boot
change, missing identity, exhaustion or lost ownership invalidates new capture.
Do not refresh evidence by rebinding or querying a replacement AI. A new record
requires another explicitly authorized attempt; it is not replay permission.
Capture teardown releases only its technical buffers/references. It never closes
the shared owner, unbinds, cancels, unloads or deletes model/application state.

Prepare a **final dedicated observation-clock adapter in core/time** in an
inactive state, without provider access. Bind its observational boot cache once
when the actual original `runOnce` owner enters, using the existing known
`request.deadline.start` created by the original driver preparation. Require
agreement with the original lifecycle's already-performed boot validation;
unknown/invalid/missing proof refuses binding. No new `monotonicNow` obtains the
cache, and a replay/join constructor cannot bind or establish execution.

The adapter uses the native Android elapsed-realtime domain through the injected
time boundary. The original witness establishes boot identity only; its timestamp
is not reused as the current session/event time. Read that from the dedicated
adapter at the actual source point after binding. The cache belongs only to this
observational session/MAIN process. Never pass it to request deadlines, governor,
restart, execution leases or another run, and never persist it as a lease. Unknown
boot refuses clock binding; do not accept a boot value from CLI/JSON.

Event reads must be local elapsed-realtime reads, with no Settings/provider,
Binder, filesystem, arbitrary function callback, waiting or worker join. Keep
platform time calls in a final platform adapter, not domain code. A generic
`OrionClock` provider read is not an acceptable event-hook implementation merely
because it has that interface:
[AndroidOrionClock](../../core/time/src/main/kotlin/network/orion/core/time/AndroidOrionClock.kt)
reads BOOT_COUNT synchronously on every monotonic call, with no cached-success
fallback. Preserve that implementation and its null-on-provider-failure semantics.
The original functional clock object, read count/order, deadline checks and thrown
objects remain unchanged. New measurement reads belong to this dedicated capture
source and are explicitly additional instrumentation overhead.

Use fixed final recorders with nonwaiting `tryLock`, bounded scalar records and
reserved first-terminal storage. All optional allocation/projection/clock failures
are contained; they never replace an original return/Error/cancellation, add a
functional retry, reset a deadline or wait in cleanup. Default-absent capture
allocates no diagnostic envelope or enum outside its isolated guard. There is no
hard wall-latency promise under OS scheduling/GC; CPU, allocation and memory
overhead must be disclosed and physically measured before performance approval.

Reject missing, regressed, cross-boot or inconsistent event times. Never clamp,
offset, retimestamp, borrow an earlier sample or use wall/HOST time to repair
them. Compare session identity with original samples, the endpoint and independent
HOST boot windows before/after capture. An original unknown boot/provider failure
or any disagreement invalidates observation; cached boot must not be called fresh
or used as fallback. Independent fresh checks belong outside the original run/
cleanup path. Later recovery cannot repair or reattach an invalidated session.
A timeout of an observer does not prove a synchronous Binder/provider call
terminated. Observation faults remain source unavailable/loss, not domain failure.

## Actual event points and the qualified consumer seam

All event time below means the MAIN observation point specified here, in elapsed
milliseconds; it is not JNI/kernel/server execution time or a backend barrier.

| Event | Acquisition point and publication rule |
| --- | --- |
| Load start | Copy the existing local `started` instant used to create that actual issued load specification, retaining that precise local-request-start meaning. Publish only with proof the corresponding original load call was issued; it is not a native-entry time |
| Load terminal | New final typed marker at MAIN's original validated terminal branch. SUCCEEDED additionally requires the original validated LOADED reply for the selected model/current endpoint. Sample the observation clock there; pre-RPC `pollAt`, Boolean `false` or a later poll cannot manufacture a load terminal |
| Inference start | New MAIN dispatch marker immediately adjacent to the original `startInference` invocation, with actual issued ID/input size/specification association. This is dispatch-attempt observation, not proof of service/native admission. Do not use `request.deadline.start`, MARK_LOADING's deadline sample or first chunk as dispatch time |
| First inference terminal | New observation marker only after the original first terminal transition succeeds. Preserve its exact status/origin/reference and use one reserved slot. Subsequent status, cancel/death and late callbacks cannot overwrite it |
| Ordered chunk acceptance | Capture a dedicated event instant at the actual ordered-acceptance decision for that chunk, staged with #140 split bytes. Publish chunk records only after the original update is successfully constructed. Buffered admission/raw arrival is not this event. For the winning final chunk, use the same canonical terminal event instant as its first-terminal marker |
| Unload start | New marker adjacent to the actual original void `unloadModel` call, identifying request issuance only. Its later cleanup `deadline.start` is not dispatch or native release |
| Unload completion | New marker at the original validated UNLOADED observation, fenced to the same endpoint/boot. A void return, wrapper/PID existence or pre-RPC poll time is insufficient. Apply the existing strict reply/deadline/identity validity rules; late/unknown observation remains unconfirmed, without changing original cleanup |

Markers add no query or extra read to the original **functional clock**. Their
dedicated clock is owned by the final qualification capture source. They must
not invoke arbitrary observers on the algorithm path. Loads without a known
terminal and cleanup without a confirmed event remain unavailable. Endpoint
death can yield the original MAIN PROCESS_DIED terminal; it does not prove JNI
returned. Main death loses local capture and leaves HOST outcome unknown.

The original `onChunks` supplied by a new qualified measurement program is also
a valid seam for **real delivery observation**: a fixed final consumer can sample
its dedicated clock at entry, receive the same original List, record bounded
numeric association, and discard content. It is the caller's original consumer,
not a wrapper around an existing product callback. No text/tool content, lengths
or hashes are inspected there; split bytes come from #140's original validation.
Do not await a memory worker or perform provider/Binder work in this consumer.

Delivery entry/normal return, ordered acceptance, logical terminal and presentation
are separate events. This contract selects **ordered acceptance** as v1's canonical
`receivedElapsed` boundary so the winning final chunk and first-terminal time
agree. A consumer's later delivery time cannot be substituted for the earlier
transition or inference start. Delivery observations may remain bounded technical
sidecar facts; they are not extra fields in v1. A lost/throwing consumer does not
erase an already-won terminal or become evidence that output was delivered.

## Field-to-source map: identity and session

HOST assembles the final unchanged v1 identity after checking actual installed
artifacts. MAIN supplies technical runtime facts from its owned source. Caller
metadata can reject a mismatch, never choose an action/model or grant authority.

| v1 field | Actual producer and acquisition |
| --- | --- |
| `schemaVersion`, `kind` | Fixed v1 literals in the pure encoder |
| `declaredEnvironment` | Explicit HOST execution environment, corroborated by target guards; remains a declaration, never authentication |
| `identity.sourceCommit` | Full reviewed clean Git commit from the complete HOST source freeze, not an arbitrary app log |
| `identity.sourceManifestSha256` | SHA-256 of the exact retained complete tracked-source manifest bytes checked by HOST |
| `identity.appPackage`, `testPackage` | Actual selected installed qualification app and matching test package, cross-checked with MAIN ApplicationInfo/UID and framework target; v1 supports both identities but this new composition targets only `network.orion.qualification` |
| `identity.appApkSha256`, `testApkSha256` | Local APK hashes compared with actually installed base APK hashes on the same target before and after; split/unavailable/mismatched artifacts reject capture |
| `identity.modelSha256`, `modelSizeBytes` | Actual MAIN selected READY registry row, original profile match/read-only file-open checks and the service's original descriptor/digest contract; never a copied DB row or unrelated private file |
| `session.bootCount` | Known original MAIN preparation/admission boot, matching all event/sampling witnesses |
| `session.bootIdSha256` | HOST hash of actual device boot identity, checked before/after the same window and joined with MAIN boot count; no raw boot UUID retained |
| `session.mainPid`, `aiPid` | Actual current MAIN PID and AI PID authenticated by the existing private connection/receipt; distinct, fixed for the record, never caller-selected |
| `session.startedElapsed`, `completedElapsed` | Dedicated MAIN capture-clock points bracketing the recorded attempt and its separate finite post-run observation; actual same-boot points, not first/last HOST polls |
| `session.maximumSampleAgeMillis` | Explicit trusted observation configuration within v1's 1–1000 ms ceiling. No default and no value derived from the ceiling; missing/incompatible configuration prevents complete rows |
| `session.processInventory` | MAIN's complete actual selected-artifact manifest inventory, including disabled/configured components. Exactly MAIN+AI is representable; never omit Debug voice probes to fit |
| `session.api`, `abi`, `pageSizeBytes` | Actual target properties/page-size observation, cross-checked with app/build signals and selected artifact |
| `session.deviceBuildSha256`, `totalMemoryBytes` | Hash of actual target build identity and observed MemTotal, stable across HOST windows; not a qualified RAM budget |
| `candidate.contextTokens`, `threads`, `maxTokens` | Actual admitted original request/load configuration associated with the trusted template/READY model; not parser/CLI defaults |
| `candidate.temperature` | Actual normalized wire temperature of that inference specification, not a later reconstructed preference |

READY/file/hash agreement and these source bindings do not approve a profile or
authenticate a source. Full source/build provenance remains a separate review.
Current empty plans/timing/template cause a technical prepare refusal, with no
fabricated runtime session/load/model association or v1 document. Physical model
provisioning must use the actual MAIN importer/registry in the qualification
identity; emulator ADB-root staging is not a physical provisioning procedure.

## Field-to-source map: operations and chunks

| v1 field | Actual producer and rule |
| --- | --- |
| `load.requestIdSha256` | SHA-256 of the actual service-issued load ID, privately associated at dispatch; never reserve an ID solely for evidence |
| `load.modelSha256` | Same actual selected/model descriptor identity as `identity` |
| `load.startedElapsed`, `completedElapsed` | The load start/terminal sources above; completion is not reconstructed from a poll reference |
| `load.terminal` | Actual original load terminal reply/decision. Ambiguous transport or a bare `false` cannot be translated into FAILED/SUCCEEDED |
| `inference.requestIdSha256` | Hash of the actual logical ID inside the real owning runOnce |
| `inference.transportRequestIdSha256` | Hash of that actual admitted/issued transport ID on the same endpoint; it must differ from logical and load IDs |
| `inference.modelSha256` | Same selected model; no mapping to another retained request |
| `inference.startedElapsed` | Actual new MAIN dispatch checkpoint, after observed load completion; not the older deadline start sent in the specification |
| `inference.terminalElapsed` | Actual first terminal observation; CALLBACK uses exactly the final ordered-acceptance marker |
| `inference.deadlineElapsed` | The original immutable request deadline; never extended by observation, sampling or export |
| `inference.inputBytes` | Actual original SharedMemory/input payload byte size before zeroing, including the original context/prompt separator. Do not reencode or retain its content/hash |
| `inference.terminal`, `terminalSource`, `failureCode` | Actual winning lifecycle result/origin: accepted final chunk maps to CALLBACK, other representable original transitions to MAIN_LIFECYCLE. Preserve original failure enum; cancellation/death/timeout cannot be inferred from HOST transport failure |
| `inference.chunks` | Only successfully constructed original ordered-acceptance updates for this inference; no buffered, duplicate, wrong-request, late or rejected input as accepted delivery |
| `chunks[].requestIdSha256`, `sequence` | Hash of the same actual logical request and original ordered zero-based sequence |
| `chunks[].receivedElapsed` | Canonical actual MAIN ordered-acceptance event, not #132 sample, raw queue arrival or consumer-entry time |
| `chunks[].textDeltaBytes`, `toolDeltaBytes` | Integrated #140 `validatedTextDeltaUtf8Bytes`/`validatedToolDeltaUtf8Bytes` for that exact chunk/release, including known zero and matching `validatedTotalContentUtf8Bytes`. Both parts are unavailable together on missing bookkeeping; never substitute zero or reencode |
| `chunks[].isFinal`, `finishReason`, `errorCode` | Actual validated chunk metadata and winning terminal association; nonfinal reasons/errors absent, final equals terminal |
| `unload.startedElapsed`, `completedElapsed` | Actual request-issuance and confirmed asynchronous-completion observation markers above |
| `unload.result` | UNLOADED only from the valid original reply/current identity; FAILED only when an actual observed cleanup failure has valid bounds. Otherwise `unload=null` with explicit capture limitation |

Strict UTF-8 hash actual identifiers only, with bounded temporary buffers; never
hash prompt/output/request fingerprints as a substitute for identity. Do not log
raw IDs. Preserve origin distinctions from #132. In particular, a callback
TIMED_OUT before MAIN's deadline differs from MAIN deadline expiry. All accepted
chunk times must precede the real deadline. If a source time cannot satisfy v1,
retain a limitation/refuse that record; never clamp it into the allowed interval.

The record requires terminated load and fixed MAIN/AI identities. No load,
ongoing/unknown load terminal, missing mandatory time or unknown AI PID means a
technical **NO_V1_RECORD**, not a synthetic empty successful load. A valid failed
load can form an incomplete v1 record without inference/unload. Missing inference
capture can be represented as `inference=null`, explicitly meaning unobserved,
not proof that no invocation occurred. Never keep a successful inference with a
truncated/lost chunk sequence. Preserve invocation uncertainty in the sidecar.

## Seven memory phases

Use one finite qualification sampler on an existing current endpoint. It issues
only existing read-only `healthCheck`/`getStatus` operations and local MAIN PSS
reads. It never creates a binding, runtime, model or new resource decision. Each
attempt validates the full fence before and after. A new optional read-only port
must contain its own query failure; do not reuse an optional call path that invokes
`lostConnection`, cancellation or terminal changes on diagnostic failure.

The sampler owns collection clocks/work outside original run/cleanup/consumer
return. Phase hints are bounded technical events, not arbitrary callbacks. Do not
pause the execution turn, stretch a phase, reset a deadline or wait for a worker
to manufacture coverage. Attempt every applicable phase; an unobservable short
window is genuinely missing. A complete producer implementation is able to
collect all seven on controlled sources; it does not guarantee seven real samples
in every run. A later plan needing measurement checkpoints that deliberately hold
work must separately review that execution/overhead contract before code.

| Phase | Actual window and allowed observed state |
| --- | --- |
| BEFORE_LOAD | Session start through the original load start; current bound AI observed UNLOADED. If AI is not already available within original guards, leave absent; do not bind just for this cell |
| DURING_LOAD | Actual load start through terminal; LOADING or LOADED |
| AFTER_LOAD | Confirmed successful load through inference/unload start or session end; LOADED |
| DURING_INFERENCE | Actual inference dispatch through first terminal; INFERENCING or LOADED |
| AFTER_TERMINAL | Live inference terminal through unload start or session end; LOADED |
| DURING_UNLOAD | Actual unload start through confirmed completion; UNLOADING or UNLOADED |
| AFTER_UNLOAD | Confirmed UNLOADED through session end, before another turn; UNLOADED |

Close the actual event windows before assigning rows. A collection crossing a
boundary cannot be relabeled into the next phase or clipped; omit it with a fixed
reason. Serial collection windows must remain nonoverlapping/in phase order.
After PROCESS_DIED, omit unload and all subsequent AI rows; a replacement PID is
another attempt, not a repaired sample.

| Memory field | Actual acquisition/validation |
| --- | --- |
| `phase` | Closed actual event-window classification above, not caller-selected |
| `startedElapsed`, `completedElapsed` | Sampler's actual same-boot collection start and end, bracketing all retained readings/state validation |
| `bootCount`, `mainPid`, `aiPid` | Same source session/fence, checked throughout the row |
| `mainSampledElapsed` | Actual MAIN collection-start point immediately before local Debug.MemoryInfo acquisition |
| `aiSampledElapsed` | Unmodified original private AI receipt's actual sample time; never HOST/capture time substituted |
| `mainPssBytes`, `aiPssBytes` | Positive actual `Debug.getMemoryInfo().totalPss * 1024` in each owning process, never ActivityManager cross-process cache or Java heap |
| `aggregatePssBytes` | Exact overflow-checked sum, within observed total memory |
| `mainNativeIncluded`, `aiNativeIncluded`, `source` | True only for the actual native-inclusive self-PSS source; fixed SELF_DEBUG_MEMORY_INFO_PSS, not proof of independent graphics/protected-memory coverage |
| `serviceState` | Actual validated `getStatus` from this endpoint, observed for this collection window and checked against its phase |
| `nativeRuntimeLoaded`, `nativeValidationCompleted` | Actual original health reply flags from the same fenced source; never infer from state/PID or reset after unload |

Enforce start ≤ MAIN sample ≤ AI sample ≤ completion, and completion minus MAIN
sample **strictly below** explicit `maximumSampleAgeMillis`. Missing/zero/stale/
partial/unknown observations yield no complete row. The service retains its native
wrapper/validation after successful model unload, so true flags with UNLOADED
are legitimate. Sampling CPU/thermal/memory interference is disclosed; this
format does not establish complete physical allocation coverage or calibrate it.

## Capture bounds and loss

Defensive limits are representation limits, never safe workload defaults:

- One active attempt, model and fenced endpoint; no retained multi-request history.
- At most 4097 accepted chunk records, 128 complete memory rows and 32 scalar
  operation markers; reserve first-terminal storage independently of saturation.
- At most one phase-triggered memory attempt per applicable phase in the first
  implementation; at most seven starts and no timer/poll-until-filled loop. Each
  attempt performs one health and one state RPC. Missing phase triggers are loss.
- Sampler lifetime is bounded by the actual request/cleanup window plus one
  explicit post-run observation step. Manual finalization has a defensive 12 s
  watchdog, not a request duration, freshness value or calibrated operating
  setting. There is no keep-alive/relaunch or observer loop after the source
  fence/window ends. A bound expiring does not prove a blocked synchronous RPC
  stopped; fence/drop late completion and report unavailable/unknown as applicable.
  Permit at most one sampler worker/outstanding sampling RPC in this qualification
  process. A stuck reader occupies that capacity; subsequent captures cannot
  create replacement workers or restart a process to conceal the degradation.
- No blocking enqueue, retry-until-lock, size clipping or fake zero. Expose fixed
  lower-bound dropped/failure counts, loss-accounting state and fixed source-gap
  reasons. Failed publication never resurrects records or changes original work.

Do not depend on #132's first 32 retained updates to recover all 4097 chunks. The
new source must consume #140's real original validation/release facts at their
typed acquisition point, into its own bounded final recorder. The old facts,
formatter125 and issued-history retention remain unchanged.

Memory-row loss can leave an otherwise structurally valid incomplete v1 document.
Chunk/identity/mandatory-time loss cannot be hidden by emitting a successful
prefix. Native metrics remain outside v1: no token/s, TTFT or synchronized native
compute is inferred from bytes, callbacks, phase clocks or API-wall durations.

## Finite export and HOST assembly

Select bounded inline export/refusal for the first implementation; no new large
payload path is selected by this contract. A full v1 input may allow 4 MiB, but
that is **not** permission to send 4 MiB through Binder.

The new qualification-only build mode may use the existing framework
instrumentation component and its same-UID Context/Bundle bridge pattern only
after the ADR screen above. Its default is disabled, ordinary Debug/Release stay
false, and no metadata argument can enable admission. Do not add a service,
change process roles or initialize main-owned infrastructure from AI. Keep every
existing ordinary/metrics/baseline/readiness/exit/single-attempt mode and its exact
keys unchanged. Runtime capture must be a mutually exclusive selected mode with
its own closed output contract and dedicated HOST parser, never new fields in
125/128/130 or a second invocation of their phases.

The final source Bundle has exactly:

| Key | Content/bound |
| --- | --- |
| `orionRuntimeCaptureStatus` | Closed ASCII technical header, at most 1024 UTF-8 bytes |
| `orionRuntimeCaptureSource` | Optional closed ASCII MAIN source capsule, at most 96 KiB UTF-8 bytes |
| `stream` | One fixed success/refusal/unavailable stream label; no content |

The source capsule carries exactly schemaVersion=1, kind=ORION_MAIN_RUNTIME_CAPTURE,
`mainIdentity`, `session`, `candidate`, `load`, `inference`, `unload` and `memory`.
`mainIdentity` has exactly appPackage, testPackage, modelSha256 and modelSizeBytes.
`session` has exactly bootCount, mainPid, aiPid, startedElapsed, completedElapsed,
maximumSampleAgeMillis and processInventory, all from MAIN sources. Other sections
are exactly the source-derived v1 sections mapped above. HOST adds
only the separately verified identity/platform fields, not missing event data.
For the SOURCE capsule only, chunks factor their common logical identity at
`inference.requestIdSha256`; each chunk retains explicit sequence, receivedElapsed,
textDeltaBytes, toolDeltaBytes, isFinal, finishReason and errorCode. The assembler
repeats exactly that proven common hash when expanding unchanged v1 chunk fields.
No times or sequence values are reconstructed or compressed.
Unknown capsule fields/types/keys are rejected. The capsule contains hashes of
actual IDs, never raw IDs, content, paths, raw errors or content hashes.

The status header has exactly schemaVersion=1, kind=ORION_RUNTIME_CAPTURE_STATUS,
qualification=NOT_QUALIFIED, recordState=NO_LOAD_REFUSAL|NO_V1_RECORD|V1_SOURCE,
captureIntegrity=COMPLETE|INCOMPLETE|UNAVAILABLE, nullable closed failureCode,
nullable ownerCallInvoked, droppedAtLeast, recordingFailuresAtLeast,
lossAccountingComplete, and the eight false flags:
sourceAuthenticated, physicalVerified, profileApproved, productionInferenceApproved,
runtimeEvidenceV1Complete, fullF5GateClosed, fullSoakGateClosed, f6Authorized.
Both counters are nonnegative Long lower bounds; accounting is a strict Boolean.
ownerCallInvoked describes actual issuance of this program's runObserved call,
not selection, native entry or execution success; missing proof remains null.
Failure codes are exactly NO_QUALIFIED_PROFILE, CONFIGURATION_UNAVAILABLE,
SOURCE_UNAVAILABLE, IDENTITY_UNAVAILABLE, IDENTITY_CHANGED, CLOCK_UNAVAILABLE,
CLOCK_DISCONTINUOUS, LOAD_TERMINAL_UNAVAILABLE, INFERENCE_EVENT_UNAVAILABLE,
UNLOAD_UNCONFIRMED, PHASE_MISSED, CAPTURE_LOSS, CAPTURE_LIMIT, EXPORT_TOO_LARGE,
FRAMING_INVALID, TRANSPORT_UNAVAILABLE or INTERRUPTED; never class/message strings.
NO_LOAD_REFUSAL is permitted only with an actual pre-invocation refusal and no
load/v1 capsule; an issued-but-unknown call cannot use that state. V1_SOURCE is
source availability only, not a complete-cycle or qualification result.
The eight flags remain false even when a resulting document is structurally
consistent. HOST reports v1's own metadata-completeness result separately; parser
acceptance cannot set an authority/qualification claim.

Render only after the original owner run has returned/thrown, in a separate
manual phase. A render/export failure cannot change its result/Throwable or
perform hidden observe/release work during unwinding. Source text is generated
from closed typed fields; do not call arbitrary `toString`/reflect private objects.
Measure the actual `Parcel.writeBundle` size and require **≤224 KiB for the
Bundle**, reserving at least 32 KiB below the architectural **256 KiB total inline
IPC ceiling** for framework/component framing. UTF-8 size is not UTF-16 Parcel
size; serializing a Bundle does not measure the entire framework transaction.
The composition's source/maximum-shape integration checks must also prove the
fixed outer envelope fits that reserve. If they cannot, refuse source export
rather than claim total size from Bundle size alone. Oversize produces a small
unavailable header with no capsule, never clipping/chunking/replay.

HOST's raw transport has a 128 KiB streaming bound, strict UTF-8, one final result
frame/code, no intermediate status flood and no raw stdout/stderr persistence.
It performs one explicitly selected program invocation on an allocated target,
using source/local/installed app+test checks before/after and actual boot/build
windows as in #130; it does not install, provision, grant, alter settings, kill,
retry or clean Android state. Add no model/profile/workload/template CLI inputs.
Expected model provenance is checked against MAIN's actual result, never used to
select or create READY state. Preserve invocation UNKNOWN after timeout,
interruption, lost framing or identity changes, even if the local ADB child ends.

The pure HOST assembler fills the eight v1 identity fields from the checked
freeze and actual MAIN model association; it fills platform session fields from
the same target/window. It does not synthesize a missing dispatch/terminal,
PID/boot, byte count, phase or memory sample. Validate final ASCII/UTF-8 size ≤4 MiB
and run the unchanged v1 validator against a separately bound expected identity.
Persist only valid closed technical material in a new owned HOST evidence
directory: runtime-v1.json when representable, capture-status.json, checked
identity/source references and hashes. No raw transcript/file-content hash is a
substitute for accepted minimized output. Publication uncertainty cannot trigger
another Android invocation. This is existing HOST technical evidence storage,
not Room/DataStore/business outbox persistence or an execution/replay ledger.

An honest valid v1 record may be CONSISTENT_INCOMPLETE_METADATA. Missing inference
means unobserved, not proven absent; sidecar ownerCallInvoked/loss preserves that
distinction. COMPLETE capture integrity, RESULT_OK, parser exit zero or hash
agreement never authenticates the producer/model/profile or closes any gate.

## Instrumentation and autonomy boundary

This is a bounded, instrumented measurement program, not an autonomous Core soak.
In the reviewed [AOSP Android 12 startInstrumentation source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/am/ActivityManagerService.java#13818),
the normal path stops/relaunches the target; noRestart takes a different path.
The pinned [RunningInstrumentation OOM-adjuster test](https://android.googlesource.com/platform/frameworks/base/+/a7417777011e69da4319ed780d6a948f77c13bf0/services/tests/mockingservicestests/src/com/android/server/am/MockingOomAdjusterTests.java#443)
expects foreground-service process state/foreground adjustment. These are scoped
platform source facts, not physical/API37 runtime qualification.

The current [minimum governor](../../core/resources/src/main/kotlin/network/orion/core/resources/OrionMinimumResourceGovernor.kt)
additionally requires `sample.foreground=true` **and**
`sample.importance=FOREGROUND`; otherwise it refuses BACKGROUND. The actual
[Android observer](../../app/src/main/kotlin/network/orion/app/resources/OrionAndroidResourceObserver.kt)
maps only `RunningAppProcessInfo.IMPORTANCE_FOREGROUND` to FOREGROUND, other known
states to BACKGROUND and unavailable state to UNKNOWN. Instrumentation or
foreground-service status does not automatically satisfy that observation.
A future approved execution procedure must observe real MAIN foreground through
the permitted UI/platform state or accept the original refusal. This contract
selects no activity, settings operation or operational value. Never fabricate,
pin or reinterpret foreground in a capture/controller adapter. Background remains
a refusal/degradation case under the current minimum policy; actual foreground
alone does not waive its other gates.

HOST130's fixed command does not request no-restart. Looping it cannot establish
one uninterrupted existing-owner session. Selecting no-restart or attaching
instrumentation for 24 hours also does not establish normal background importance,
autonomy, battery/Doze behavior or the approved heavy-soak workload. That execution
and process-importance decision is separate; parser/FSM correctness resolves none
of it. No device is available for physical qualification at this review.

## Implementation sequence and required proof

1. Consume integrated #140 split bytes without extra encoding; preserve its exact
   validation/exception/count/List behavior and nullable failure semantics.
2. Implement the final session/fence/event clock and bounded typed recorder.
   Prove default absence, exact dedicated-clock bounds, unknown/regressed boot,
   contention, allocation faults, reserved terminal and no original-clock changes.
3. Add narrowly scoped Client/lifecycle event acquisition at the points above.
   Prove actual dispatch versus reservation, first-terminal origin, late callbacks,
   failed update construction, replay/join/queued expiry, callback Error/cancellation
   and the original returned objects. No observer-generated functional RPC.
4. Implement the qualification consumer's separate delivery source and the finite
   read-only memory sampler. Script all seven real windows, transitions during a
   sample, loss, missing state/PSS, stale age, endpoint/boot/PID replacement and
   late completions. Prove optional failures never call lostConnection/retry.
5. Implement the pure source-capsule encoder and HOST assembler/strict parser.
   Test every required v1 field, known zero versus unavailable, failed load,
   callback versus MAIN timeout, loss, unknown invocation, duplicate/extra fields,
   byte/actual Parcel bounds, encoding and sensitive sentinel non-persistence.
6. Review Android composition's actual boundary diff before enabling the new
   qualification-only mode. If a new architectural surface is required, open the
   scoped ADR first. Run canonical Docker source/JVM/HOST/build/lint/R8 controls;
   use mechanical/emulator negatives with unchanged empty configuration. Synthetic
   positives validate mechanisms, never physical provenance/profile authority.
7. Only after separately reviewed trusted configuration, provisioning and physical
   allocation exist may an actual measurement run produce evidence for review.
   Keep failed/incomplete attempts. Independent memory/native/API coverage and
   calibrated budgets remain required; seven PSS rows are not those approvals.

Each unit needs its own bounded work package and truthful source/artifact freeze.
None can declare the producer implemented by completing this document alone.
No operational values are chosen here. The complete runtime producer, physical
MemoryLimiter stimulus and approved heavy-work/stress/24-hour executor still need
implementation/measurement. F4/#64, F5/#89, soak and F6 remain open.
