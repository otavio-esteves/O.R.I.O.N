# F4 — Deterministic scheduler and recovery candidate

This report tracks the runtime candidate for
[ORION-F4-RUNTIME-001](../issues/ORION-F4-RUNTIME-001.md). It does not declare the
physical/system gate closed. The user explicitly continued development with physical
qualification pending; no emulator result is promoted to S21/OEM/24-7 evidence.
The remaining physical/system/soak work is tracked in
[issue #64](https://github.com/otavio-esteves/O.R.I.O.N/issues/64), with the prior
transaction-core physical gate still tracked in issue #23.

## Implemented boundaries

- Room schema 5 explicitly migrates schema 4 scheduling metadata and finite manifest
  tables. Existing reminders remain without external delivery authority.
- A separate native opt-in confirms an exact future generic alert. Finite daily and
  weekly series contain 2–31 independently prepared and confirmed occurrences, with
  a 365-day horizon; the UI initially offers seven daily or four weekly alerts.
  Every exact date is disclosed before the original batch gesture. No future receipt
  is manufactured, no parent receipt is copied, and confirmed payloads never mutate.
- Each civil occurrence is resolved separately. Requested/resolved local time, zone,
  offset and GAP/OVERLAP decisions are persisted and checked against the exact finite
  manifest. Next binding and current success/FIRED/event commit atomically after
  platform proof. Uncertainty pauses the series; cancel/edit/snooze revokes waiting
  children and preserves ambiguous execution/audit.
- MAIN AlarmManager adapters use explicit immutable, generation-specific identifier
  PendingIntents. Selected precision is persisted before registration, followed by
  a separate successful-registration observation; failure stays visibly awaiting.
  Exact access is optional `SCHEDULE_EXACT_ALARM`, with explicit approximate fallback.
- Alarm callbacks enter encrypted reference-only durable ingress. A consumed receipt
  acknowledges a received fact; it grants no invocation authority. The external runner
  revalidates exact consent, policy, capabilities, permission, generation and boot-bound
  execution lease immediately before Android invocation, outside Room/barrier monitors.
- Domain Event Outbox publication uses a reference-only technical consumer, atomic
  receipts, bounded retry and dead letters. Maintenance scans use persisted bounded
  cursors; expired unstarted delivery proposals become terminal without retrying UNKNOWN.
- WorkManager supplies unique deferrable heartbeat/reconciliation only. MAIN initializes
  its technical scheduler synchronously after role/unlocked checks to support cold
  JobService startup; actual reconciliation/enqueue remains a Core recovery stage.
  Auxiliary processes never initialize WorkManager or the main graph. Automatic
  initializer and optional exported diagnostics receiver are removed; the framework
  JobService remains protected by BIND_JOB_SERVICE.
- Recovery reconciles outbox/actions/reminders/workers before releasing the Startup
  Barrier. Boot/unlock, time/zone changes, app update, exact capability changes and
  permitted return after Force Stop are triggers. Force Stop evidence uses the
  public current-start ApplicationStartInfo completion callback, including API35
  builds whose historical records report pid=0. Incomplete negative evidence is
  unavailable; positive evidence stays pending until persistence succeeds. An exit
  reason or an arbitrary historical positive record is never proof.
- Diagnostics expose capability/fallback, worker registration, pending/uncertain work,
  dead letters, background restriction, sanitized previous exits and Force Stop evidence.
  Resource policy keeps inference disabled; no microphone/AI/voice startup is added.

## Validation record

Canonical Docker validation passed with 4,108 tasks: 630 Debug JVM cases across 89
suites, lint, module boundaries and ELF/ABI/APK/AAB/16 KiB packaging gates. The exact
command was:

```bash
docker compose run --rm android-build check assembleDebug assembleRelease :app:assembleDebugAndroidTest :core:security:assembleDebugAndroidTest --max-workers=2
```

The 35 HOST recorder contracts passed. A dedicated API35/x86_64 emulator ran all
313 application Android cases and four Keystore cases successfully on the final
APK pair. A full-suite attempt encountered a System UI ANR dialog that owned focus
and blocked two native UI prerequisites. After the attempt ended, selecting the
normal Android “Close app” button recovered System UI; the entire unchanged
313-case suite then passed. No assertion was relaxed.

All six real NotificationManager scenarios passed: deep Doze, Force Stop followed
by explicit allowed return and fresh persisted primary startup evidence, reboot,
time change, timezone change and foreground return under background restriction.
The host observed each actual notification before starting verification
instrumentation. Doze was observed at notification time; temporary test settings
were restored. Restriction remains degradation and does not qualify continuous
background operation.

Actual run-as SIGKILL after committed reminder-delivery RUNNING ownership passed:
verification ran in a different process, retained the original binding/ciphertext,
recorded manual review and never resent the synthetic external marker. That marker
is separate from the real NotificationManager evidence above.

Installed 4→5 replacement passed on a separate clean instance of the same emulator
image, preserving ciphertext, original keys, durable queues and PENDING/UNKNOWN
without replay or data clearing. Artifact verification caught an earlier collection
starting before the final main APK finished packaging. That result is retained;
the final replacement ran after the completed build and matches every other final
Android proof's app/test hashes.

[Final evidence](f4-runtime-evidence.json) records the 85-file changed-source freeze,
artifact identities, environment, preserved failed attempts and remediation. All
final Android proofs use app `df86c4e4…` and test `b0149ed2…`; the report stores their
full SHA-256 values. Physical S21 qualification remains open; the disposable emulator
uses temporary RAM-backed userdata and provides NON_DEFINITIVE runtime evidence.

## F4 gate checklist

| Master Plan §10.11 criterion | Required evidence |
| --- | --- |
| Tasks/reminders persist; timers work | Regression suite and installed upgrade |
| Reboot and process death recover | Actual lifecycle recorder and SIGKILL; physical qualification still required |
| Event/Action Outbox recover; UNKNOWN never blind-retries | SQLite/runner/ownership tests and actual claim-death recorder |
| Durable ingress and Startup Barrier | Gated callback, original receipt replay and bootstrap regressions |
| Exact alarm fallback | Backend permission-race tests and real Android registration |
| Time/timezone reconciliation | Civil/DST tests and actual Android lifecycle recorder |
| Installed upgrade preserves state without duplicate actions | Baseline4→candidate5 install-r with original keys and uncertainty |
| Observable health and complete deterministic use without AI | Native UI, diagnostics and process-ownership tests |

Physical S21 qualification, broader API/OEM compatibility, Doze/battery restriction
matrix, resource stress and the required long-running soak remain separate evidence
obligations. A finite series is deliberately finite; continuing past its disclosed
last occurrence requires new exact confirmation. This is not an unbounded recurring
authorization grant or an architectural waiver.

## Platform references

- [AlarmManager delivery and special access](https://developer.android.com/develop/background-work/services/alarms)
- [WorkManager custom initialization](https://developer.android.com/develop/background-work/background-tasks/persistent/configuration/custom-configuration)
- [ApplicationStartInfo primary Force Stop signal](https://developer.android.com/reference/android/app/ApplicationStartInfo#wasForceStopped())
- [Current-start completion callback](https://developer.android.com/reference/android/app/ActivityManager#addApplicationStartInfoCompletionListener(java.util.concurrent.Executor,java.util.function.Consumer%3Candroid.app.ApplicationStartInfo%3E))
- [Public Memory Limiter exit reason](https://developer.android.com/reference/android/app/ApplicationExitInfo#REASON_MEMORY_LIMITER)
- [Compatible Memory Limiter evidence](https://android-developers.googleblog.com/2026/06/prioritizing-memory-efficiency-steps-for-android-17.html)
