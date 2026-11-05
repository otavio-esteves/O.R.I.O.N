# ORION-F3-TASK-006 — Complete/cancel task contracts and policy

Issue: <https://github.com/otavio-esteves/O.R.I.O.N/issues/34>.

## OBJECTIVE
Define deterministic CompleteTask/CancelTask contracts, exact canonical status-change input, static Skill metadata and a fresh MAIN admission policy before adding their mutation adapters and UI. Architecture §§9/48/58–61/102 and Master Plan §§9.1–9.2 govern the package.

## FILES ALLOWED
core/commands task status contracts/preparer and unit tests; core/skills/metadata task status descriptors/tests; core/policy task status binding/context/policy/tests; related documentation only.

## CONTRACTS INVOLVED
Command result semantics; task ID and expected version; UUID key/trace; immutable validated canonical input; exact admission binding; static descriptor; trusted MAIN context and operational StartupBarrier; later exact ActionRequest confirmation.

## INVARIANTS INVOLVED
INV-004/009/011/018/019/021/022/023/031/035.

## TESTS REQUIRED
Canonical payload/target vectors; version bounds; replay IDs outside material payload; operation cannot select authorization/recovery; exact task/version/operation/key/trace binding; absent/untrusted/auxiliary/gated/presence/capability evidence; positive policy still requires confirmation; state/generation changes and cancellation; safe diagnostics; Docker unit/build/lint/boundaries.

## ACCEPTANCE CRITERIA
Complete/cancel are bounded typed requests, not effects. Their payload is task ID plus expected version, with operation bound through a fixed static Skill name. MAIN policy needs fresh matching observations and exact admitted input; no request supplies permission/presence/policy/confirmation flags. No mutation or task capability activation occurs in this package.

## PROCESS DEATH / SECURITY / MIGRATION
Pure recomputable contracts/policy; no persistence/schema/IPC/permissions/logging change or resumed effects. Mandatory confirmation and current execution-time policy checks remain separate.

## OUT OF SCOPE
Status persistence/execution, list query and task screen/runtime wiring (separate following package), full F4 recovery/scheduling, AI/voice, deferred S21 physical qualification #23.

## VALIDATION
CONTAINER: 68 JVM cases passed, including 20 new contract/descriptor/policy cases. Debug/release AARs, all three module lint checks (zero issues), module boundaries and 36 negative assertions passed in 1m 58s (295 tasks). No Android runtime or hardware qualification is inferred for this pure package. See [source/artifact evidence](../qualification/f3-task-status-contracts-build-summary.json). Production status mutation remains gated and required CI must pass before merge.
