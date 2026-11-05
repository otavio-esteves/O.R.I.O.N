# ORION-F3-TIMER-001 — Owned deterministic timer runtime

GitHub issue: <https://github.com/otavio-esteves/O.R.I.O.N/issues/51>.

OBJECTIVE: Implement CreateTimer, CancelTimer and read-only GetTimerStatus/list through the existing MAIN ingress, policy, exact confirmation, ActionRequest and owned local Skill pipeline. Add a timer screen. No alarm or notification delivery is promised before F4.

FILES ALLOWED: New timer namespaces in core commands/queries/policy/orchestrator/metadata, data/timers, skills/timers, feature/timers, app/timers and related tests; narrow Room v4 entity/DAO/migration/access/empty-storage detection; module registration/dependencies; this work package. FoundationRuntime/MainActivity wiring belongs to primary integration.

CONTRACTS INVOLVED: StartupBarrier, encrypted durable ingress, exact ActionConfirmationSnapshot, ActionRequest ownership/BootSessionId lease, SkillDescriptor, DomainEventOutbox, encrypted field envelopes, OrionClock and explicit 3→4 migration.

INVARIANTS INVOLVED: INV-001/004/005/006/007/008/011/014/018/019/021/022/023/031/033/035/036. No authority, persistence or process topology change is intended.

TESTS REQUIRED: Validation/canonical bounds; duration/overflow; same-boot monotonic and reboot wall fallback, clock rollback/forward; policy unavailable/mismatch/freshness; timer ownership, confirmation payload/expiry, transaction rollback, replay conflict and read-only queries; v3→4 preservation and upgrade metadata; UI port flow and process-death instrumentation.

ACCEPTANCE CRITERIA: Timer create/cancel effects occur only after exact confirmation inside one admitted ingress/action/domain/event/audit writer transaction. Title is encrypted. Status queries never mutate. Persisted deadlines survive reboot with an explicit degraded clock basis. Migration preserves prior encrypted records. Canonical Docker tests/build/lint/boundary checks required before closure; hardware qualification remains deferred.

OUT OF SCOPE: F4 schedulers, alarms, notifications, autonomous expiry effects, external side effects, recurrence, AI, voice, topology/security authority changes and physical S21 qualification.

PROCESS DEATH: Before transaction commit all action/timer/event/ingress changes roll back. Committed work retains a replay-safe result. Uncertain/abandoned actions retain conservative recovery; no generic UNKNOWN retry. No timer can become an external effect during startup.

SECURITY / MIGRATION: Timer content uses versioned encryption and technical reference-only events. Room v4 adds a timer table, indices and accessor, with an explicit additive v3→4 migration; no destructive fallback. Monotonic timestamps are interpreted only with matching BootSessionId. Creation uses bounded positive millisecond durations and overflow-safe deadline arithmetic. The UI explains that timers have no background alert until scheduler qualification.

INTEGRATION STATUS: Room v4 and MAIN/UI wiring are integrated. Initial canonical
Docker checks passed 245 JVM cases (69 timer cases), zero failures, timer UI/storage
lint, module boundaries and debug/test packaging in 6m37s. Initial focused Android
run passed backend/migration/recovery/navigation but two UI tests failed because
the test host Activity was not declared. Test-host and foreground polling fixes
are being integrated; no final test completion or readiness is claimed here.

Actual installed update versionCode2/schema3 to versionCode3/schema4 passed on
API35/x86_64 emulator, preserving encrypted records, keys, pending and UNKNOWN
state without replay. [Recorded artifact/component identities](../qualification/f3-timer-installed-upgrade-api35.json).
This is NON_DEFINITIVE Android evidence and does not close physical/F3/F4 gates.

UI/LIFECYCLE HARDENING: Compose tests use the existing MainActivity test host,
with no test-manifest/exported component added. Periodic status reads run only
while the selected LifecycleOwner is STARTED; stop cancels the polling child and
resume preserves loaded rows/cursor/draft. Polls do not drain ingress. A registry
regression checks frozen read counts while CREATED, resumed reads while STARTED,
draft preservation and unchanged explicit queue-drain/list counts. The direct
lifecycle-runtime-compose dependency is pinned at 2.9.4, already selected transitively
by the existing Activity Compose 1.13.0; this adds explicit API use, not a baseline
version upgrade. Runtime execution belongs to the integrating primary agent.
