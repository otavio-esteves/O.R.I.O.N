# ORION-F6-HYBRID-001 — Hybrid chat software vertical slice

OBJECTIVE: Complete Master Plan V1.2 §§12.1–12.7 in software: deterministic
queries, hybrid routing, bounded context, validated structured responses, local
chat/stream/cancel/safe inference retry and explicit human task preparation and
confirmation through the existing Core. User instruction 2026-11-18:
“tenta finalizar f6 (de ponta a ponta ignorando aparelho físico)”.

FILES ALLOWED: core/context/**; core/orchestrator/**; task read descriptor in
core/skills/metadata/**; feature/chat/**; app/chat adapters, existing Cortex owner
read-only configuration selector, MainActivity and related tests; settings and
module dependencies; this issue, ADR-0004 clarification and F6 evidence.

CONTRACTS: OrionIntent, ContextBudgetManager, SkillDescriptor, TaskGateway,
DeviceInformationGateway, existing Cortex runtime/client, ActionRequest and exact
confirmation binding; no new IPC, persistence or execution contract.

INVARIANTS: INV-001–009, INV-011–014, INV-018–019, INV-021–025,
INV-027–028, INV-031, INV-033, INV-035–036. Model output remains untrusted.
No mutable model proposal is submitted until a new explicit human gesture.

TESTS REQUIRED: token-budget priorities and mandatory overflow; strict tool
schema, normalization and privilege injection; routing without Cortex;
proposal/confirmation isolation; streaming, cancellation, terminality and safe
retry; Compose interaction and real native inference in an Android emulator;
Core survival on actual AI death; Docker JVM/check/build/lint and boundary checks.

ACCEPTANCE: every F6 software requirement has implementation and reproducible
evidence; invalid/partial output executes nothing; Fast Paths survive unavailable
Cortex; real-model emulator inference uses existing governor/client and never
changes production profiles; durable task creation still requires Core policy,
startup readiness, presence and exact confirmation.

OUT OF SCOPE: physical S21/API/native qualification, production model/profile
approval, CORTEX_RUNTIME_READY, 24h claims, F7 memory, voice, new action kinds,
automatic repair/retry of model tools, architecture changes, push/merge.

ENVIRONMENTS: Docker for hardware-independent checks and builds; HOST for ADB;
ANDROID_EMULATOR for scoped runtime evidence. Physical gates remain OPEN.
