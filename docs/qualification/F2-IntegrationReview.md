# F2 — Independent integration review

Integration: [issue #20](https://github.com/otavio-esteves/O.R.I.O.N/issues/20),
[PR #21](https://github.com/otavio-esteves/O.R.I.O.N/pull/21).
This review supplements the original [storage evidence](F2-StorageCompletion.md);
its source/artifact hashes remain historical evidence of the earlier candidate.

## Physical qualification decision

On 2026-11-04 the user explicitly deferred S21 physical testing. The follow-up is
[ORION-QUAL-S21-F2 / #23](https://github.com/otavio-esteves/O.R.I.O.N/issues/23).
No physical/hardware/OEM/24-7 qualification or TRANSACTION_CORE_READY declaration
is inferred from this integration. Android evidence remains NON_DEFINITIVE.

## Independent reviews and resolutions

Three delegated coding agents independently reviewed transactions, keys/private
payloads and execution/ingress. These are static agent reviews, not physical
qualification or a human GitHub approval. The primary agent reviewed the resulting
patches and serialized builds/device tests. A second agent also cross-reviewed the
execution/lock changes against the local Room 2.8.5 bytecode.

| Finding | Resolution / regression proof |
| --- | --- |
| P1: authority, confirmation expiry and lease clocks sampled before waiting for SQLite writer | claim/finish acquire the writer transaction before their reads and checks; four contended-writer Android regressions cover expiry, fresh lease, revoked permission and expired callback |
| P2: supersession extends an already finite claim validity; equal timestamps create an empty interval | preserve min(existing end, supersession time); reject empty intervals before mutation; two new Android cases and stronger rollback assertions |
| P3: optional key-stage exception logs CORE_BLOCKED while publishing DEGRADED_SAFE | derive error event from published readiness; two new unit cases cover optional versus critical failure |
| Lock-order consequence of fresh checks inside writer transaction | isOpen becomes a volatile snapshot without a monitor; recovery/admission remain synchronized; unit regression reproduces writer→snapshot versus barrier→writer |

The lock change preserves admission semantics: only whileOpen admits bounded local
transactions. Reading a snapshot never grants execution authority. Test databases
use explicit WAL so the contention proof does not depend on LowRAM/AUTOMATIC.

## Validation

CONTAINER, approved:

```bash
docker compose run --rm android-build :app:testDebugUnitTest :core:ingress:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :data:actions:lint :data:database:lint verifyModuleBoundaries --max-workers=2
docker compose run --rm android-build :core:recovery:testDebugUnitTest --max-workers=2
```

Focused build: 688 tasks, 6m01s, 60 executed/628 up-to-date. Recovery check: 45 tasks,
1m10s, 1 executed/44 up-to-date. In total, 22 JVM cases across app (4), ingress (9)
and recovery (9) passed. Lint in the changed persistence modules and boundaries passed.

HOST/ANDROID_EMULATOR API35/x86_64: install matching debug/test APKs with `adb -s
emulator-5582 install -r -t`, then run `shell am instrument -w
network.orion.test/androidx.test.runner.AndroidJUnitRunner`. The complete suite
passed **82 tests**, including all six new Android regressions. A pre-test system
UI ANR dialog was resolved through its official Close app action before installation;
no app test/detector was suppressed. This disposable emulator is not S21 evidence.

Source/artifact identities and case counts are in
[f2-integration-review-evidence.json](f2-integration-review-evidence.json).
The original head `19efeeb` passed
[CI run 41928025415](https://github.com/otavio-esteves/O.R.I.O.N/actions/runs/41928025415).
The required `docker-build-and-check` must also pass on the final reviewed PR head;
PR #21 tracks that current status. No administrator bypass is authorized or used.

## Recovery, security and upgrade impact

The outer writer transaction rolls back ownership/audit on failure and refuses
stale consent or stale terminal callbacks. Claim supersession never extends truth
validity. Logs contain technical readiness codes only. No schema, payload format,
IPC, process topology, permission or exported component changes; Room remains v3.
Skills, operational F4 recovery and external effects remain unavailable.

The first separate F3 package is
[CreateTask preparation / #22](https://github.com/otavio-esteves/O.R.I.O.N/issues/22):
typed validation/canonicalization and static metadata, with no mutable runtime wiring.
