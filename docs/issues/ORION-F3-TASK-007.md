# ORION-F3-TASK-007 — Owned task completion and cancellation

Issue: <https://github.com/otavio-esteves/O.R.I.O.N/issues/36>.

## OBJECTIVE
Implement confirmed, owned task completion/cancellation as deterministic local Skills. Reuse the current MAIN UI admission lifecycle, exact action binding, writer admission and atomic status/event/action/audit execution. Architecture §§48–61/102; Master Plan §§9.1–9.2 apply.

## FILES ALLOWED
app task admission owner/status action preparation and runner plus tests; narrow existing create-adapter transaction-composition guards and shared task transaction helper; data/tasks owned status adapter; skills/tasks status Skill plus tests; narrowly required dependency additions; documentation/evidence. Existing task DAO CAS/event transactions and action ownership stores are reused.

## CONTRACTS INVOLVED
CompleteTask/CancelTask validated input and deterministic policy; static descriptors; original key and exact confirmation fingerprint; task expected version; actual RUNNING execution/lease; transactional domain event and terminal action; MAIN-only ownership.

## INVARIANTS INVOLVED
INV-001/004/007/008/009/011/014/018/019/021/022/023/031/033/035/036.

## TESTS REQUIRED
Live channel admission and lifecycle revocation; exact operation/task/version/key/fingerprint binding; complete and cancel generate exactly one corresponding event and one owned terminal action; replays never duplicate or reopen tasks; stale task version/status, wrong operation/key and forged contexts cannot mutate; capability/barrier/confirmation/lease loss; injected event/audit failure rolls back status/action/audit together; reopen persists committed results. Docker unit/build/lint/boundaries and API35 Android tests including existing creation regressions.

## ACCEPTANCE CRITERIA
Only a currently authorized, exactly confirmed request executes. Status mutation plus event plus action success and audit commit together; failures leave the original task/action intact. Same-key same-payload replay is safe; conflicting requests never replace the first. No UI, generic dispatcher or capability activation is introduced.

## PROCESS DEATH / SECURITY / MIGRATION
The local transaction exposes either the prior confirmed action/task state or the committed status/event/success together. No external effects, generic retry, raw logging, schema/IPC/permissions or process change. Existing UNKNOWN/manual-review state is preserved.

## OUT OF SCOPE
Read query/task screen and actual production durable-ingress/runtime wiring (next package), full F4 recovery/scheduling, Cortex/voice and deferred S21 physical qualification #23.

## VALIDATION
CONTAINER: 28 JVM cases, debug/release Skill AARs, app/test APKs, Skill/task-storage lint (zero issues), app lint (ten unchanged warnings), module boundaries and all 36 negative assertions passed in 3m 28s. API35 emulator: 69 Android cases passed, including 12 status cases and three enclosing-transaction/lock-order regressions. The matching-APK host task transaction recorder again passed real SIGKILL/new-PID rollback and explicit replay after the transaction-composition changes. See [build/source/artifact evidence](../qualification/f3-task-status-execution-build-summary.json) and [process-death evidence](../qualification/f3-task-status-execution-transaction-death-api35.json). Product UI/durable-ingress activation remains the following package; required remote CI must pass before merge.
