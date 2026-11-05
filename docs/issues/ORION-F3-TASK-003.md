# ORION-F3-TASK-003 — MAIN-owned explicit task admission

Issue: <https://github.com/otavio-esteves/O.R.I.O.N/issues/29>.

## OBJECTIVE
Implement the MAIN-owned admission source for explicit in-app CreateTask gestures, scoped to the normalized admitted input and the Activity channel that received it. Supply fresh operational barrier, capability and device/presence evidence to existing preparation policy. Master Plan §§9.1–9.2; Architecture §§48–49/58–61/102 apply.

## FILES ALLOWED
app task admission owner and tests; app Gradle dependencies; MAIN runtime construction; MainActivity lifecycle/focus tracking; work package and qualification evidence.

## CONTRACTS INVOLVED
CreateTask/ValidatedCreateTask; CreateTaskPolicyContextSource; StartupBarrier; static task.create descriptor; MAIN-only process bootstrap; actual in-app UI event origin.

## INVARIANTS INVOLVED
INV-009/011/018/021/022/023/031/033/035.

## TESTS REQUIRED
Default/auxiliary/closed/detached/unfocused/paused/locked/unavailable states; exact binding to title/task/key/trace; no trust from producer IDs; channel replacement/close and ABA generation changes; source exceptions/cancellation; bounded pure preparation; redacted diagnostics. Focused Docker unit/build/lint/boundary checks; Android lifecycle validation when the UI callback is wired.

## ACCEPTANCE CRITERIA
Only a trusted channel opened by MAIN and an explicit callback can capture an admission. A source is scoped to one exact validated input; subsequent reads are fresh and lifecycle revocation invalidates it. Admission alone grants no policy approval or execution. The operational barrier stays closed and task-write capability unavailable until subsequent recovery/Skill packages exist.

## PROCESS DEATH / SECURITY / MIGRATION
Admissions are ephemeral and cannot survive as authority. No public Intent/IPC payload carries admission flags or executes commands. No DB/schema/permissions or exported components change. No raw title/hash/exception logging.

## OUT OF SCOPE
Action persistence and real confirmation (TASK-004), task execution/idempotency (TASK-005), task screen/integration (TASK-006), F4 scheduling/recovery, Cortex/voice, deferred S21 qualification #23.

## OBSERVATION LIMITS
Lifecycle changes invalidate prior admissions even after pause/resume or focus ABA. Each source is bound to one captured normalized request. Device/barrier/capability observations are fresh but cannot detect unsampled OS ABA or grant execution authority. Real confirmation and execution must revalidate their current conditions. No public source/origin/presence flags are accepted.

## VALIDATION
CONTAINER: 18 JVM tests (14 new admission cases), debug app/test APKs, app lint, module boundaries and 36 negative assertions passed. HOST/ANDROID_EMULATOR API35: all five FoundationNavigationTest/FoundationRuntimeTest cases passed, covering Activity recreation, auxiliary ownership, component surface and backup exclusion. This is not hardware qualification or proof of an operational task command callback; that UI wiring remains TASK-006. See [source/artifact evidence](../qualification/f3-task-admission-build-summary.json). Required remote CI must pass on the final PR head.
