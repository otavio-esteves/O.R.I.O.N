# O.R.I.O.N. Toolchain Profile

Profile ID: `orion_toolchain_2026_09_03_01`

This profile is the reproducible baseline for `ORION-F0-000`. A material toolchain
change requires a new profile and re-execution of affected qualification checks.

| Component | Pinned value |
| --- | --- |
| Docker base image | `eclipse-temurin:17-jdk-jammy@sha256:400014962ad7224461f945bb1cc3d7d5a1927ce15b8245b72d9cedcda554cd2a` |
| `minSdk` | 31 |
| `targetSdk` | 37 |
| `compileSdk` | 37 |
| Android SDK Platform package | 37.0 (revision selected by the stable channel) |
| JDK | 17 |
| Kotlin | 2.4.10 |
| Android Gradle Plugin | 9.4.0 |
| Gradle | 9.6.0 |
| Android Build Tools | 36.0.0 |
| Android Command-line Tools | 15859902 |
| NDK | 29.0.14206865 (r29) |
| CMake | 4.0.2 |
| Initial ABI | `arm64-v8a` |

Native dependency versions and commit SHAs are intentionally absent until the F0
benchmark harness selects candidates. No native dependency is part of this baseline.

## Docker contract

The canonical CLI build runs in the repository image:

```bash
docker compose build android-build
docker compose run --rm android-build toolchainInfo
docker compose run --rm android-build check
```

Docker is the build boundary. Device access remains on the host: use host `adb` for
the Galaxy S21 and pass APK artifacts produced under the mounted workspace. Docker
does not receive ADB/USB access by default and is never an application runtime
dependency. See `docs/adr/ADR-0001-docker-first-host-device.md` for the normative
development-environment boundary.

Building the image downloads the Android SDK and records acceptance of its licenses.

## Native compatibility gate

The initial ABI is `arm64-v8a`. Once native artifacts enter the repository, the F0
gate must verify 16 KiB ELF alignment and packaging before they can be qualified.
Until then the gate is `NOT_APPLICABLE_NO_NATIVE_ARTIFACTS`, not `PASS`.
The gate validator scans native sources, build markers, libraries, and generated
APK/AAB contents, so the first direct or transitive native artifact makes that state
invalid and activates the tracked `ORION-FND-001` work.
