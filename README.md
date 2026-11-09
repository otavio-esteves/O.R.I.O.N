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
git submodule update --init --recursive
docker compose build android-build
docker compose run --rm android-build toolchainInfo
docker compose run --rm android-build check
```

Equivalent convenience targets are available as `make docker-build`, `make toolchain`,
and `make check`.

The F0 benchmark harness and its versioned profile contract live under `benchmark/`.
Run `make benchmark-validate` to validate committed evidence and the negative fixtures.

The F5 software foundation adds a MAIN-owned GGUF registry (Room schema 6), fresh
resource admission and a private disposable Cortex in `:ai`, backed by pinned
llama.cpp source in `third_party/llama.cpp`. Production qualified model plans remain
empty. The default Android observer explicitly reports missing complete fresh
aggregate-memory evidence; neither importing a model nor passing a software test
enables production inference. Physical resource/performance/native qualification
and the 24-hour stability soak remain open. See the [F5 software audit](docs/qualification/F5-SoftwareAudit.md).

An opt-in, separate instrumentation scope exercises a pinned tiny GGUF through
the real registry/Binder/JNI pipeline using synthetic test-only resource inputs:

```bash
docker compose run --rm android-build -PorionNativeSmoke=true :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2
```

This builds the test model into the instrumentation APK only. Install/run that scope
on an explicitly selected disposable emulator/device using host ADB; it is software
integration evidence, not a real-model resource profile or S21 qualification.
Rebuild without the flag to restore the complete application instrumentation suite.

The deterministic F3 candidate now includes native task, reminder, timer, personal
memory and manual notification workflows, plus device information and activity
history. All domain mutations pass through encrypted durable ingress, deterministic
policy, exact human confirmation and owned ActionRequest execution. Local domain,
action, audit, event and ingress changes commit together. Each domain consumes only
its own queue scope behind the same operational Startup Barrier.

Room v5 preserves durable timers and adds explicit scheduling/finite-occurrence metadata through the non-destructive 1→2→3→4→5 migration chain. Timers use
monotonic elapsed time only in their original BootSessionId and expose wall fallback
after reboot. User-declared memory remains distinct from inference; forgetting
removes current entity/claim/processing copies and preserves minimal replay
protection. Manual notification claims commit before invoking Android outside both
Room transactions and the local barrier monitor. Original consent, capabilities,
permission and ownership are revalidated at invocation; uncertain outcomes are
never automatically resent.

The F4 runtime candidate adds explicitly confirmed one-shot and finite daily/weekly
reminder alerts, exact-alarm capability with observable approximate fallback,
reference-only durable callbacks, event publication and recovery/unique maintenance.
Every occurrence in a finite series is prepared and individually bound to an original
confirmation receipt before activation. Unknown delivery pauses that series; cancel
or snooze invalidates its remaining waiting occurrences. Legacy reminders retain no
external alert authority and require a new explicit opt-in. Timers still have no
automatic background alerts.

Core readiness remains DEGRADED_SAFE while the physical/system qualification gate
is open; AI and voice remain unavailable. This candidate does not declare resilient
MVP or 24/7 readiness. Current validation and the gate checklist are recorded in the
[F4 runtime report](docs/qualification/F4-RuntimeGate.md); the
[F3 implementation report](docs/qualification/F3-ImplementationGate.md) remains
historical evidence of the preceding baseline.

The final F4 candidate passed 630 Debug JVM cases, 35 host contracts, 313 application
Android cases and four Keystore cases. Real emulator notification checks cover
Doze, reboot, Force Stop/allowed return, time/timezone changes and observable
background restriction. Actual committed-claim SIGKILL verifies no blind replay.
The reports retain source/artifact hashes and failed attempts; physical/system/soak
qualification remains open in [issue #64](https://github.com/otavio-esteves/O.R.I.O.N/issues/64).

The later [F4 closeout audit](docs/qualification/F4-Closeout.md) records the physical
S21 fixes and capability/resource matrix, 318-case S21/API31 baseline evidence and
the latest 319-case S21/API37 regression suites without failures or skips,
actual debug native loading on S21 and API37/16KiB emulator, and a completed
600-second S21 task/timer/reminder workload with actual process death and no
observation gaps. The failed wireless one-hour attempt and sampler-race attempt are
retained. A short run never closes the real 24-hour gate; release/native and broader
compatibility decisions remain separate. Per-run APK hashes distinguish test-only
revisions, including the Espresso compatibility repair for API37.

The historical [integrated F3 evidence](docs/qualification/f3-integrated-evidence.json) records
559 Debug JVM, 18 host and 247 Android cases, installed replacement and five actual
process-death scenarios, with frozen source and matching artifact hashes. Full
canonical Docker CI covers lint, module and native packaging gates. Runtime evidence
is API35/x86_64 emulator evidence; the physical qualification remains open.

Build both APK variants, the release AAB, and the instrumentation APKs, and execute
unit, lint, benchmark, native-compatibility, and module-boundary gates with:

```bash
docker compose run --rm android-build check assembleDebug assembleRelease :app:assembleDebugAndroidTest :core:security:assembleDebugAndroidTest --max-workers=2
```

The GitHub Actions baseline runs this same Compose flow. Android device installation
and runtime validation remain host/physical-device responsibilities.

Host ADB recorder contracts run without a device using
`docker compose run --rm host-harness-check`. The
[passive soak observer](docs/qualification/F4-SoakObservation.md) prepares bounded
technical observations for the pending long-running recovery qualification; a
completed collection never automatically approves that gate.
New controlled runs retain an atomic recovery journal. After an interruption,
`recover-f4-soak-runtime.py` performs scoped cleanup against the same device/APKs
and preserves the failed run; see the recovery section in that observer document.

The earlier [F4 preparation report](docs/qualification/F4-Preparation.md) describes
PR #62 before production activation. The runtime candidate builds on those contracts;
its physical and full system/soak qualification remains separately gated.

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
python3 scripts/android/test_scheduled_reminder_runtime_harness.py
```

Action preparation, migration and encrypted-storage evidence is recorded in the
[F2 increment report](docs/qualification/F2-ActionPreparation.md). The later
[S21 physical review](docs/qualification/F2-S21-PhysicalGate.md) records the scoped
transaction-core evidence and gate decision; historical deferrals remain retained.

The native gate is active for Compose/DataStore transitive libraries. `check`
verifies ELF/ABI/APK/AAB packaging, including negative fixtures. Scoped debug loads
passed on S21 arm64/4KiB and API37 x86_64/16KiB; release/R8 and physical arm64/16KiB
loads remain separate. See the [scoped load record](docs/compatibility/native-load-scoped-20261107.json)
and [Foundation gate report](docs/qualification/F1-FoundationGate.md).

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
Additional scenarios are `reminder`, `timer`, `memory`, `notification` and `reminder_delivery`.
Memory interrupts an uncommitted forget operation and verifies restoration of all
logical copies before explicit erasure replay. Notification death uses a durable
synthetic external marker after the committed claim; it proves no blind replay,
while real NotificationManager registration/private content are separate Android
instrumentation cases. The recorder never substitutes Force Stop or root for SIGKILL.

The Keystore instrumentation APK is built by CI; running its
`network.orion.core.security.test/androidx.test.runner.AndroidJUnitRunner` on a device
is a separate host responsibility. Emulator evidence does not qualify S21/OEM hardware.

## Installed upgrade validation

The F4 runtime debug candidate is versionCode 5 (Room schema 5); the preceding F3 baseline is versionCode 4/schema 4. The host recorder installs a prior APK/test APK,
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

## Scheduled reminder system validation

The F4 recorder observes a real Android notification before verification starts the
app again. Use a dedicated target because these scenarios change its lifecycle,
clock, timezone or power restrictions and temporarily grant test access:

```bash
python3 scripts/android/verify-scheduled-reminder-runtime.py \
  --serial emulator-5586 --environment ANDROID_EMULATOR --dedicated-target \
  --adb /path/to/platform-tools/adb --scenario doze \
  --evidence-dir /tmp/orion-f4-doze
```

Other scenarios are `reboot`, `force_stop`, `time_change`, `timezone_change` and
`background_restriction`. Run Doze first to respect allow-while-idle alarm quotas.
Force Stop requires an explicit permitted return; background restriction remains
observable degradation. Reports retain artifact/source identities and restore
the target's temporary settings. Emulator results remain non-definitive.
