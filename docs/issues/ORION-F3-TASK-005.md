# ORION-F3-TASK-005 — Atomic idempotent task creation Skill

Issue: <https://github.com/otavio-esteves/O.R.I.O.N/issues/32>.

## OBJECTIVE
Implement task.create as a deterministic Skill with atomic local execution and idempotent persistence: claim the confirmed action, create/deduplicate the task plus Domain Event, and finish the owned execution in one admitted Room transaction. Architecture §§48–59/102; Master Plan §§8.2–8.5/9.1–9.2 apply.

## FILES ALLOWED
skills/api; new skills/tasks module; data/tasks owned-write adapter and dependencies; MAIN task runner; app build/test dependencies; related tests, module registration and qualification documentation. The host process-death recorder gains a fixed task scenario plus contract tests and README usage. Keep the existing descriptor recovery strategy conservative; clarify its comment only.

## CONTRACTS INVOLVED
OrionSkill/SkillDescriptor; typed validated arguments; confirmed ActionRequest; trusted MAIN policy/authority; execution ID, version and BootSessionId-bound lease; task persistence and transactional Domain Event Outbox; natural task-ID and keyed action dedup.

## INVARIANTS INVOLVED
INV-001/004/005/006/007/008/009/011/014/018/019/021/022/023/031/033/035/036.

## TESTS REQUIRED
No task without a confirmed exact request/current MAIN admission; forged execution IDs/context/LLM metadata cannot write; create plus action success/event/audit commit together; duplicate same request yields one task/event; changed key payload conflicts; existing task-ID collision never overwrites content; ownership/lease/confirmation/capability loss blocks; event/audit insertion failures roll back all changes; reopen and transaction-interruption behavior; static Skill metadata/unsupported intent tests. Docker unit/build/lint/boundary checks and API35 instrumentation.

## ACCEPTANCE CRITERIA
Successful local execution produces one task, one TaskCreated event and one terminal owned action in the same DB transaction. No RUNNING state is durably exposed without its local outcome. Retry after interruption remains idempotent; UNKNOWN never gets generic retry. Execution context IDs alone are not credentials. Main-only ownership and conservative recovery metadata remain unchanged.

## PROCESS DEATH / SECURITY / MIGRATION
Before commit: action remains confirmed and no task/event is committed. After commit: action/task/event are durable together. No external effects, permission grants, plaintext logging, schema or IPC changes. The Skill's bounded synchronous local implementation is shared by the normative suspend entry and the transaction runner without suspension across the blocking admission monitor.

## OUT OF SCOPE
Production ingress/UI activation and task status/query flows (TASK-006), full F4 recovery/scheduling/replay, Cortex/voice, hardware/OEM/24-7 claims and deferred S21 qualification #23.

## VALIDATION
CONTAINER: 22 JVM cases (4 Skill, 18 app), debug/release Skill AARs, app/test APKs, lint and both module-boundary checks passed. All 36 negative boundary assertions passed; Skill/task-storage lint has zero issues and app has ten warnings in unchanged files. HOST recorder contract: four tests passed. API35/x86_64 emulator: 54 final Android cases passed, including 12 new execution cases and the new transaction interruption fixture. The matching-final-APK host --scenario task recorder killed PID 3388 after uncommitted task/event/action-success writes; PID 3442 verified rollback and explicit exactly-once local outcome after replay. This is local SQLite evidence, not distributed exactly-once delivery or physical/OEM qualification. Product ingress/UI remain gated. See [build/source/artifact evidence](../qualification/f3-task-execution-build-summary.json) and [process-death evidence](../qualification/f3-task-execution-transaction-death-api35.json). Required CI must pass on the final PR head.
