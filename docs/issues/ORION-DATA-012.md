# ORION-DATA-012 — Durable admission regression fixes

Issue: <https://github.com/otavio-esteves/O.R.I.O.N/issues/27>.

## OBJECTIVE
Fix the three pre-F3 review findings authorized by the user: preserve queued
ingress on transient crypto unavailability; reserve each event retry using fresh
wall time; validate confirmation after acquiring the SQLite writer transaction.
Architecture V3.2 §§48–57/70–73 and Master Plan V1.2 §8 apply.

## FILES ALLOWED
`data/repository/**/OrionDurableIngressStore.kt`;
`data/events/**/OrionEventDispatcher.kt`;
`data/actions/**/OrionActionStore.kt`;
related app instrumentation tests; this package and qualification evidence.

## CONTRACTS INVOLVED
Durable ingress terminality; versioned field encryption; bounded Event Outbox
retry; exact confirmation binding/expiry; Room writer admission and atomic audit.

## INVARIANTS INVOLVED
INV-008/011/019/021/022/023/035. No external action retry or execution is added.

## TESTS REQUIRED
- Transient CRYPTO_UNAVAILABLE preserves the encrypted queued row, version and
  attempt count, invokes no consumer, and allows consumption after recovery.
- A slow first consumer cannot give later events an already elapsed retry time;
  wall-clock rollback during the batch prevents delivery of events not yet due.
- Confirmation expiring or becoming future-dated during writer contention is
  rejected without changing action state, version or audit; fresh confirmation
  still succeeds after waiting.
- Focused Docker unit/build/lint/module-boundary checks and Android instrumentation
  on a disposable API35 emulator. Emulator evidence is NON_DEFINITIVE for hardware.

## ACCEPTANCE CRITERIA
All regression cases pass without changing public contracts, schema, payloads,
IPC, process topology or authorization. Preserve corruption rejection, bounded
retry and atomic mutation/audit. Record actual checks and remaining qualification.

## PROCESS DEATH / SECURITY / UPGRADE
Crypto failure leaves the existing durable request pending. Event attempt/backoff
still commits before consumers run. Confirmation validation and persistence share
the writer transaction, whose interruption rolls back state and audit. No new
logging, sensitive payload persistence, permissions or migration is introduced.

## OUT OF SCOPE
Production task/Skill wiring, F4 recovery/scheduler, new health integration,
external effects, physical S21 qualification and architecture changes.

## VALIDATION
CONTAINER: the focused build/unit/lint/boundary command passed in 8m (997 tasks).
All 75 selected JVM cases passed; all 36 negative boundary assertions passed.
Changed persistence modules have zero lint issues. App lint passed with ten
warnings in unchanged toolchain/dependency files, app configuration and manifest.

HOST/ANDROID_EMULATOR API35/x86_64: first run the six new cases against the original
production code; five fail at their intended assertions and the valid-confirmation
control passes. After the fixes, updating both APKs with `adb install -r -t` and
running the complete app instrumentation suite passes all 88 cases, including the
six new regressions. No app data is cleared, no detector is suppressed and no
physical/OEM/24-7 qualification is claimed. The emulator is disposable.

Exact commands, source/artifact identities, test counts and raw-output hashes are
recorded in [the qualification evidence](../qualification/f2-durable-admission-regressions.json).
Remote PR CI is not asserted for this local change.
