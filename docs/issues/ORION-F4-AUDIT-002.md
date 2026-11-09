# ORION-F4-AUDIT-002 — Memory erasure and truthful bounded maintenance

OBJECTIVE: Fix the three audit findings: accumulated memory proposals blocking
forget, retention batches without continuation, and healthy maintenance reports
despite degraded event publication.

FILES ALLOWED: memory DAO/proposal admission/erasure, retention result and runtime
maintenance/worker wiring, focused JVM and Android tests, this issue.

CONTRACTS INVOLVED: existing atomic memory forget and replay tombstone, bounded
retention, Startup Barrier, event outbox, maintenance diagnostics and WorkManager.

INVARIANTS INVOLVED: INV-006, INV-007, INV-008, INV-011, INV-014, INV-019,
INV-021, INV-022, INV-023, INV-035; AGENTS.md retention and observability rules.

TESTS REQUIRED: legacy histories beyond 10,000 rows erase all target copies;
proposal limits preserve dedup and allow forget; erasure remains atomic on failure;
full retention/expiry batches request continuation; DEAD_LETTER degrades the pass
without requesting continuation; unresolved data and consent remain unchanged.

ACCEPTANCE CRITERIA: finite-snapshot erasure uses bounded pages without a global
10,000-row failure; new memory proposals have explicit pending/retained limits;
internal maintenance continues while bounded batches are full; publication failure
cannot produce a healthy snapshot or a successful completed reconciliation worker.

OUT OF SCOPE: schema/migrations, architecture or authority changes, automatic
external retries, inference/voice, physical/soak qualification, publishing/merging.

Existing uncommitted ORION-F4-INGRESS-001 work is preserved and integrated.

## Implementation and recovery

- Memory erasure captures finite action/ingress high-water marks under the existing
  writer transaction and traverses 100-row keyset pages. Historical overflow no
  longer causes the former global 10,000-row rejection. Target matching still
  authenticates and validates encrypted content. Cancellation, deletion, ciphertext
  erasure, audit and replay tombstone remain atomic; a failure rolls everything back.
- New memory creation proposals are limited to 1,000 pending/confirmed proposals
  and 10,000 retained creation actions. Existing keys still undergo normal dedup /
  conflict validation; confirmation, dismissal and forget do not create a new
  creation proposal and remain available at capacity. No uncertain action is
  deleted or automatically retried to make room.
- Saturated retention/expiry batches request bounded internal continuation.
  Event publication health now participates in maintenance health. Stable
  degradation ends the worker as failure rather than success; DEAD_LETTER alone
  does not request continuation or additional event delivery attempts.
- Schema v5, migration chain, ciphertext formats, confirmation binding and process
  ownership remain unchanged. No user content or new identifiers enter logs.
  Historical erasure retains one atomic transaction; paging bounds memory usage,
  while total scan time still depends on the finite stored history.

## Validation — 2026-11-08

- HOST (noncanonical fallback): Docker socket access was denied; noninteractive
  sudo also requires a password. The existing host JDK 21 / Android SDK was used
  explicitly, without changing the pinned canonical Docker toolchain.
- HOST: wrapper `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug
  :app:assembleDebugAndroidTest --offline --max-workers=2` passed: 81 JVM cases,
  zero failures/errors/skips; lint zero errors and 14 warnings; both Debug APKs
  assembled. Room generated no schema changes. Build log:
  `/tmp/orion-audit-checks.log` (1,756 actionable tasks).
- HOST: all 125 harness contracts and all 36 module-boundary checks passed;
  `git diff --check` passed.
- Debug APK SHA256: `1d2598f2763f5ab89d319c4ab391ae8031bd0375b9666c19e01b267df6767eeb`.
- Debug test APK SHA256: `d19233fc5ad5ed8368fc5260dec611a568f062966a4e078e6fb21267277908ba`.
- ANDROID_EMULATOR: API37/x86_64, `Orion_API_37` in a temporary read-only
  instance (`emulator-5580`): all 60 cases across `OrionMemoryKernelTest`,
  `TransactionCorePersistenceTest`, `OrionSchedulingCoordinatorTest` and
  `OrionTaskKernelTest` passed without failures or skips (20.851 seconds).
  This includes 10,001 historical erased actions plus the real target, paged
  action/ingress rollback, both proposal admission limits, dedup/dismiss/forget
  at capacity, terminating retention continuation preserving UNKNOWN, and a
  real SQLite DEAD_LETTER whose attempts/version never change over repeated
  maintenance publication. The existing 80-request ingress regression also passed.
  Log: `/tmp/orion-audit-android-tests.log`.
- The initial emulator install rejected the host debug signature against its
  existing Docker-signed package. Only packages in the temporary read-only
  instance were removed/reinstalled; the original AVD and physical devices were
  untouched. The host APK pair has the separate identities above.

The preceding host/emulator results alone do not establish canonical Docker
validation; the subsequent section records that independent run. Physical
S21/performance, actual process death and long-run qualification remain open.

## Subsequent canonical Docker validation — 2026-11-08/09

Docker access succeeded with `sg docker -c '...'`: the account was already listed
in the `docker` group, while the running session had not loaded that group. The
socket remains `0660 root:docker`; no socket permissions or daemon configuration
were changed. All following build/verification commands run in the canonical
Compose services, without ADB/USB passthrough.

- CONTAINER: `docker compose build android-build` passed. Image:
  `sha256:e5516d4fbe83e9d4ee9608b87eff3e577a3120a3ded9d46773bd314a8afe728d`.
- CONTAINER: `docker compose run --rm host-harness-check` passed all 125 cases.
- CONTAINER: `docker compose run --rm android-build toolchainInfo check
  assembleDebug assembleRelease :app:assembleDebugAndroidTest
  :core:security:assembleDebugAndroidTest --max-workers=2 --console=plain` passed
  (4,112 actionable tasks: 2,041 executed, 686 cached, 1,385 up-to-date).
  Toolchain: Java 17.0.20, Gradle 9.6.0, SDK `/opt/android-sdk`.
  All 665 Debug JVM cases across 94 suites passed without failures/errors/skips.
  App lint: zero errors, 14 warnings. Module boundaries, benchmark contracts,
  native gate fixtures, ELF/ABI checks, Debug/Release APK and release AAB native
  packaging/16KiB alignment passed. Room schema v5 remained unchanged.
- Logs: `/tmp/orion-audit-docker-image.log`,
  `/tmp/orion-audit-docker-harness.log`, `/tmp/orion-audit-docker-checks.log`.
- CONTAINER: the final CI command, `docker compose run --rm android-build
  -PorionQualificationRelease=true :app:verifyQualificationNativePackage
  :app:assembleQualificationReleaseAndroidTest --max-workers=2 --console=plain`,
  also passed (1,619 actionable tasks). R8 produced both minified qualification
  APKs; the app APK passed ELF/ABI and static native packaging checks for arm64
  and x86_64. Log: `/tmp/orion-audit-docker-qualification.log`.

Canonical artifact SHA256 identities (distinct from the earlier host pair):

| Artifact | SHA256 |
| --- | --- |
| Debug APK | `bd306faf16d4a1e6e4afce8e0f5256c5831464d0bcff5940e8efe2f4c80d207c` |
| Debug test APK | `986dd5d9b56c904f35ac13c7a028e579e8c21e17c2200f84164a1e823a0a9d8a` |
| Unsigned release APK | `2fc75ced78ac3351bcd50938e9f78d3992f25192c85dcf648cbe8129754357c8` |
| Release AAB | `f7cd032e8eb5fad5195e4ef27cfa1e604c6914c5c8e0507fcde7d1468acfac16` |
| Minified qualification APK | `4c39465bbd4b205a53bd9f2349fd8f17e16756d7c34f28cfd954b6b85015ac57` |
| Minified qualification test APK | `062aeb2ed72c7e0a4399d92dbaeb20c7a7c7bb21d87856485e923fce24bfa81f` |

The earlier host APK pair is retained in `/tmp/orion-audit-host-artifacts/`.
These are local canonical results, not a GitHub Actions status or physical gate
closure. Native qualification metadata remains `PENDING`.

On 2026-11-09, the canonical Debug APK pair above was installed via host ADB in a
new temporary read-only `Orion_API_37` instance (`emulator-5580`, API37/x86_64,
4KiB). Both `adb install -r` operations passed with the original Docker signer;
no package removal was needed for this run. The same four focused suites passed
all 60 Android cases without failures/skips (14.796 seconds). Log:
`/tmp/orion-audit-docker-android-tests.log`. Platform fingerprint:
`Android/sdk_phone64_x86_64/emu64x:17/CE2A.260420.051.A3/16571990:userdebug/test-keys`.
This is independent emulator evidence for the canonical pair; it does not relabel
the earlier host pair or establish physical qualification. The temporary emulator
was closed after verification. The original AVD and physical devices were untouched.
