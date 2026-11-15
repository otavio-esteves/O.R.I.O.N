# One optional observed MAIN owner invocation

[Work package](../issues/ORION-F5-OWNER-CYCLE-001.md), issue #123. This connects
the existing owner to its real internal resource/stream/result/unload sources
and the optional #119 metrics reader. It adds no driver, exporter, execution loop,
stress, trusted profile or physical result. The production factory is unchanged:
plans stay empty, and the current production path refuses NO_QUALIFIED_PROFILE.

The optional final `OrionCortexCycleObserver` is supplied only by internal
composition to an existing `OrionCortexRuntimeOwner`. No JSON/CLI/caller field can
construct a qualified plan or enable admission. The default owner has no observer;
its original `run` uses the same execution implementation with no capture.

## Explicit execution and separate manual phase

An internal opt-in consumer obtains an opaque token with
`owner.beginCycleObservation()`, calls
`owner.runObserved(token, modelId, request, context, threads, originalCallback)`
once, and retains the original result or catches the original thrown object.
Only afterwards may it explicitly call `owner.readCycleObservation(token)`.
These are separate calls: reading/assembling the receipt is not part of the
original run, cleanup or caller-return path. No callback wrapper is installed.

The observer token contains no request/model/content fields. Early refusal
records the actual Unavailable result and NO_ATTEMPT only; no fake logical terminal,
native state, stream or resource decision is manufactured. Trusted model SHA256
is copied only after the original READY/profile binding succeeds. The logical
request ID stays private and is recorded only inside the admitted original
`runOnce` operation. It is never echoed by a receipt or used as its lookup key.
Native metrics may contain their separately validated service-issued transport ID.

The owner and client reuse their existing request ledger and turnstile. An
existing owner result is RETAINED_RESULT. A client dedup race that joins retained
work is also retained, without another turn/resource/stream/unload observation.
A private optional call-local marker records entry into the actual `runOnce`
lambda; missing buffered ownership events cannot be reinterpreted as replay.
Logical ownership before a queued expiry is distinct from actual turn entry.
Neither state proves native entry, model load or completion.

## Exact callback association

The token travels as a local argument with its coroutine into the actual client
turn. It is not an owner-wide last-attempt pointer. The client creates a final
synchronous scope only around each original restart/resource callback: exact
token, opaque client scope, callback kind, LOAD/INFERENCE/BEFORE_LOAD phase and
current Thread. The owner checks that scope and its own token scope before
offering the already-collected snapshot/already-computed decision. Scopes end in
finally, including original exceptions; another coroutine/thread/phase does not
inherit them. Buffer order and thread coincidence alone establish nothing.

Resource projection reuses the final bounded #117 projector. No extra resource
observation, governor/restart evaluation, health call, clock read, PID guess or
policy fence is created. Up to twelve resource facts are retained per token.
Snapshot timestamps and missing fields retain their original meanings.

Stream fields are lower-bound counts of accepted chunk observations, original
MAIN `onChunks` call attempts and its normal returns. The same function receives
the same original List. Original Error/cancellation objects and callback ordering
are preserved. No text, piece, bytes, lengths, tokens, output hash or exception
description is copied. These counts are distinct from native callback metrics,
delivery/presentation or tokenizer counts.

## Unload and retained metrics remain separate facts

The client copies facts from its existing unload request/status polling and the
existing deadline/pre-RPC poll references. There is no added Clock/RPC, poll,
worker or wait in run/cleanup. This collector does not activate the #118 diagnostic
clock worker. An UNLOADED reply is recorded as that reply, without inventing
post-reply timing validity, model release or native slot/finality. Its receipt
therefore stays INCOMPLETE, even alongside a logical SUCCEEDED result.

Callback scopes and endpoint views use volatile technical references, without
adding a wait on the client monitor in the functional path. The immutable numeric
view is invalidated before endpoint/pin replacement and republished only inside
places already protected by the original monitor. Allocation failure leaves it
unavailable. The manual phase additionally checks actual client/turn/epoch/PID
fields under monitor before and after optional IPC, with no monitor across IPC.

A completed call permits one explicit manual metrics phase per token. It uses
the already-owned client and the internally validated logical mapping; it never
calls acquire/bind, refreshes resource evidence, reads a clock, resets cancellation
or retries. #119 may perform its one lazy capability discovery before the one
query. Overlapping reads return pending data rather than duplicate queries.
Missing/lost/superseded/dead/closed identity yields unavailable native metrics.
Unknown source time stays unknown. Native API-wall durations are not throughput,
full backend-compute completion or 24-hour evidence.

The first assembled result is an immutable retained snapshot. Subsequent reads
return that snapshot, not a new current-state assertion. `snapshotState=FINAL`,
slot release or JNI return are copied only from an available #119 snapshot; they
are never inferred from a logical result or unload reply.

## Bounds, failure and evidence limits

The final observer holds 1–16 technical entries (default four), using `tryLock`.
Full/contended storage drops observation, never work. Optional allocations,
projection/publication and manual reads contain diagnostic Throwable locally;
original resource/inference/callback exceptions remain outside that containment.
Missing turn/acceptance markers after loss stay nullable rather than false proof.
All receipts are retainedSnapshot / NOT_QUALIFIED and keep profile, production
inference, F5 and soak approval flags false.

`droppedAtLeast`, `recordingFailuresAtLeast` and `lossAccountingComplete` are
cumulative observer-wide lower-bound accounting, not per-token counts or an
atomic snapshot of concurrent counters. LOSS marks the particular token. The
stream counters likewise remain lower bounds after dropped events.

If the final returned/threw record loses the lock, a receipt can stay PENDING
with LOSS permanently. PENDING means the completion record is unavailable, not
that inference is still active or that another read will eventually complete.
There is no automatic polling/repair/resume. Such a pending slot is not evicted
as completed. Explicit release removes only completed technical observations,
never app/model state or resources. Concurrent explicit release may make an
already-running manual read unavailable; it cannot attach that reply to a new
token. Lost evidence never grants retry authority.

Provided tests include pure mechanical buffer/source controls; real owner's
early NoProfile/Core-error cases with closed DB/rejecting inventory/bind; and
scripted Client execution/dedup/queue/cancellation/manual-reader controls. The
last fixture uses a test-only private-client assignment to exercise the actual
owner reader with an existing scripted client. It does not pass owner heavy
admission or provide native/model/physical evidence. No production reflection,
factory hook, profile injection or qualification component is added.

The author ran no Gradle/Docker/ADB. Canonical compilation, runtime tests and CI
belong to the root integration. Hardware/calibration, an operational driver,
memory stimulus and the actual full-duration #89/#64 program remain separate.
No result declares CORTEX_RUNTIME_READY or opens F6.
