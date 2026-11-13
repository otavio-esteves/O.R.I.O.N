# ORION-F5-AUDIT-001 — Cortex client connection lifecycle regressions

OBJECTIVE: Correct the two audited client races: a connection callback reaching a
closed executor, and old disconnection cleanup terminating newly admitted work.

FILES ALLOWED: OrionCortexClient in ai/cortex; focused ai/service instrumentation
tests; this work package and related validation evidence only.

CONTRACTS: Architecture V3.2 §§27–31 (streaming, terminality, cancellation and AI
death); existing Cortex IPC v1; ADR-0001 validation environment boundary.

INVARIANTS: INV-012/013/028. MAIN remains useful after optional inference failure.
Each request terminates once. Rebinding requires a new explicit guarded request.

TESTS REQUIRED: Reproduce both bugs before correction; late connection callbacks
after close; new admission from health/terminal callbacks and during unbind;
terminal callback counts and old callback deduplication; close during admission;
existing real Binder/death/rebind tests; canonical JVM/lint/module/build checks;
minified JNI and static native packaging checks.

ACCEPTANCE: Connection callbacks cannot leak executor rejection to MAIN. Cleanup
only terminates requests detached from that connection and cannot overwrite a
newer connection's health/model state. Pending resources are released once and
terminal callbacks remain single. Relevant checks pass with exact environments
and retained negative evidence.

OUT OF SCOPE: IPC semantics or process ownership changes; Room/schema/migrations;
Skill or Action Outbox execution; automatic inference/rebind; production profile
activation; physical qualification, 24-hour soak, release promotion or merge.

RECOVERY/SECURITY: Only ephemeral client state changes. No prompt/payload logging,
new exported surface, persisted authority or changes to durable recovery. Emulator
results remain NON_DEFINITIVE for physical/runtime qualification.
