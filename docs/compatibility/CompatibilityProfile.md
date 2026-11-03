# CompatibilityProfile

Compatibility evidence is kept separate from performance evidence. The initial
performance target is the Samsung Galaxy S21, while API compatibility must be
qualified independently against supported releases and Android 17/API 37.

Each record identifies whether it ran in `CONTAINER`, `HOST`, `ANDROID_EMULATOR`, or
`PHYSICAL_DEVICE`. Only physical-device results are definitive for Android runtime
and hardware behavior. Emulator results can qualify API-specific surfaces that the
Galaxy S21 cannot execute, but do not replace physical performance evidence.

Required qualification areas include background restrictions, foreground services,
microphone access, exact alarms, VoiceInteractionService, ApplicationExitInfo,
memory-limit behavior, process pressure, and native 16 KiB compatibility.

The current native gate is recorded in `native-compatibility-gate.json`. It is now `PENDING`: Compose graphics-path and DataStore bring transitive native
libraries. The former `NOT_APPLICABLE_NO_NATIVE_ARTIFACTS` state no longer applies.
Static ELF/ABI/APK/AAB checks run as part of `check`. Physical load qualification
is tracked separately and must not be replaced by x86_64 emulator evidence. The validator scans the repository for native
source/build markers and native libraries, and inspects generated APK/AAB packages
for transitive `.so` files. If one appears while the gate retains that state,
validation fails and `ORION-FND-001` must activate the real gate.

The activated gate tracks ELF 16 KiB alignment, APK/AAB packaging, a load test, and
ABI compatibility independently. `PASS` is valid only when every check passes;
`PENDING` and `FAIL` remain explicit non-passing states. Device-dependent load
evidence continues to follow the Docker/host/physical-device boundary.
