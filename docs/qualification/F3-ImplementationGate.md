# F3 — Deterministic implementation and qualification

The integrated candidate covers the functional implementation scope of Master
Plan §9 and Architecture §128. Validation is in progress; a phase-ready claim is
not inferred from implementation or emulator results.

## Functional scope

| Area | Implemented behavior | Boundary |
| --- | --- | --- |
| Tasks | Create/list/complete/cancel with exact confirmation | Existing owned transactional pipeline preserved |
| Reminders | Create/list/cancel/snooze with exact instant/zone and CAS | `UNSCHEDULED`; no OS delivery promise |
| Timers | Create/cancel/status, durable duration and foreground countdown | Original-boot monotonic clock; explicit wall fallback after reboot; no background alerts |
| Time/battery/device | Typed deterministic queries and native refresh | MAIN Android adapters, unknown values explicit, no mutation |
| Personal memory | Explicit user-declared create/list/forget | Fixed `PERSONAL`/`USER_DECLARED`; no inference promotion, FTS or F7 ranking |
| Notifications | Explicit confirmed local post, permission/channel diagnostics | Claim durable before Android; no Room/barrier monitor around external work; proof or uncertainty |
| Native control | Home shortcuts, Tasks/Reminders/Timers/Memories/Notifications/Device/Activity/Diagnostics/Settings | No chat or AI dependency |

Every mutable Skill has a static descriptor and remains behind deterministic
validation, policy, exact confirmation, current capability/presence checks and
owned execution. Per-domain consumers share one Room owner and one real Startup
Barrier without consuming or rejecting another domain's encrypted queued input.
Domain events are committed facts retained in the Event Outbox; publication is F4.

## Persistence, recovery and privacy

Room v4 adds only `orion_timers`; historical schemas 1/2/3 remain preserved and
explicit migrations are registered through 4. Key provisioning checks include
timer data. No destructive migration, process topology, IPC or database ownership
change was introduced. All auxiliary processes remain without the MAIN graph.

Memory forgetting deletes the current domain/claim rows and logically
erases authenticated content copies in terminal processing envelopes. Pending
drafts are safely cancelled before erasure; unreadable or uncertain copies block
the operation and roll it back. Minimal preserved tombstones and same-writer
enqueue guards prevent resurrection, including a fresh ingress key. This does
not claim forensic erasure of SQLite/WAL/flash blocks.

Notification preparation/confirmation may drain during recovery, but no recovery
or queue callback posts externally. Android execution begins after a committed
`RUNNING` claim and outside both Room and the Startup Barrier monitor. A bound
permit checks original consent, actual ownership, policy, capabilities, permission,
presence and clocks immediately before invocation. Only matching active
registration permits success and its reference-only event. Ambiguous live results
become `UNKNOWN`; abandoned executions follow `MANUAL_REVIEW`, without blind retry.

## Evidence status

The final canonical build, full Android suites, installed upgrade and process-death
reports are being captured against frozen source and matching APK hashes.

Earlier independently verified increments remain historical evidence:

- [Scoped ingress](f3-ingress-scopes-evidence.json): Docker checks and 10 API35 ingress cases.
- [Device/activity](f3-device-activity-evidence.json): 23 JVM and 8 API35 cases.
- [Reminder runtime](f3-reminder-runtime-evidence.json): 166 focused JVM and 24 API35 cases.
- [Actual reminder SIGKILL](f3-reminder-runtime-transaction-death-api35.json): different-PID rollback and explicit replay.
- [Installed schema upgrade](f3-timer-installed-upgrade-api35.json): versionCode 2→3/schema 3→4, original records/keys/uncertainty preserved.

Evidence identifies `CONTAINER`, `HOST` or `ANDROID_EMULATOR`. API35/x86_64 runtime
evidence is `NON_DEFINITIVE` under ADR-0001; it cannot establish S21/OEM performance,
battery behavior, hardware Keystore guarantees or continuous-operation resilience.

## Gate remaining open

The physical S21 qualification of the transaction core remains explicitly deferred
in [issue #23](https://github.com/otavio-esteves/O.R.I.O.N/issues/23). Its F2 entry/gate
implications and definitive F3 process-death qualification are not waived by this
implementation. `DETERMINISTIC_AGENT_READY` is not declared.

F4 remains separate: AlarmManager/WorkManager delivery, exact-alarm capability,
civil recurrence/DST reconciliation, durable event publication and full operational
recovery. Core readiness remains `DEGRADED_SAFE`; no resilient MVP/24-7 claim, AI,
voice, wake word or System Assistant qualification is introduced.
