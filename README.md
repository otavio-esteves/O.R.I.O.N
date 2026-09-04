# O.R.I.O.N.

Operational Reasoning & Intelligent Orchestration Network.

The project is bootstrapped with a Docker-first Android toolchain. Docker is required
for canonical command-line builds; Android Studio may be used as an editor, and host
`adb` remains responsible for physical-device communication.

## Bootstrap

```bash
docker compose build android-build
docker compose run --rm android-build toolchainInfo
docker compose run --rm android-build check
```

Equivalent convenience targets are available as `make docker-build`, `make toolchain`,
and `make check`.

The first image build downloads the pinned Android SDK, NDK, and CMake packages and
therefore can take several minutes and several gigabytes. See
`docs/toolchain/ToolchainProfile.md` for the normative version set.

If Docker reports permission denied for `/var/run/docker.sock`, grant the current
user access to the daemon according to the host installation, then restart the
session. Do not run the build with `sudo`, because that creates root-owned artifacts.
