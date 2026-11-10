# ADR-0002: Bounded Cortex history without lifetime admission exhaustion

- Status: Accepted — explicitly approved by the user on 2026-11-09
- Date: 2026-11-09
- Scope: Cortex ephemeral request identity, duplicate admission and status retention
- Supersedes: Only the implementation-specific lifetime 64-identity caps in
  ORION-F5-QUEUE-001 and ORION-F5-SERVICE-001
- Baseline preserved: Architecture V3.2 §§23–30; INV-009, INV-012, INV-013,
  INV-019, INV-027, INV-028, INV-033, INV-036

## Problem

The MAIN ledger never retires terminal identities. The service also retains both
load and inference identities forever. Its 64 entries therefore exhaust after 32
successful load/infer/unload cycles; unloading does not restore admission.
Increasing the cap only postpones failure. Discarding arbitrary IDs would allow an
old request to execute again. Restarting processes to clear counters is unnecessary.

## Decision

Use issued, ordered identities with bounded result retention and a retirement
watermark. Each history owns a random scope and monotonically increasing sequence.
Only an identity issued by that history can be admitted. Keep live requests and a
bounded window of recent terminal results. Retiring a terminal entry advances a
compact watermark; an identity below that watermark is always a duplicate and can
never execute again, even after its full result has been discarded.

An active request is never evicted. Capacity continues to limit simultaneous live
work; it no longer limits the number of completed requests during a process lifetime.
Sequence exhaustion fails closed. A changed boot or process death cannot revive an
old scope. No prompt, output, receipt or identity is persisted by this mechanism.

MAIN issues logical request identities before queueing and keeps terminality in
its existing request lifecycle. A retired replay is reported as a duplicate whose
historical result is unavailable, rather than fabricating a new terminal result.
The service issues transport identities, separately from logical MAIN identities,
so priority ordering and explicit rebind cannot reuse a transport identity.

The private Cortex protocol negotiates a minor-version capability for issued IDs
and bounded result retention. Legacy protocol-1.0 clients retain the existing
fail-closed contract. New clients require the capability before using the new
path. No major-version mismatch is accepted and no mutable Skill authority is added.

The implementation uses Cortex 1.1 with capability
`orion_issued_request_history_v1`. The new `issueRequestId()` method is appended
after the original AIDL methods, preserving their transaction IDs. It increments
the service's sequence without retaining a reservation object. An issued identity
is admitted only when load, inference or cancel records it. MAIN similarly exposes
`newRequestId()` on its technical owner/client; callers retain that identity and
its exact normalized request through retries. Arbitrary or foreign logical IDs
are rejected before dispatch. Service transport IDs are mapped back to that exact
logical ID only inside the matching callback closure.

The initial `getProtocolInfo()` reply retains exactly the protocol-1.0 envelope:
`schemaVersion`, `status`, `major`, and `minor`. Capabilities are returned by a
separate `getCapabilities()` method appended after `issueRequestId()` (Binder
transaction 10). All original transaction numbers, including issued-ID transaction
9, remain unchanged. A new MAIN client first checks the major/minor, then queries
and validates capabilities before issuing an identity or starting heavy work.
Minor 0, major mismatch, missing capability, unavailable extension, or malformed
reply fails closed. A legacy client accepts the initial reply using its original
strict envelope and never needs to call either extension. This wire clarification
implements the legacy compatibility required above; identity, retention, and
authority semantics are unchanged.

Each history retains at most 64 entries. When full, it retires the oldest terminal
entry and any terminal entries at or below its watermark, preserving every live
entry, including live holes below that watermark. Thus a slow older request cannot
block an otherwise terminal history from making room. Delayed, never-submitted
reservations below the watermark are retired too. Existing live/retained entries
are checked before the watermark, so duplicates of live work still join it.

Service queries/submissions for a retired identity return `DUPLICATE_REQUEST`;
MAIN uses a typed retired-request result without starting another lifecycle.
Missing capability makes the new MAIN client unavailable before requesting an ID
or loading a model. Legacy direct requests keep a separate bounded 64-entry
fail-closed history and never receive eviction/reexecution semantics implicitly.

Cancellation before start retires/cancels the issued reservation. Cancellation of a
terminal or retired request is a no-op. Late callbacks remain bound to the exact
request and Binder epoch. Rebind never retries a previous inference: only an
explicit new logical request may obtain a new transport identity, after fresh
restart and resource approval.

## Validation required

- Thousands of completed requests retain bounded memory and continue admitting.
- Concurrent duplicates share one result; changed payloads cannot replace live or
  retained requests; retired and foreign-scope identities never execute.
- Active/queued ownership survives retirement pressure; capacity, cancellation,
  timeout, invalid time, sequence exhaustion and close fail safely.
- Real Binder load/inference/unload runs exceed the old 32-cycle limit and retain
  one terminal result per logical request; stale callbacks/rebind do not replay.
- Canonical unit, lint, dependency, Debug/Release, R8 and native packaging checks.

## Unchanged boundaries

Room schema, persistence, Action Outbox, confirmation, Startup Barrier, process
topology, resource/restart authority, production-profile qualification and physical
gates are unchanged. This is not authorization to enable production inference.
