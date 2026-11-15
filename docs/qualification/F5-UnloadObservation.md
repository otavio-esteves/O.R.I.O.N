# Passive Cortex unload observations

Work package: [ORION-F5-UNLOAD-OBSERVATION-001](../issues/ORION-F5-UNLOAD-OBSERVATION-001.md),
[#110](https://github.com/otavio-esteves/O.R.I.O.N/issues/110).
Original observation source base: `39cba9d73c15797917135bd396f5b36da8570b09`.
Isolation follow-up: [ORION-F5-UNLOAD-ISOLATION-001](../issues/ORION-F5-UNLOAD-ISOLATION-001.md),
[#118](https://github.com/otavio-esteves/O.R.I.O.N/issues/118), base `e7cf08b`.
The validation below for issue110 predates the isolation follow-up.
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
null, it submits a typed facts-only ticket without waiting for a clock or worker.
A single MAIN-owned worker later samples time and checks numeric versions that
are updated with the actual remote/connection, known-PID, execution and closure
changes. The ticket retains no Binder, ServiceConnection or client. An already
known AI PID remains only technical metadata; no new healthCheck obtains one.
An absent PID stays null. Unknown/exhausted identity cannot become confirmation.

The execution epoch advances only after a request actually obtains the turn.
A pending observation cannot confirm UNLOADED after another execution starts,
even on the same Binder/connection. A refused request that obtained a turn still
invalidates the older observation; queue expiry and retained replay do not.
Closure and endpoint changes also invalidate pending confirmation. Guard checking
and bounded offer share one short numeric lock, containing no clock/RPC/IO or
consumer callback. They describe one publication point, not future residency.

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
The extra clock runs only on the diagnostic worker, outside the caller, operation,
turn, cleanup timeout/catch and NonCancellable ownership. The caller neither
waits for sampling nor joins the worker on cancel/close. A blocked provider cannot
retain the execution turn or delay the caller's return. A throwing clock yields
unavailable time; a slow worker may conservatively reject a late sample. The
original functional clock/RPC calls remain unchanged. The guarantee concerns
additional diagnostic work, not original synchronous providers or OS scheduling.

The previous synchronous post-cleanup implementation avoided its own timeout/
catch but still held the turn and could change a queued request/outer timeout.
Issue118 fixes that isolation defect; the earlier slow-clock test alone did not
cover this effect.

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
| EXECUTION_CHANGED | A newer actual turn owner started before pending publication |
| DEADLINE_EXPIRED / TIME_REFERENCE_INVALID | Expiry, absent/changed boot, regression or unavailable time |
| STATUS_INVALID | Existing status envelope fails validation, or UNLOADED contains contradictory/extra fields |
| UNLOAD_REQUEST_FAILED / TRANSPORT_FAILURE | Existing unloadModel/getStatus failed; no inferred completion |
| POLL_TIMEOUT / CANCELLED | Existing coroutine timeout/cancellation, separate from boot-deadline arithmetic |
| OBSERVATION_UNAVAILABLE | No stronger supported observation |

UNLOADED_OBSERVED records that the selected private endpoint supplied the earlier
status and passed these conservative checks at publication. The asynchronous
clock is not the RPC timestamp and does not prove the remote still has that
state when sampled. It does not prove physical/native
release, reclaimed PSS/RSS, future residency, complete process accounting or a
model/resource profile. `nativeRuntimeLoaded` is not sampled or forced false:
the JNI wrapper can remain resident after model unload.

## Bounded collection and identity limits

Each immutable observation contains only clientScope, connectionEpoch,
executionEpoch, knownAiPid, deadlineStartedAt, checkedAt and outcome. The start is the original
post-unload-call deadline start, not the beginning of the void call. Null times
or PID are retained as unavailable, never zero. No Binder/Context reference,
request identifier, model ID/path, text, exception or Bundle is retained.

clientScope, connectionEpoch and executionEpoch are local ephemeral technical identifiers. A
scope is allocated by one bounded CAS attempt; contention/exhaustion returns
unknown rather than reusing an identity. Connection epochs advance only for the
opt-in client. These identifiers can repeat after MAIN dies, even within the same
boot, and do not authenticate a process, artifact, physical device or cross-process
incident. They are not durable leases or a provenance signature.

Optional guard construction and numeric-version/stamp allocation are contained
separately from normative execution/bind/release/cancel/close. A diagnostic update
failure permanently disables that owner's publication guard using only a
primitive volatile write, without allocating, acquiring its lock or reading a
provider. Pending/later tickets can publish only OBSERVATION_UNAVAILABLE; updates cannot
revive confirmation. Recording/allocation failure may still drop the record. Failure accounting is itself contained. A constructor failure leaves
the optional owner/dispatcher unavailable. This does not claim that arbitrary
process-wide OOM permits the original functional allocations to succeed.

There is exactly one diagnostic worker and one global in-flight slot, with no
queue or replacement thread. It is initialized only after MAIN role validation,
when an opt-in client is constructed outside a turn. Submission uses one CAS and
wake-up; a busy slot drops the new ticket without calling its Clock. The in-flight
slot remains occupied while its provider is stuck, even after the originating
client closes. New/closed clients cannot create extra workers or retained jobs.
One hung ticket may retain its Clock/technical guard/buffer indefinitely, bounded
globally to one. Other diagnostics remain unavailable/dropped until it returns;
there is no timeout-based replacement or claim that Android can interrupt a hung
provider. MAIN death loses the worker and all ephemeral evidence.

The final owned buffer has capacity 1–64 (default 16), retains the oldest records
and drops new records when full or contended. offer uses tryLock, never waits and
invokes no user code. drain also uses tryLock; a contended drain consumes nothing
and explicitly marks its empty result as contended. Successful drains return a
bounded immutable copy. Consumers cannot hold the client's monitor/turn through
an injected callback or mutate retained records.

droppedAtLeast, contendedAtLeast, samplingBusyDroppedAtLeast and
recordingFailuresAtLeast are cumulative lower bounds. Sampling-busy drops are
also included in droppedAtLeast; they are not fake observations with invented
time/identity. Empty drain while sampling is pending is not completion proof. Counter updates use one bounded CAS; contention/overflow marks
lossAccountingComplete=false rather than inventing exact zero losses. Counter
reads and the drain are not an atomic loss snapshot. A recording fault similarly
marks accounting incomplete; no automatic second publication is attempted.
Consumers must inspect those flags and cannot treat an empty batch as complete
coverage. This buffer grants no inference/admission/qualification authority.

## Isolation validation supplied (issue118)

Two regression-first Android cases exercise a blocked diagnostic clock while a
second request is pending, and a caller with an outer timeout. The fixed caller
returns and the second request succeeds while the provider remains blocked.
The pending first observation is then EXECUTION_CHANGED, and the second sample
is explicitly dropped as busy. Separate Android cases cover actual close/loss
while sampling is pending and queue expiry without phantom unload/epoch advance.
Existing observation cases now await evidence only in test code, never in the
production caller; saturation/replay retain their original Cortex call checks.

Nine new pure JVM cases exercise the actual shared dispatcher/guard: global
capacity including closed clients and a stuck provider, resumption, execution/
endpoint/closure invalidation, unavailable versions, clock failure, functional
failure distinctions, original loop-exit facts, deadline and clock regression.
The permanent-disable case additionally covers blocked pending work and later
non-revival without forcing real OOM. Together with the ten existing classifier/buffer cases these await root's
canonical execution. Author ran no Gradle, Docker, ADB or device commands.

## Original validation boundary (issue110, not issue118 evidence)

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

At author handoff, these JVM/Android tests awaited root's canonical execution;
the author worktree ran no Gradle, Docker, ADB or device command. Source checks confirm unchanged Cortex and
bind/unbind call sites, and git diff --check passes. No software test or DTO from
this package is assigned physical provenance. F4 #64/F5 #89, performance/profile
calibration, heavy producer, actual limiter stimulus and real-duration soak remain
open; no CORTEX_RUNTIME_READY or F6 activation is claimed.

Root execution subsequently passed: ten focused JVM cases, then all 142 app JVM
cases (including those ten), app lint/Debug/Release/test assembly, ordinary R8/native
packaging/test assembly, and all 34 scoped Android cases. The owned emulator was
API35/x86_64/4KiB; fixtures used scripted Binder endpoints and synthetic resource
controls, with no native model. APK hashes were verified before/after. The
[evidence index](f5-unload-observation-evidence.json) binds the exact compiled
source, raw logs and frozen artifacts. No physical or F5 gate is closed.
