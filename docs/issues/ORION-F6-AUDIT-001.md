# ORION-F6-AUDIT-001 — Close the three F6 audit findings

OBJECTIVE: Preserve user cancellation when notification preparation is blocked,
keep Cortex connection negotiation off MAIN's UI thread, and render selected chat
history chronologically. User authorization: “beleza, segue. e planeja F7 quando
terminar as correções”. Branch: `fix/f6-audit-followup`, base `171bbda`.

FILES ALLOWED: app notification ingress priority and related Android tests;
app/ai/OrionCortexClient and related connection tests; core/orchestrator chat
context and related JVM tests; this issue and scoped validation documentation.
After fixes/validation, a separate F7 planning document and its README link.

CONTRACTS INVOLVED: Architecture §§23–31, 48.1, 51.1, 62–77; accepted ADRs
0001–0004; existing durable ingress priority DESC / receivedAt ASC, exact
confirmation, StartupBarrier, private versioned Cortex Binder and Context Budget.

INVARIANTS INVOLVED: INV-001, INV-006, INV-009, INV-011–014, INV-018–019,
INV-021–023, INV-027–028, INV-033, INV-035–036.

TESTS REQUIRED: permission/capability denial followed by exact dismissal without
an external invocation; gated/unfocused dismissal and UNKNOWN protection; slow
handshake dispatched from Android MAIN with a responsive UI, cancellation/timeout,
close and late replies; recent correction order and newest-history selection under
budget. Run red regressions first, focused Docker JVM/build checks, Android emulator
regressions, then canonical Docker check/lint/dependencies/Debug/Release/native
packaging and relevant release-sensitive checks.

ACCEPTANCE CRITERIA: a blocked posting request cannot starve a valid dismissal;
dismissal still validates presence, barrier, exact action/version and legal state;
Binder negotiation never occupies the UI callback, bounded pending work cannot
revive cancelled/closed connections; chat keeps recent-history selection and full
token budgeting while rendering selected turns oldest to newest. Preserve all
existing safety tests and record exact results and execution environments.

OUT OF SCOPE: F7 implementation; new IPC/AIDL/capabilities/processes, schema or
migrations, new Skill authority, production inference profiles, physical/24h gates,
voice, release publication or merging. The approved follow-up workflow uses a
focused draft PR against the existing `feat/f6-hybrid-chat` branch; the F7 plan
gets a separate branch/review. F7 planning follows Master Plan §13 and does not
declare MEMORY_ENGINE_READY or extend ADR-0004 implicitly.
