# ORION-F5-SERVICE-001 — Private Cortex Binder runtime

OBJECTIVE: Implement private :ai service over protocol 1.0 with bounded asynchronous
load/inference, native streaming, cancellation, timeout, unload and request status.

FILES ALLOWED: ai/service only and this issue.

CONTRACTS INVOLVED: existing Cortex AIDL/Wire schema, JNI wrapper, AndroidOrionClock;
Architecture Cortex IPC/lifecycle/native ownership and Master Plan §11.

INVARIANTS INVOLVED: INV-002, INV-009, INV-012, INV-013, INV-027, INV-028, INV-033.

TESTS REQUIRED: incremental UTF-8 across token boundaries, malformed/truncated or
oversized token rejection, bounded output; canonical compile/lint/native package;
parent integration tests for same-UID private Binder, load/hash/FD guards, stream,
cancel/timeout/unload/death. Runtime/physical qualification remains separate.

ACCEPTANCE CRITERIA: one active worker, 64 retained statuses, bounded encoded
Bundles, read-only regular model descriptors, bounded read-only SharedMemory prompt
snapshots, full hash before native load, boot-bound
deadlines, no late logical callbacks after terminality, no auto-restart or business
authority. MAIN owns fresh governor/restart decisions before each load/inference.

OUT OF SCOPE: MAIN client/policy wiring, model registry/schema, real-model profile
qualification, voice/Skills, physical gate closure and publishing.

## Wire behavior

Load is asynchronous: reply ACCEPTED followed by status polling. `getRequestStatus`
returns `status` plus `state`; request terminal states are SUCCEEDED, CANCELLED,
TIMED_OUT or FAILED. Load requires schemaVersion, requestId, modelId, sha256,
sizeBytes, contextTokens, threads, bootCount, startedElapsed and durationMillis.
Inference requires schemaVersion, requestId, modelId, inputSize, maxTokens,
temperature and the same deadline fields. Durations are at most 120,000ms. Model
files are at most 4GiB; prompts obey the shared wire/native 1MiB limit. Model identity
must match the loaded model. Protocol reply uses major/minor. Chunks use delta and
zero-based long sequence, with exactly one final finishReason.

Cancellation before admission retains a bounded CANCELLED tombstone. At 64
admissions, new distinct requests fail closed with CAPACITY_REACHED; existing IDs
retain their original status and never execute again. Unknown-ID cancellation at
capacity throws a content-free capacity failure. The MAIN owner must generate
fresh request IDs and retain its own logical terminal state. The active native slot
remains occupied until best-effort cancellation actually returns. Death of a callback
owner cancels its inference. Service destruction requests cancellation and lets the
owned worker perform final native/descriptor cleanup; no process relaunch occurs.

Validation is consolidated in the F5 Runtime Gate linked below.

## Consolidated validation

Canonical checks, scoped emulator results and retained failed attempts are recorded
in [F5 Runtime Gate](../qualification/F5-RuntimeGate.md) and its
[evidence record](../qualification/f5-runtime-evidence.json). That report supersedes
implementation-time validation-pending notes without changing this work package's
scope. Final review corrections/reruns, physical qualification and production
resource profiles retain the explicit status recorded there; no per-issue test
count or phase closure is inferred.
