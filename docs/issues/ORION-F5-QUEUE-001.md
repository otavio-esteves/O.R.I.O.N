# ORION-F5-QUEUE-001 — Bounded priority inference admission

OBJECTIVE: Serialize MAIN Cortex sessions using Architecture §30 priority order
instead of a FIFO mutex, without adding authority or permitting concurrent loading.

FILES ALLOWED: app/ai/OrionInferenceTurnstile.kt, OrionCortexClient.kt, focused
request-ledger helper, related JVM/Android tests and this issue.

CONTRACTS INVOLVED: Cortex concurrency (one heavy inference), OrionInferencePriority,
OrionClock/BootSessionId, cancellable ephemeral admission. The parent integrates
this helper around the existing session boundary; the service retains BUSY guards.

INVARIANTS INVOLVED: INV-009, INV-013, INV-027, INV-028, INV-033.

TESTS REQUIRED: priority then FIFO selection, eight-waiter capacity, cancellation
while waiting and after handoff, active cancellation, exception cleanup, close,
invalid boot/time references, and absence of overlapping admitted blocks.

ACCEPTANCE CRITERIA: one active session, at most eight waiting sessions; INTERACTIVE
then USER_COMMAND, REMINDER, PROACTIVE, BACKGROUND, with increasing admission
sequence within each priority. Cancellation never leaks a granted slot. Close
rejects future requests and drains waiters; parent client cancels the active session.

OUT OF SCOPE: durable ingress, request-ID deduplication, physical qualification,
resource-policy changes, native preemption, inference execution and runtime wiring.

## Semantics

Admission sequence implements FIFO among simultaneous/equal-time requests without
depending on mutable wall time. Every waiter retains an injected monotonic mark;
a missing boot identity, boot change or elapsed-clock regression prevents its block
from starting. A queued request also observes its own injected monotonic deadline
without waiting for the active session to finish. No permission is cached by a turn.

The priority queue is ephemeral and nonpreemptive: an interactive request waits for
the current native session to finish/cancel, then precedes lower-priority waiters.
The existing client owns the one shared instance and must not recursively acquire
it within a session. The 64-entry request-identity cap remains an independent
contract and includes active, queued and terminal identities.

A waiter's suspension is inside its cleanup region, so cancellation before a granted
continuation runs still releases ownership. Completion happens outside the gate's
monitor, allowing undispatched/unconfined resumptions without running user work
under the monitor. Close never launches, kills or restarts a process. Process death
discards this queue; no waiting prompt or durable ActionRequest is persisted here.

Validation pending parent-orchestrated canonical Docker checks. JVM fixtures are
not Android runtime or physical qualification evidence.

## Final lifecycle audit amendment

OBJECTIVE: A queued request expires independently of active native work, immediately
returns its terminal result, and releases queue capacity without Binder/resource
calls. Preserve canonical request-ID deduplication across active/queued/terminal
requests using a bounded synchronized ledger; duplicate waiters never start another
operation or replace the first result. Cover expiry before/after grant, reboot and
rollback, cancellation and identity conflict. Keep existing priority/FIFO behavior.

INVARIANTS: INV-012, INV-013, INV-019, INV-027, INV-028, INV-036. No new authority,
persistence or native retry is introduced. The ledger remains ephemeral and capped
at 64 identities, matching the previous terminal history cap.

The same client lifecycle review closes a SharedMemory allocation/close race: the
cleanup region must include monitor installation, and temporary UTF-8 bytes are
cleared even when allocation/closed-client checks fail. The request deadline stays
unchanged; no deadline is extended to compensate for queueing.

Focused additions: three injected-clock queue cases, six request-ledger JVM cases,
and `OrionCortexQueuedDeadlineTest` using the real MAIN client with a synthetic
pre-bind policy hold. That Android case advances an injected clock, verifies a
queued timeout/replay before active release, rejects changed-payload identity reuse,
and proves zero Binder/resource/output calls for the expired request. It needs no
GGUF or inference and cannot qualify actual Android/native performance.

Validation of this amendment pending parent-serialized checks.
