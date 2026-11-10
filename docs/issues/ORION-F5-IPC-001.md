# ORION-F5-IPC-001 — Bounded inference request lifecycle

OBJECTIVE: Implement the main-side inference DTO and lifecycle contract before the
Binder adapter, preserving Architecture §§26–31 and Master Plan §§11.3, 11.7–11.8.

FILES ALLOWED: `ai/api/src/**`, `ai/api/build.gradle.kts`, this issue.

CONTRACTS INVOLVED: IPC v1.0, schema v1, request identity, bounded streaming order,
exactly one logical terminal state, idempotent cancellation, boot-bound timeout.

INVARIANTS INVOLVED: INV-009, INV-011, INV-012, INV-013, INV-019, INV-036.

TESTS REQUIRED: schema/identity checks; in-order and bounded out-of-order streaming;
duplicate and conflicting buffered chunks; gap/count/content-size rejection;
terminal races and late callbacks; cancellation before/loading/during/after work;
timeout at boundary, clock rollback/forward, reboot and missing boot identity;
process death and mismatched request identity.

ACCEPTANCE CRITERIA: One request reducer emits deltas once and in sequence; buffers
have count, sequence-gap and content-byte limits; cancellation/timeout/death cannot
change an existing terminal result. Content is absent from default diagnostics.

OUT OF SCOPE: Binder/AIDL/Parcelable encoding, measured Parcel size guard, large
payload transport, service/registry/JNI, inference execution/authorization, global
request queue/dedup registry, persistence, physical or phase qualification.

## Integration notes

These are in-memory API values, not a claim of a stable serialized wire encoding.
The transport must validate the complete encoded Parcel against the existing
256 KiB policy, negotiate v1.0 and enforce caller identity before constructing
values. The reducer additionally bounds decoded content and its reorder buffer.
The owner must bound its request map, deduplicate request admission, and deliver
the returned chunks to a bounded consumer. No generated text authorizes a Skill.

Cancellation immediately terminates the main-side logical request. Stopping native
work remains best effort; the runtime must receive cancellation, abort load when
safe and escalate an unresponsive worker separately. Terminal callbacks cannot
revive this reducer. No state survives main-process death and no partial output is
reconstructed into an external action.

Validation is consolidated in the F5 Runtime Gate linked below.

## Consolidated validation

Canonical checks, scoped emulator results and retained failed attempts are recorded
in [F5 Runtime Gate](../qualification/F5-RuntimeGate.md) and its
[evidence record](../qualification/f5-runtime-evidence.json). That report supersedes
implementation-time validation-pending notes without changing this work package's
scope. Final review corrections/reruns, physical qualification and production
resource profiles retain the explicit status recorded there; no per-issue test
count or phase closure is inferred.
