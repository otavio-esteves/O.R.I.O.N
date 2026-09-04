# ADR-0001: Docker-first build with host and physical-device validation

- Status: Accepted
- Date: 2026-09-03
- Scope: Development, build, validation, evidence, and future CI infrastructure
- Supersedes: Nothing
- Architectural invariants affected: INV-032 (reinforced)

## Context

O.R.I.O.N. needs a reproducible Android and native toolchain without confusing a
Linux container with Android runtime behavior. The architecture already permits
shell-based development automation and requires separate physical-performance and
Android-API compatibility qualification.

## Decision

Docker is the canonical environment for reproducible build and automation whenever
the task does not require real Android hardware or host integration. This includes
Gradle/JDK/SDK/NDK/CMake, Kotlin/Java/native compilation, JVM and hardware-independent
unit tests, lint, static and architecture checks, dependency checks, native
compatibility checks, packaging, and APK/AAB generation.

The preferred command boundary is:

```bash
docker compose run --rm android-build <task>
```

The Linux host is canonical for Android Studio, ADB, USB discovery, APK installation,
logcat, interactive debugging, profiling, process/CPU/memory inspection, Perfetto,
traces, graphics, and direct interaction with devices. ADB and USB are not exposed to
the container by default.

A physical Android device is the source of truth for runtime behavior and hardware
evidence, including local LLM inference, STT/TTS, audio and wake word, latency,
RAM/CPU/battery/thermal behavior, accelerators, throttling, background execution,
foreground services, Doze/App Standby, process-death and reboot recovery, continuous
operation, 24/7 behavior, and real Android API integration.

Every `BenchmarkProfile` declares one execution environment:

```text
CONTAINER
HOST
ANDROID_EMULATOR
PHYSICAL_DEVICE
```

Evidence about Android runtime or hardware may be marked `DEFINITIVE` only for
`PHYSICAL_DEVICE`, unless a higher-precedence normative test plan explicitly defines
a narrower exception. Container/JVM/emulator results remain useful but
`NON_DEFINITIVE` for those claims. API compatibility remains a separate matrix and
may require an emulator or a newer physical device when the Galaxy S21 cannot execute
the target API.

Docker is development/build/CI infrastructure only. The APK/AAB must not depend on a
container, a Docker-local service, or development-host connectivity at runtime.

## Reproducibility controls

- Pin tool versions and verify direct downloads with checksums.
- Keep the Gradle Wrapper and toolchain profile versioned.
- Avoid moving image tags where a stable digest is practical.
- Keep hidden host dependencies out of canonical build tasks.
- Record toolchain profile, commit SHA, build variant, candidate hash, backend, device,
  OS/API, battery, temperature, and duration with benchmark evidence.

## Consequences

- Developers need Docker for canonical command-line builds and host Android tooling
  for device work.
- Physical qualification cannot run as an ordinary container-only CI job.
- CI should invoke the same Compose service and Gradle tasks as local development;
  device-lab stages remain explicitly separate.
- Removing Docker or changing this boundary requires documented architectural
  justification and review of reproducibility and evidence impact.

## Rejected alternatives

- Host-only builds: increase drift and hidden dependencies.
- ADB/USB inside Docker by default: adds platform-specific privilege and device
  complexity without improving the build boundary.
- Container/emulator evidence as a substitute for physical qualification: cannot
  establish the required hardware, thermal, battery, background, or 24/7 behavior.

## Migration

Existing Docker build commands remain unchanged. The BenchmarkProfile contract
records environment, commit, build variant, backend, initial temperature, and
separate execution and qualification outcomes before any real evidence is promoted.
Future CI reuses the Compose/Gradle entry points and adds a separately governed
physical-device stage.
