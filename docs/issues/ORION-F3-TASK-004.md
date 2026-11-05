# ORION-F3-TASK-004 — Exact task action preparation and confirmation

Issue: <https://github.com/otavio-esteves/O.R.I.O.N/issues/30>.

## OBJECTIVE
Prepare and persist an exact task.create ActionRequest from a trusted MAIN admission and deterministic policy. Accept human confirmation only for the exact displayed request, with fresh admission/policy under writer admission. Master Plan §§8.4–8.5/9.1–9.2 and Architecture §§48/50–51/58–61/102 apply.

## FILES ALLOWED
app task-action preparation/confirmation adapter and related instrumentation tests; required app dependencies; work package and qualification evidence. Existing action persistence contracts are reused.

## CONTRACTS INVOLVED
ValidatedCreateTask; MAIN admission; policy NeedsConfirmation; static descriptor; ActionRequest, idempotency and exact confirmation binding; StartupBarrier; atomic Room state/audit.

## INVARIANTS INVOLVED
INV-004/009/011/018/019/021/022/023/031/035.

## TESTS REQUIRED
Encrypted draft plus NEEDS_CONFIRMATION/audit atomicity; normalized replay returns original action; changed payload/key scope conflict; no task is created by preparation/confirmation; stale preview/version/fingerprint/policy rejected; source/lifecycle/capability revocation; fresh exact confirmation persists; rollback on audit failure; guarded writer admission. Focused Docker build/lint/boundaries plus API35 instrumentation.

## ACCEPTANCE CRITERIA
Action IDs come from Core, never producer task IDs. Policy/version/descriptor are trusted code. One operation atomically registers or reuses the original request and waits for confirmation. A displayed preview cannot confirm materially different content or policy. Confirmation never proves a gesture by itself and never executes a Skill.

## PROCESS DEATH / SECURITY / MIGRATION
Interruption leaves no partial preparation/audit. Persisted pending requests survive death; ephemeral admissions do not. Fields remain encrypted, previews/diagnostics are redacted, no schema/IPC/permissions change. Production ingress and UI wiring remain gated until TASK-006.

## OUT OF SCOPE
Task execution and replay semantics (TASK-005), task query/status/UI integration (TASK-006), scheduler/full F4 recovery, Cortex/voice and deferred S21 qualification #23.

## VALIDATION
CONTAINER: focused app unit/build/lint and both module-boundary checks passed in 8m 8s (1044 tasks); all 18 app JVM tests and 36 boundary negative assertions passed. HOST/ANDROID_EMULATOR API35: 41 cases passed (11 new preparation/confirmation, 12 encrypted action-store, 18 execution ownership). Ten lint warnings remain in unchanged files. No real UI confirmation or task execution is activated by this adapter. See [source/artifact evidence](../qualification/f3-task-action-preparation-build-summary.json). Required remote CI must pass on the final PR head.
