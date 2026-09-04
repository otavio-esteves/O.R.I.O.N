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

The initial Foundation skeleton contains the Android application in `:app` and the
process-bootstrap contract in `:core:common`. Build both APK variants and execute all
unit, lint, benchmark, native-compatibility, and module-boundary gates with:

```bash
docker compose run --rm android-build check assembleDebug assembleRelease
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
