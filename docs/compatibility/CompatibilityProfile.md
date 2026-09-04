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

The current native gate is recorded in `native-compatibility-gate.json`. It remains
`NOT_APPLICABLE_NO_NATIVE_ARTIFACTS` until a native dependency is introduced. That
state is not a compatibility pass.
