# Optional generation metrics in the minified qualification APK

Work package: [ORION-F5-GENERATION-IPC-001](../issues/ORION-F5-GENERATION-IPC-001.md),
[#119](https://github.com/otavio-esteves/O.R.I.O.N/issues/119).
Contract: [accepted ADR-0003](../adr/ADR-0003-optional-cortex-generation-metrics.md).

OBJECTIVE: Exercise the actual optional metrics source through the private Binder
of the single existing :ai runtime in a minified, non-debuggable qualification APK.
FILES ALLOWED: OrionQualificationInstrumentation.java, the new platform-only
OrionGenerationMetricsQualification.java, the existing native probe's minor-version
compatibility check, and this note. No runtime/IPC/native/build-policy changes.
CONTRACTS/INVARIANTS: ADR-0001/0002/0003; Architecture §§22/23/32/33; Master Plan
§11.11; INV-002/011/012/013/027/028/032/033/036. This is an explicitly scoped
emulator integration fixture, not production ResourceGovernor admission.
TESTS REQUIRED: Default three probes remain; optional one-probe raw-Binder path
on the owned emulator, with frozen source/app/test/model hashes and exact flags.
ACCEPTANCE: Actual bounded load/generation/FINAL/cache/unload observations with
closed primitive schemas, real service identity, honest absent metrics and no
output/request IDs logged. Required CI precedes merge. OUT OF SCOPE: production
profiles, physical/native-performance qualification, new runtime/JNI in MAIN,
new IPC, policy/retry/configuration changes, hardware/soak/F4/F5/F6 closure.

Build the separate qualification identity with trusted build-time options:

```text
-PorionQualificationRelease=true -PorionCortexGenerationMetrics=true
```

The existing platform runner selects the optional one-test mode only with all
three explicit instrumentation arguments:

```text
-e orionGenerationMetricsProbe true
-e orionGenerationMetricsEnvironment ANDROID_EMULATOR
-e orionGenerationMetricsModelPath <canonical-direct-filesDir-path>
```

`orionNativeOnly=true` cannot be combined with this mode. Without metrics arguments,
the default three probes (two for nativeOnly) remain unchanged. This mode does
not run the MAIN native-library probe or UI startup probe. It imports no target
runtime/codec/AIDL class and depends on no obfuscated class name. It uses stable
private Binder descriptors and append-only transaction numbers. Arguments and
queries cannot enable the build-time source or approve a model/profile.

Only emulator Build signals plus the declared ANDROID_EMULATOR are accepted;
this is not hardware attestation. The fixture is the exact model in
[model-manifest.json](../../ai/llama/test-fixtures/model-manifest.json):
491400032 bytes, SHA256
`74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`.
The canonical path must be a direct child of the target's canonical filesDir.
The probe rejects symlinks, opens O_NOFOLLOW/read-only, checks regular-file and
inode/device/size identity, and hashes that same FD before passing it to load.
The service independently validates its original FD/digest contract.

HOST fixture preparation is separate from this APK behavior. The non-debuggable
qualification identity requires ADB root solely on the explicitly owned emulator
to stage/chown/restorecon this private file and clean it afterward. Root owns the
serial guard, source/APK/model freeze, installation and cleanup. Never apply this
procedure to an S21, an existing user device or unrelated emulator. The APK does
not stage/import/download a model, grant settings or bypass Android permissions.
Application/service bootstrap may create its own technical state.

The probe uses context512/threads1/maxTokens1/temperature0 with fixed, non-user
input. A 300s outer monotonic budget and finite poll counts cover hash/load/infer/
cleanup. Each load/inference request retains the original <=120s deadline. These
bounds cannot preempt a blocked synchronous Binder/filesystem/provider operation;
they do not extend product deadlines. There is one requested generation, no
second native runtime, synchronization barrier or query-triggered work.

The callback checks typed schema, issued transport ID, sequence and one final
terminal while discarding text. Binder chunk counts are never treated as tokens.
The retained FINAL must report JNI_RETURNED, RELEASED, logical SUCCEEDED, valid
stage/count/mask/actual-request relations and the same separately observed :ai
PID/current endpoint. Both immediate EOG and TOKEN_LIMIT are valid one-token
outcomes, with zero generated-token decode calls and absent decode-call time.
Available native durations remain API-wall nanoseconds, including possible real
zero; unavailable timers are absent, not zero-filled. Source time UNAVAILABLE or
DISCONTINUOUS stays explicit. Cached FINAL queries must preserve all fields,
sequence and time/absence; no query timestamp is treated as fresh measurement.
The probe then issues one unload and observes UNLOADED through the original
service; it does not assert native-wrapper destruction or memory reclamation.

The receipt contains only technical enums, masks/counts and actually available
native durations. It states ANDROID_EMULATOR / NOT_QUALIFIED and synthetic
admission. No output, raw request/invocation identity, path or raw exception text
is printed. A failure in this optional mode is sanitized. No token/s, TTFT, total
prefill/decode compute, native completion barrier or physical provenance is claimed.

At author handoff this is source preparation only: no Gradle, Docker or ADB was
executed by the author. Root owns canonical build/R8/Android validation and its
source/artifact/log evidence. Production qualified plans and F4#64/F5#89 remain
pending; no F6 authority is granted.
