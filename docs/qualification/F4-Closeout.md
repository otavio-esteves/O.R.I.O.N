# F4 closeout audit — pending long-run approval

Work package: [ORION-F4-QUAL-002](../issues/ORION-F4-QUAL-002.md); parent
[issue #64](https://github.com/otavio-esteves/O.R.I.O.N/issues/64).
This is an evidence audit, not an F4/MVP approval or release tag.
The [machine-readable closeout record](f4-closeout-s21-api35-20261107.json)
binds each result and retained failed attempt to its artifact/source identities.

The runtime fixes and passive observer passed required CI and were merged through
[PR #66](https://github.com/otavio-esteves/O.R.I.O.N/pull/66) and
[PR #67](https://github.com/otavio-esteves/O.R.I.O.N/pull/67). The follow-up changes
are instrumentation and host qualification tooling; production runtime, schema,
authority boundaries and the installed main APK remain unchanged.

## Master Plan §10.11

| Criterion | Current scoped evidence |
| --- | --- |
| Tasks persist | S21 full Android suite; atomic task/event SQLite and process-death cases |
| Reminders persist | S21 persistence/generation/finite-series cases and real notifications |
| Timers work | S21 monotonic/boot fallback tests; a real elapsed timer is included in the active short workload |
| Reboot recovers | S21 completed boot-count increase, autonomous Core and actual notification before instrumentation |
| Process death recovers | Seven actual S21 SIGKILL scenarios; another controlled death is scheduled in the short workload |
| Domain Event Outbox recovers | S21 receipt/rollback/duplicate/backoff/dead-letter tests and maintenance regression |
| Action Outbox recovers | S21 claim/finish/ownership and actual committed-claim death cases |
| UNKNOWN never blind-retries | S21 ambiguity/manual-review/retention/upgrade cases and actual claim-death evidence |
| IngressQueue recovers | S21 durable encrypted ingress, receipts, dedup and actual transaction death |
| Startup Barrier works | S21 real shared barrier/recovery/admission and denied invocation cases |
| Exact alarm fallback | Real S21 approximate registration; access-loss APPROXIMATE and access-gain EXACT with unchanged confirmation |
| Time/timezone reconcile | Both S21 real notification scenarios passed with original instant/binding retained |
| Installed upgrade preserves state | Recorded physical installed 4→5 replacement; original ciphertext/keys/queues and PENDING/UNKNOWN retained |
| Health observable | S21 native diagnostics; actual background/notification/channel degradation and restored capability |
| Integral deterministic use without AI | S21 native task/reminder/timer/memory/device/notification workflows; Cortex/voice explicitly unavailable |

The detailed source/APK identities and retained failures are in the
[initial physical report](f4-s21-physical-api35-20261107.json) and
[runtime-fix follow-up](f4-s21-fixes-physical-api35-20261107.json). The new physical
matrix adds notification denial/grant, exact access loss/gain, battery saver,
real disposable SQLite SQLITE_FULL rollback and a bounded heap/write load. A full
318-case Android rerun passed without failures or skips on the identical main APK.

Channel denial used visually verified official Android Settings controls because
Samsung's UiAutomator returned a null accessibility root. The automatic attempt
refused to tap and remains recorded as failed. Actual Android assertions then
verified channel importance 3→0→3, denial before invocation and restoration of the
original app permission. Raw screenshots contain only the owned Settings page and
are retained with the local evidence, not embedded in repository reports.

## Long-run and compatibility obligations

The current physical window is only a few hours. A controlled one-hour workload is
running with a synthetic task, elapsed timer, six original approved reminders,
planned main-process SIGKILL, scoped endpoint queue/uncertainty checks and passive
memory/battery/storage/system snapshots. It is explicitly a short run. It cannot
substitute for 24 real hours.

The workload harness is a measurement component. Its report keeps both
`fullSoakGateClosed` and `fullSoakProfileComplete` false. A complete long-run decision
also needs the declared boot/unlock checkpoints, actual heartbeat progress,
continuous backlog/uncertainty observations and review of crashes/ANRs, thermal,
battery, retention and growth. Endpoint instrumentation restarts MAIN and is marked
as such; it is never presented as passive autonomous progress.

Architecture V3.2 §119 explicitly includes an emulator in the Android compatibility
target. API 37 and 16 KiB emulator work therefore supplies compatibility evidence
only. It never becomes S21/OEM/arm64 performance or physical 16 KiB qualification.
The S21 API35/arm64/4KiB evidence remains distinct. The API37 image is being prepared;
no unexecuted runtime result is approved here.

F4 remains open until required qualification evidence, the real 24-hour soak and
its review are complete. No AI/voice phase advancement, readiness promotion or
`v0.1.0-core` tag is authorized by this audit alone.

The native compatibility gate also remains `PENDING`. Passing static alignment and
packaging checks or a debug x86_64 emulator load does not approve physical arm64/
16 KiB runtime or release/R8 native loading. The API compatibility matrix and this
native gate retain separate decisions.
