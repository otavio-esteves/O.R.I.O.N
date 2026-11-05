# ORION-F3-REMINDER-002 — Deterministic reminder runtime

GitHub issue: <https://github.com/otavio-esteves/O.R.I.O.N/issues/49>.

OBJECTIVE: Connect create/cancel/snooze and bounded read-only reminder queries to a MAIN-only durable ingress/action/Skill pipeline and native screen. Reminders remain UNSCHEDULED in F3; no delivery is promised.
FILES ALLOWED: reminder namespaces in commands/policy/metadata/queries/orchestrator/data/skills/features/app and related tests; narrow existing DAO additions and module dependency wiring. Foundation/MainActivity integration is performed by the primary agent.
CONTRACTS INVOLVED: existing CreateReminder, ActionRequest/execution lease/confirmation, StartupBarrier, durable ingress, optimistic versions, domain event transaction, SkillDescriptor, bounded queries and encryption.
INVARIANTS INVOLVED: INV-001/004/005/006/007/008/011/014/015/018/019/021/022/023/031/033/035/036.
TESTS REQUIRED: validation/canonical material; policy source binding/revocation; durable gated enqueue; exact confirmation and original expiry; create/cancel/snooze atomicity and replay conflicts; bounded read queries; lifecycle recreation; transaction rollback and process-death proof; Docker assembly/unit/lint/module boundaries, host instrumentation where available.
ACCEPTANCE CRITERIA: Actual native UI effects traverse authenticated MAIN admission, durable encrypted ingress, deterministic policy, exact confirmation and owned local Skill after the shared recovery barrier. Same key plus changed payload is rejected; no query mutates state; no stale consent renewal. Event/state/action/audit/ingress commit together. Runtime remains degraded until F4 qualification.
OUT OF SCOPE: OS alarms/WorkManager/notifications, recurring schedules, system-local-time resolution, F4 reconciliation, AI/voice, physical S21 qualification. No schema change for reminders: snooze updates existing PENDING scheduledAt with CAS and emits REMINDER_SNOOZED; timer schema belongs to its separate package.

Process death: precommit mutations roll back; committed local results are verified before replay. UNKNOWN/manual actions receive no automatic retry. Payloads remain encrypted/minimized and excluded from diagnostics. No process/IPC/Android permission or authority boundary changes. Existing schema/keys are retained.

VALIDATION: Canonical Docker checks passed in 9m29s (1023 tasks): 166 JVM cases,
zero failures/errors/skips, reminder UI/storage lint, module boundaries and
debug/test APK assembly. Host/API35 x86_64 emulator passed all 24 focused Android
cases, including historical snooze replay, exact time display and MAIN navigation.
The five host-recorder contract cases passed. Actual RUN_AS_SIGKILL during the
owned reminder transaction proved rollback in a different PID and explicit replay
without duplicate effects/events. Independent cross-review findings were fixed.

Source/APK hashes and test counts: [build/runtime evidence](../qualification/f3-reminder-runtime-evidence.json).
Actual kill report: [process death](../qualification/f3-reminder-runtime-transaction-death-api35.json).
Runtime evidence remains NON_DEFINITIVE; physical #23 and full F3/F4 gates remain
open. Required CI must pass before merge.
