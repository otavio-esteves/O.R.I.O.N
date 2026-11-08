# F4 closeout — functional exit and open qualification

Work package: [ORION-F4-QUAL-002](../issues/ORION-F4-QUAL-002.md); parent
[issue #64](https://github.com/otavio-esteves/O.R.I.O.N/issues/64).
Latest decision: [functional exit audit](F4-FunctionalAudit.md) approves the
§10.11 deterministic exit and governor-first F5 work. Stability/native qualification
remains open; Cortex activation and a release tag are not approved. The historical
measurements below retain their original scope and outcomes.
The [machine-readable closeout record](f4-closeout-s21-api35-20261107.json)
binds each result and retained failed attempt to its artifact/source identities.

The runtime fixes and passive observer passed required CI and were merged through
[PR #66](https://github.com/otavio-esteves/O.R.I.O.N/pull/66) and
[PR #67](https://github.com/otavio-esteves/O.R.I.O.N/pull/67). The follow-up changes
are instrumentation, test-only dependencies and host qualification tooling;
production runtime sources, schema and authority boundaries remain unchanged.
Repackaging after the Espresso dependency declaration produced a different main
APK container hash, with every uncompressed package entry byte-identical to the
earlier APK and all native data offsets still 16KiB aligned. The new artifact pair
has separate regression evidence; whole-APK hash equivalence is never claimed.

## Master Plan §10.11

| Criterion | Current scoped evidence |
| --- | --- |
| Tasks persist | S21 full Android suite; atomic task/event SQLite and process-death cases |
| Reminders persist | S21 persistence/generation/finite-series cases and real notifications |
| Timers work | S21 monotonic/boot fallback tests and an elapsed real timer with unchanged ciphertext after the completed 10-minute workload |
| Reboot recovers | S21 completed boot-count increase, autonomous Core and actual notification before instrumentation |
| Process death recovers | Seven earlier actual S21 SIGKILL scenarios plus controlled death and autonomous notification from a new MAIN PID during the completed short workload |
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

The current physical window is only a few hours. A controlled one-hour workload
prepared a synthetic task, timer and six original approved reminders. Wireless ADB
stopped responding around the halfway checkpoint: three deliveries and 30 passive
samples were retained, but no additional controlled death/return or endpoint queue/
uncertainty verification was proved. The attempt remains `INCOMPLETE`. After
reconnection, all six owned reminder fixtures and the task/timer fixture were
cleaned and original policies restored. Cleanup verification is not qualification.
Neither the intended hour nor 24 real hours is claimed.

A subsequent 10-minute workload delivered both originally approved reminders,
including autonomous delivery from a new process after actual SIGKILL. Its endpoint
binding/task/timer checks passed, but instrumentation raced the observer's final
PID/memory read: one unavailable memory sample correctly kept that run incomplete.
A regression reproduced the ordering fault, then the recorder was changed to await
passive completion before endpoint instrumentation. All 98 host contracts passed.
The unchanged strict observer and corrected recorder then completed a fresh real
600-second S21 workload: both reminders delivered once, original hashes/fingerprints
and task/timer ciphertext retained, uncertainty digest unchanged, actual death/
autonomous return proved, and 11 samples without identity, metric or cadence gaps.
Scoped cleanup and restoration, including the original 60-second screen timeout,
passed. The report is `SHORT_RUN_COMPLETE_REVIEW_REQUIRED`, never a 24-hour approval.

The workload harness is a measurement component. Its report keeps both
`fullSoakGateClosed` and `fullSoakProfileComplete` false. A complete long-run decision
also needs the declared boot/unlock checkpoints, actual heartbeat progress,
continuous backlog/uncertainty observations and review of crashes/ANRs, thermal,
battery, retention and growth. Endpoint instrumentation restarts MAIN and is marked
as such; it is never presented as passive autonomous progress.
Reminder fixture cleanup precedes the final task/timer checkpoint, so endpoint
queue totals cannot establish retention/pruning or uninterrupted backlog history.

Architecture V3.2 §119 explicitly includes an emulator in the Android compatibility
target. API 37 and 16 KiB emulator work therefore supplies compatibility evidence
only. It never becomes S21/OEM/arm64 performance or physical 16 KiB qualification.
The S21 API35/arm64/4KiB evidence remains distinct. The identical main APK passed
318 cases without skips on an official API31/x86_64/4KiB image. Scoped debug native
loading passed on S21 arm64/4KiB and official API37/x86_64/16KiB; the latter also
passed the two real SQLite/resource cases. A cold full-runner startup failure on
the Google 16KiB image is retained; later platform logs showed CPU saturation and
Google-component ANRs, which do not establish the original failure's exact cause.

On the official API37 AOSP ATD/4KiB image, 301 of 319 cases passed and 18 UI cases
exposed the same transitive Espresso3.5.0 reflection failure:
`InputManager.getInstance()` no longer exists. Only the test dependency is pinned
to stable Espresso3.7.0, whose [official release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0)
document the system-service lookup fix. Original failures remain recorded and no
assertion or platform security setting was relaxed. That repair passed 317 cases;
the two remaining UI searches raced the asynchronous pending-list reload after
Activity recreation. The tests now wait at most 10 seconds for the same exact
fixture-scoped selector before clicking once. Disabled/duplicate controls and
permanently missing requests still fail; no request is re-prepared or automatically
confirmed. Both focused UI regressions and the full 319-case manifest then passed
on physical S21/API35 and official API37 AOSP ATD/4KiB, without failures or skips.
All temporary S21 policies and the original 60-second screen timeout were restored.

The ATD system-matrix preflight returned an unknown background restriction level
and stopped before fixture preparation. It is retained as a failed preflight,
not lifecycle evidence. All six scoped system cases then passed on ordinary
official API37 AOSP/4KiB instances: Force Stop/explicit allowed return, reboot,
time/timezone change, deep Doze and foreground return while background-restricted.
Each observed the real notification before verification and retained the original
binding with one committed delivery; temporary policies were restored. These are
independent cases, not a continuous combined workload. After the first instance's
reboot, its next-case preflight again found an unknown shell restriction level and
stopped before mutation. The remaining four cases used a fresh independent
instance with an observed original policy; no restriction was normalized to create
that baseline. Both failed preflights remain retained. ATD app-surface results
never stand in for ordinary-platform events or physical/OEM behavior.

The wider stability qualification remains open until the required real-duration
workload and review are complete. The blanket F5 prohibition previously derived
from that obligation is superseded by the scoped functional audit above, which
uses Master Plan §§10.11/31. No inference, readiness promotion or release tag is
authorized by this historical evidence alone.

The native compatibility gate also remains `PENDING`. A separate-identity,
non-debuggable R8 qualification APK now passed scoped native loading on physical
S21/arm64/4KiB and startup/storage checks on API35/API37 x86_64/4KiB emulators.
See [minified qualification](F4-MinifiedNative.md) for exact artifact identities,
retained incomplete attempts. [Recreation qualification](F4-UiRecreation.md) records
the two-import test-environment correction and the complete 325-case API37 pass.
This does not approve physical arm64/16KiB or production
release distribution. The API compatibility matrix and native gate retain
separate decisions. The real-duration, complete physical workload and review gates
remain open; short results never promote Core readiness or enable Cortex.
Governor-first F5 implementation follows the separate functional exit decision.
