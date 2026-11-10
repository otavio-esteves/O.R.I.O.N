# ORION-F5-TRANSPORT-001 — Private Cortex Binder transport

OBJECTIVE: Implement the architecture's versioned internal Cortex AIDL and bounded
schema-1 transport, using read-only file descriptors for model/prompt content.

FILES ALLOWED: ai/api transport, ai/service, app/ai client and dedicated tests;
module registration/dependencies and private service manifest.

CONTRACTS: Architecture §§22–32; Master Plan §§11.2–11.4, 11.7–11.8.
INVARIANTS: INV-002/009/012/013/027/028/033; no data or business authority in :ai.

TESTS REQUIRED: Protocol/schema/encoded-size rejection; read-only descriptor
validation; stream terminality; callback/Binder death; cancellation/timeout; bounded
queue/request history; no blind inference replay on rebind.

ACCEPTANCE: Private same-UID service, no Room/repositories/Skills; one heavy inference
at a time; fresh MAIN resource approval before load/inference; one main terminal;
large content never inline; module and Debug/Release checks pass.

OUT OF SCOPE: F6 routing/tool execution, physical qualification or readiness claims.

## Consolidated validation

Canonical checks, scoped emulator results and retained failed attempts are recorded
in [F5 Runtime Gate](../qualification/F5-RuntimeGate.md) and its
[evidence record](../qualification/f5-runtime-evidence.json). That report supersedes
implementation-time validation-pending notes without changing this work package's
scope. Final review corrections/reruns, physical qualification and production
resource profiles retain the explicit status recorded there; no per-issue test
count or phase closure is inferred.
