# ORION-F3-TIMER-001 — Owned deterministic timer runtime

OBJECTIVE: Implement CreateTimer, CancelTimer and read-only GetTimerStatus/list through the existing MAIN ingress, policy, exact confirmation, ActionRequest and owned local Skill pipeline. Add a timer screen. No alarm or notification delivery is promised before F4.

FILES ALLOWED: New timer namespaces in core commands/queries/policy/orchestrator/metadata, data/timers, skills/timers, feature/timers, app/timers and related tests; narrow Room v4 entity/DAO/migration/access/empty-storage detection; module registration/dependencies; this work package. FoundationRuntime/MainActivity wiring belongs to primary integration.

CONTRACTS INVOLVED: StartupBarrier, encrypted durable ingress, exact ActionConfirmationSnapshot, ActionRequest ownership/BootSessionId lease, SkillDescriptor, DomainEventOutbox, encrypted field envelopes, OrionClock and explicit 3→4 migration.

INVARIANTS INVOLVED: INV-001/004/005/006/007/008/011/014/018/019/021/022/023/031/033/035/036. No authority, persistence or process topology change is intended.

TESTS REQUIRED: Validation/canonical bounds; duration/overflow; same-boot monotonic and reboot wall fallback, clock rollback/forward; policy unavailable/mismatch/freshness; timer ownership, confirmation payload/expiry, transaction rollback, replay conflict and read-only queries; v3→4 preservation and upgrade metadata; UI port flow and process-death instrumentation.

ACCEPTANCE CRITERIA: Timer create/cancel effects occur only after exact confirmation inside one admitted ingress/action/domain/event/audit writer transaction. Title is encrypted. Status queries never mutate. Persisted deadlines survive reboot with an explicit degraded clock basis. Migration preserves prior encrypted records. Canonical Docker tests/build/lint/boundary checks required before closure; hardware qualification remains deferred.

OUT OF SCOPE: F4 schedulers, alarms, notifications, autonomous expiry effects, external side effects, recurrence, AI, voice, topology/security authority changes and physical S21 qualification.

PROCESS DEATH: Before transaction commit all action/timer/event/ingress changes roll back. Committed work retains a replay-safe result. Uncertain/abandoned actions retain conservative recovery; no generic UNKNOWN retry. No timer can become an external effect during startup.

SECURITY / MIGRATION: Timer content uses versioned encryption and technical reference-only events. Room v4 adds a timer table, indices and accessor, with an explicit additive v3→4 migration; no destructive fallback. Monotonic timestamps are interpreted only with matching BootSessionId. Creation uses bounded positive millisecond durations and overflow-safe deadline arithmetic. The UI explains that timers have no background alert until scheduler qualification.
