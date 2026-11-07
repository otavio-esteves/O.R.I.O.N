# ORION-F4-PREP-001 — Scheduler and durable recovery preparation

Tracking: [issue #61](https://github.com/otavio-esteves/O.R.I.O.N/issues/61).

OBJECTIVE: Deliver independently testable F4 preparation: auditable civil-time
recurrence resolution, explicit exact/approximate alarm selection, a reference-only
idempotent event consumer, and regression coverage of paginated action recovery.
This package does not activate the F4 operational graph or advance a phase gate.

FILES ALLOWED: scheduler/**; data/events/**; the dedicated event-publication
Android tests and ActionExecutionOwnershipTest under app/src/androidTest/**;
this work package, README and a corresponding qualification report.

CONTRACTS INVOLVED: Architecture sections 38–44 (civil recurrence, DST, exact-alarm
capability/fallback), Domain Event Outbox/consumer receipts, Action Outbox execution
ownership and BootSessionId. Master Plan sections 2 and 10 govern phase gates.

INVARIANTS INVOLVED: INV-005/006/007/008/011/014/015/016/018/019/023/035/036.

TESTS REQUIRED: DST gap resolves to first valid local instant; overlap preserves
prior valid offset or selects earlier offset; next occurrence advances strictly;
invalid or unbounded recurrence inputs fail explicitly; exact selection requires
user preference, a precise user-facing event and fresh capability; denied/unknown
capability selects observable approximate delivery; event projection/receipt commit
atomically, duplicate/reopen/redelivery do not duplicate projection; action recovery
paginates beyond active leases and preserves UNKNOWN without invocation.

ACCEPTANCE CRITERIA: Narrow JVM tests, module-boundary checks, relevant build/lint
and instrumentation APK compilation pass in canonical Docker. Android tests execute
only if a host-connected target is available; otherwise remain explicitly unrun.
No runtime activation or phase-ready claim; documentation identifies remaining gates.

OUT OF SCOPE: Schema migrations, AlarmManager/WorkManager registration, receivers,
automatic reminder delivery, runtime event dispatch, new execution authority, AI,
voice, physical qualification, release/merge and F4/MVP readiness declarations.

PROCESS-DEATH / UPGRADE: Pure scheduler calculations own no durable state. Event
projection and consumer receipt share an existing Room transaction, so an interrupted
commit rolls back both and redelivery deduplicates committed work. Existing schema
and production startup/recovery remain unchanged; new recovery coverage exercises
persisted boot-bound leases and terminal uncertainty.

SECURITY: Scheduler decisions carry no user payload. The event consumer must project
only allowlisted technical metadata; no raw payload, external side effect, permission
or exported component is added.
