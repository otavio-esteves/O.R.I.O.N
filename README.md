# O.R.I.O.N.

Operational Reasoning & Intelligent Orchestration Network.

## Development environments

Docker is the canonical, reproducible environment for build and automation: Gradle,
JDK, Android SDK/NDK, compilation, unit tests, lint, static analysis, compatibility
checks, and APK/AAB generation run there whenever technically applicable.

The Linux host owns Android Studio, `adb`, USB/device communication, installation,
interactive debugging, logcat, profiling, traces, and other host-integrated tools.
ADB and USB are deliberately not passed into the build container by default.

An Android physical device is the source of truth for runtime behavior, hardware
benchmarks, audio, local AI, memory, battery, thermal behavior, background execution,
recovery, and long-running stability. Container, desktop JVM, or emulator results are
not definitive evidence for those properties. The normative workflow and evidence
rules are recorded in [ADR-0001](docs/adr/ADR-0001-docker-first-host-device.md).

## Bootstrap

```bash
docker compose build android-build
docker compose run --rm android-build toolchainInfo
docker compose run --rm android-build check
```

Equivalent convenience targets are available as `make docker-build`, `make toolchain`,
and `make check`.

The F0 benchmark harness and its versioned profile contract live under `benchmark/`.
Run `make benchmark-validate` to validate committed evidence and the negative fixtures.

The Foundation includes shared contracts, a main-only Room/DataStore bootstrap,
structured technical logging, health aggregation, and Compose Home/Diagnostics/Settings.
Room v3 adds transactional domain records, a reference-only Event Outbox, durable
ingress and bounded retention through explicit 1→2→3 migrations. Encrypted action
preparation, guarded execution ownership, conservative abandoned-action recovery,
Keystore-wrapped versioned data keys, and gated transactional ingress are implemented.
MAIN startup provisions keys only after an empty-storage proof and a durable attempt
marker. Reminder/memory adapters provide encrypted reads, CAS mutations, transactional
supersession and claim deletion. Large ingress content has a bounded encrypted private
file path with TTL. Product commands, Skill execution and complete F4 operational
recovery remain gated. AI and voice remain unavailable. See the
[F2 storage completion and remaining qualification](docs/qualification/F2-StorageCompletion.md).

Build both APK variants, the release AAB, and the instrumentation APKs, and execute
unit, lint, benchmark, native-compatibility, and module-boundary gates with:

```bash
docker compose run --rm android-build check assembleDebug assembleRelease :app:assembleDebugAndroidTest :core:security:assembleDebugAndroidTest --max-workers=2
```

The GitHub Actions baseline runs this same Compose flow. Android device installation
and runtime validation remain host/physical-device responsibilities.

```text
source -> Docker build/check/APK -> Linux host + ADB -> physical Android device
```

The first image build downloads the pinned Android SDK, NDK, and CMake packages and
therefore can take several minutes and several gigabytes. See
`docs/toolchain/ToolchainProfile.md` for the normative version set.

If Docker reports permission denied for `/var/run/docker.sock`, grant the current
user access to the daemon according to the host installation, then restart the
session. Do not run the build with `sudo`, because that creates root-owned artifacts.

## Foundation Android validation

After the Docker build, run the host harness against an explicit device serial:

```bash
python3 scripts/android/verify-foundation-runtime.py \
  --serial emulator-5554 --environment ANDROID_EMULATOR \
  --evidence-dir /tmp/orion-f1-runtime
```

The host SDK's `adb` must be on PATH; otherwise pass `--adb /path/to/platform-tools/adb`.
For physical-device evidence, specify its serial and `--environment PHYSICAL_DEVICE`.
The harness installs debug/test APKs, checks navigation and Activity recreation,
verifies storage/IPC ownership in all auxiliary processes, injects main-process
death with `run-as`/SIGKILL under the debug app UID,
and tests allowed return after Force Stop. It records APK hashes, environment,
Android build/API, page size and results. It does not qualify recovery of business
side effects or 24/7 operation.

The host harness contract tests run without ADB or a device:

```bash
python3 scripts/android/test_foundation_runtime_harness.py
python3 scripts/android/test_transaction_process_death_harness.py
python3 scripts/android/test_installed_upgrade_harness.py
```

Action preparation, migration and encrypted-storage evidence is recorded in the
[F2 increment report](docs/qualification/F2-ActionPreparation.md). F2 remains in progress.

The native gate is active for Compose/DataStore transitive libraries. `check`
verifies ELF/ABI/APK/AAB packaging, including negative fixtures; physical arm64
load remains a separate qualification. See the [Foundation gate report](docs/qualification/F1-FoundationGate.md).

Compose preserves its default debug keystore in the project-scoped
`orion-android-user` volume. Keep that volume to update installed debug APKs across
builds. APKs signed with a previously lost ephemeral key cannot be updated with a
new key; the harness reports the Android error and does not uninstall or erase data.
Release signing is not configured by this development volume.

## Transaction process-death validation

After the canonical build, the host recorder can interrupt an open Room transaction
and verify its committed baseline in a different process. Use a dedicated target
with no concurrent instrumentation:

```bash
python3 scripts/android/verify-transaction-process-death.py \
  --serial emulator-5580 --environment ANDROID_EMULATOR \
  --adb /path/to/platform-tools/adb --evidence-dir /tmp/orion-f2-transaction-death
```

For physical evidence, use the explicit device serial and `--environment PHYSICAL_DEVICE`.
Add `--scenario task` to exercise owned task creation: the checkpoint is after the
uncommitted task/event/action-success writes, and verification in a new process
checks rollback followed by exactly one committed task/event after explicit replay.
The Keystore instrumentation APK is built by CI; running its
`network.orion.core.security.test/androidx.test.runner.AndroidJUnitRunner` on a device
is a separate host responsibility. Emulator evidence does not qualify S21/OEM hardware.

## Installed upgrade validation

The debug candidate is versionCode 2. The host recorder installs a prior APK/test APK,
seeds synthetic state, replaces both with the candidate using `adb install -r`, and
verifies encrypted records, original keys, queues, pending actions and UNKNOWN state.
It never uninstalls, clears data or falls back to forced downgrade. Use a dedicated
target whose installed version is no newer than the previous APK:

```bash
python3 scripts/android/verify-installed-upgrade.py \
  --serial emulator-5582 --environment ANDROID_EMULATOR \
  --adb /path/to/platform-tools/adb \
  --previous-apk /path/to/baseline/app-debug.apk \
  --previous-test-apk /path/to/baseline/app-debug-androidTest.apk \
  --evidence-dir /tmp/orion-f2-installed-upgrade
```

The baseline test APK must include `UpgradePersistenceTest`. For the schema-3 baseline
at `d7a2301`, copy that test source into a detached baseline checkout and build with
the canonical image and the same project debug-keystore volume. Keep the baseline
source identity, fixture hash and artifact hashes with the evidence. Real scheduler
artifacts and full recovery/reconciliation upgrades are a separate F4 qualification.
When sharing the Gradle user cache across checkouts, give the baseline a separate
project cache with `--project-cache-dir /tmp/orion-baseline-project-cache` so its
incremental output history cannot overwrite the candidate's history.
Run builds sequentially when sharing the Gradle user cache across containers.
