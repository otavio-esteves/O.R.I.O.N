# F4 preparation — scheduling policies and durable consumers

This is a preparation candidate, not a declaration of F4 or resilient MVP readiness.
The work contract is [ORION-F4-PREP-001](../issues/ORION-F4-PREP-001.md).
Master Plan §2 makes phase exit gates mandatory. The outstanding physical S21
qualification recorded in [F3](F3-ImplementationGate.md) remains open. Production
readiness remains `DEGRADED_SAFE`.

## Delivered components

- `OrionCivilRecurrence` resolves individual civil occurrences with auditable requested
  and resolved local times, zone, offset, instant and resolution decision. Gaps use
  the first valid local instant; overlaps preserve a valid previous occurrence
  offset, otherwise the earlier offset. Search is bounded and returns explicit
  exhaustion or rule-ended outcomes. `RecurrenceRule` is an internal domain strategy,
  not a user-provided callback or a persisted recurrence format. Daily and weekly
  progressions in tests do not establish a product recurrence catalogue.
- `OrionAlarmStrategy` selects exact alarms only for precise user-facing reminders
  with user opt-in and a fresh positive capability observation. Otherwise it returns
  approximate precision and a diagnostic reason. This is a policy component; it
  neither calls Android nor grants action authority. Future adapters must revalidate
  immediately before registration and persist selected precision/method.
- `OrionTechnicalEventConsumer` publishes known version-1 domain facts to a technical
  projection using the existing transactionally committed consumer receipt. It reads
  no business content and performs no external action. Committed projection followed
  by interrupted delivery acknowledgement is safe to redeliver. Registration in
  production and ongoing dispatch remain pending.
- Action recovery gains a pagination regression proving that an active first page
  does not hide abandoned executions and that existing `UNKNOWN` records, ciphertext
  and audit remain unchanged without a new execution attempt.

There are no schema migrations, application manifest changes, new Android permissions,
receivers, workers, runtime registrations or changes to production recovery order.
Timers still have no automatic background alerts and reminders remain `UNSCHEDULED`.
The new scheduler code does not own a clock or consult raw platform time.

## Validation

[Recorded evidence](f4-preparation-evidence.json) identifies commands, source-file
hashes, APK hashes and test cases. All scoped checks passed:

- CONTAINER: 30 Debug JVM cases (28 new) across scheduler and data/events, with
  zero failures; scheduler/data-events Debug lint; module boundary validation and
  its 36 negative/positive fixtures; scheduler/data-events Release libraries;
  application Debug/Release APKs and Android instrumentation APK compilation.
- ANDROID_EMULATOR: 22 cases (4 new) on API35/x86_64, zero failures. This includes
  actual SQLite rollback when receipt insertion fails, dedup after database reopen
  with missing delivery acknowledgement, and paginated execution recovery.
- Final scheduler revalidation includes the rollback/gap collision correction:
  next occurrence must exceed both the clock cutoff and the previous actual instant.
  A typed prior occurrence preserves that historical instant across zone changes.

Container and emulator results are non-definitive for hardware/runtime qualification
under ADR-0001. SQLite close/reopen or an injected exception is not actual process
death. Physical tests, full repository `check`, required remote CI and F4 system/soak
qualification were not executed in this package. Runtime test output and final
build output are retained locally under `/tmp/orion-f4-preparation-android.txt` and
`/tmp/orion-f4-preparation-final-build.log`; these temporary logs are not durable
repository artifacts.

## Remaining delivery sequence

Each row is a separate engineering work package requiring its own bounded issue,
implementation, validation and protected-branch review. Physical gate closure must
precede operational phase advancement.

| Package | Concrete acceptance |
| --- | --- |
| Physical F2/F3 gate | S21 persistence, encrypted storage, installed upgrade and actual process-death scenarios pass with artifact/source identity recorded |
| Reminder scheduling persistence | Explicit migration adds baseline civil recurrence/resolution, schedule method, request code, generation and audit metadata; upgrade preserves existing reminders and uncertainty |
| MAIN Android adapters | Exact special access uses optional `SCHEDULE_EXACT_ALARM`, explicit immutable PendingIntents and identifier-only extras; revoked/unknown access yields persisted approximate fallback |
| Reconciliation and delivery ingress | Startup, boot/unlock, time/zone changes, upgrade, capability changes and allowed return after Force Stop rebuild scheduling from Room; stale generations and duplicate callbacks cannot duplicate actions |
| Durable event publication | Register validated consumers, recover before Action Outbox, bound dispatch/retry and maintenance, expose backlog/dead letters; crash boundaries preserve receipt semantics |
| Operational recovery and maintenance | Wire all normative recovery stages, unique deferrable workers, permission/capability and background-restriction diagnostics; mutable effects remain gated |
| F4 qualification | Actual death/reboot, Doze, restriction, alarm revocation, time/zone changes, installed upgrade and soak evidence pass on appropriate physical/API targets; only then evaluate MVP/tag readiness |

No AlarmManager callback may post directly outside durable ingress and the authorized
ActionRequest pipeline. WorkManager remains a deferrable maintenance mechanism,
never the source of truth for exact civil delivery. An abandoned execution never
proves absence of an external effect; `UNKNOWN` receives no generic automatic retry.
