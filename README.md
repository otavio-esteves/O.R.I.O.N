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

The Foundation now includes shared contracts, a main-only Room/DataStore bootstrap,
structured technical logging, health aggregation, and Compose Home/Diagnostics/Settings.
AI, voice, mutable ingress and action execution remain unavailable until their later
implementation phases. The Room v1 schema contains technical metadata only; future
business schemas require explicit migrations.

Build both APK variants, the release AAB, and the instrumentation APK, and execute
unit, lint, benchmark, native-compatibility, and module-boundary gates with:

```bash
docker compose run --rm android-build check assembleDebug assembleRelease :app:assembleDebugAndroidTest --max-workers=2
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
verifies storage/IPC ownership in all auxiliary processes, kills the background app,
and tests allowed return after Force Stop. It records APK hashes, environment,
Android build/API, page size and results. It does not qualify recovery of business
side effects or 24/7 operation.

The native gate is active for Compose/DataStore transitive libraries. `check`
verifies ELF/ABI/APK/AAB packaging, including negative fixtures; physical arm64
load remains a separate qualification. See the [Foundation gate report](docs/qualification/F1-FoundationGate.md).

Compose preserves its default debug keystore in the project-scoped
`orion-android-user` volume. Keep that volume to update installed debug APKs across
builds. APKs signed with a previously lost ephemeral key cannot be updated with a
new key; the harness reports the Android error and does not uninstall or erase data.
Release signing is not configured by this development volume.
