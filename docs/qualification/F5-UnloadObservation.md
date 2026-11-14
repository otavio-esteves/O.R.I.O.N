# Passive Cortex unload observations

Work package: [ORION-F5-UNLOAD-OBSERVATION-001](../issues/ORION-F5-UNLOAD-OBSERVATION-001.md),
[#110](https://github.com/otavio-esteves/O.R.I.O.N/issues/110).
Source base: `39cba9d73c15797917135bd396f5b36da8570b09`.
This package supplies internal ephemeral diagnostics only. It is not a heavy
producer, persisted journal, admission path, performance metric or physical
qualification result. Production owner configuration and its empty qualified
plans are unchanged. No IPC, native, process, schema or policy contract changes.

## Existing behavior and scope

`OrionCortexClient.runInference` already performs non-cancellable best-effort
unload in the owning turn's finally block. Its inference terminal is independent
of whether cleanup can be confirmed. The client still calls unloadModel once,
creates its original 5000ms boot-bound deadline **after that call returns**, polls
getStatus at the original 25ms cadence under the original 5500ms coroutine bound,
and preserves the existing releaseConnection/lostConnection/loaded-null effects.
No missing reply is retried by this diagnostic. Requests that never obtain a turn
do not produce unload events; a refused request whose existing finally finds no
endpoint can produce NO_ENDPOINT without any bind or Cortex call.

The optional `OrionCortexUnloadObservationBuffer` constructor argument defaults
to null. The production owner does not supply it. On that default path there are
no new clock reads, endpoint-scope allocation or Cortex calls. Opt-in metadata
does not change approval, request IDs, inference terminality or cleanup policy.
There are no load/stream hooks, raw prompts/output/model content or callbacks
invoked on the client execution path.

## Confirmation and uncertainty

UNLOADED_OBSERVED requires the **already existing** getStatus response, validated
by the existing Bundle/Parcel/schema guard and additionally containing exactly
schemaVersion and status=UNLOADED. modelId or other operation fields prevent a
diagnostic confirmation even where the old cleanup code tolerates those fields.
The old code's return/release behavior remains unchanged in those cases.

The opt-in path snapshots the selected remote and ServiceConnection under the
client monitor. After the functional timeout/catch completes and loaded becomes
null, it obtains an isolated clock reading and rechecks the same remote,
ServiceConnection, local epoch and closed state under that monitor. An already
known AI PID must still match; no new healthCheck is made to obtain one. An absent
PID remains null. Unknown local identity cannot become a confirmation.

The final check must be in the original deadline's known boot, at/after its start
and the captured pre-RPC poll time, and **strictly before** its expiry. Equality,
missing boot, changed boot or a regression within the window is unconfirmed.
The later diagnostic timestamp is a conservative point observation, not an exact
timestamp of the remote state transition or the RPC's return. A delayed diagnostic
can reject a reply that actually arrived earlier; it must not retimestamp that
reply or restart the deadline to manufacture timely confirmation.

Extra clock reads use the existing OrionClock boundary. AndroidOrionClock reads
BOOT_COUNT through the settings provider and can therefore involve provider IPC;
the guarantee is **no additional Cortex RPC**, not no additional Binder activity.
The extra clock/classification/offer runs outside the original cleanup timeout
and its catch, after loaded=null. A slow diagnostic cannot trigger that timeout
or enter the functional lostConnection catch; a throwing diagnostic clock yields
unavailable time. Clock/provider observation can still add latency to this opt-in
diagnostic path, and is not a calibrated performance measurement.

Nonterminal loop exits retain the existing pre-poll time and ownership facts
before the original releaseConnection clears them. Functional clock exceptions
keep their original disconnect effect and are recorded as TIME_REFERENCE_INVALID
on every iteration. Transport and malformed-envelope exceptions keep their old
effects. Publication failures are isolated from those effects and never cause an
extra unload, disconnect, cancellation or retry.

Outcomes are fixed technical enums:

| Outcome | Scope |
| --- | --- |
| UNLOADED_OBSERVED | Valid existing response plus current endpoint and time checks |
| NO_ENDPOINT | Existing cleanup found no remote; no unload RPC was issued |
| IDENTITY_UNAVAILABLE | Local scope/epoch/connection cannot identify the observation |
| ENDPOINT_CHANGED / CLIENT_CLOSED | Selected endpoint is no longer current or client is closed |
| DEADLINE_EXPIRED / TIME_REFERENCE_INVALID | Expiry, absent/changed boot, regression or unavailable time |
| STATUS_INVALID | Existing status envelope fails validation, or UNLOADED contains contradictory/extra fields |
| UNLOAD_REQUEST_FAILED / TRANSPORT_FAILURE | Existing unloadModel/getStatus failed; no inferred completion |
| POLL_TIMEOUT / CANCELLED | Existing coroutine timeout/cancellation, separate from boot-deadline arithmetic |
| OBSERVATION_UNAVAILABLE | No stronger supported observation |

UNLOADED_OBSERVED proves only that the selected private endpoint supplied that
status with these checks at the recorded point. It does not prove physical/native
release, reclaimed PSS/RSS, future residency, complete process accounting or a
model/resource profile. `nativeRuntimeLoaded` is not sampled or forced false:
the JNI wrapper can remain resident after model unload.

## Bounded collection and identity limits

Each immutable observation contains only clientScope, connectionEpoch,
knownAiPid, deadlineStartedAt, checkedAt and outcome. The start is the original
post-unload-call deadline start, not the beginning of the void call. Null times
or PID are retained as unavailable, never zero. No Binder/Context reference,
request identifier, model ID/path, text, exception or Bundle is retained.

clientScope and connectionEpoch are local ephemeral technical identifiers. A
scope is allocated by one bounded CAS attempt; contention/exhaustion returns
unknown rather than reusing an identity. Connection epochs advance only for the
opt-in client. These identifiers can repeat after MAIN dies, even within the same
boot, and do not authenticate a process, artifact, physical device or cross-process
incident. They are not durable leases or a provenance signature.

The final owned buffer has capacity 1–64 (default 16), retains the oldest records
and drops new records when full or contended. offer uses tryLock, never waits and
invokes no user code. drain also uses tryLock; a contended drain consumes nothing
and explicitly marks its empty result as contended. Successful drains return a
bounded immutable copy. Consumers cannot hold the client's monitor/turn through
an injected callback or mutate retained records.

droppedAtLeast, contendedAtLeast and recordingFailuresAtLeast are cumulative lower
bounds. Counter updates use one bounded CAS; contention/overflow marks
lossAccountingComplete=false rather than inventing exact zero losses. Counter
reads and the drain are not an atomic loss snapshot. A recording fault similarly
marks accounting incomplete; no automatic second publication is attempted.
Consumers must inspect those flags and cannot treat an empty batch as complete
coverage. This buffer grants no inference/admission/qualification authority.

## Validation boundary

Supplied tests include ten JVM cases for the exact classifier used by the client,
within-window clock regression, bounded/immutable/concurrent collection and busy
producer/drain behavior. The test-only collector-lock fixture proves a contended
producer returns without waiting; no client private endpoint is injected.

Fourteen new scripted Android cases extend the existing client fixture (28 cases
in the class), using no native model. They cover current getStatus confirmation,
no endpoint, strict deadline/boot checks, a failing extra clock, default-noop clock
behavior, close/disconnect across the reply, cross-thread disconnect during a
blocked status call, malformed/contradictory replies, transport/request failures,
deadline/coroutine timeout, saturation and replay. Existing initial-expiry and
cancellation cases additionally check absence/uniqueness of unload observation.
Review regressions include UNLOADING then a failure of the second functional
clock read, poll5000/reply4000 within the same window, and a slow 6000ms diagnostic
read after UNLOADING/UNLOADED compared with the default path. Inference terminal,
Cortex RPC and disconnect counts must remain unchanged.

These JVM/Android tests await root's canonical execution; this worktree ran no
Gradle, Docker, ADB or device command. Source checks confirm unchanged Cortex and
bind/unbind call sites, and git diff --check passes. No software test or DTO from
this package is assigned physical provenance. F4 #64/F5 #89, performance/profile
calibration, heavy producer, actual limiter stimulus and real-duration soak remain
open; no CORTEX_RUNTIME_READY or F6 activation is claimed.
