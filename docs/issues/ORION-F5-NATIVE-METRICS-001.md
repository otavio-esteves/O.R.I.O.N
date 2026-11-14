# ORION-F5-NATIVE-METRICS-001 — native-local generation measurements

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/111
Source base: d2b784e67f0645df3257d890be128ad9771ba99d.

OBJECTIVE: Add the smallest optional, content-free per-invocation measurement
source to the existing AI-owned JNI runtime. Actual tokenizer/sample/evaluation
counts and local monotonic work durations must not become IPC, policy or profile
authority, nor change default generation/cancellation/exception behavior.

FILES ALLOWED: ai/llama/src/main/cpp/orion_llama_jni.cpp;
ai/llama/src/main/kotlin/network/orion/ai/llama/OrionLlamaRuntime.kt and one related
internal observation type; focused ai/llama tests; this work package and
docs/qualification/F5-NativeLocalMetrics.md; root additionally authorized the
related ai/llama/build.gradle.kts junit4 test dependency. No vendor, service, AIDL,
client, profile, scheduler, shared build/CI wiring or root checkout edits.

CONTRACTS INVOLVED: Architecture Cortex ownership, native lifetime and metrics;
Master Plan F0/F5; current shared native ownership/operation exclusion, JNI
exception translation, synchronous piece callback and cooperative cancellation.

INVARIANTS INVOLVED: INV-002/009/011/013/027/028/032/033.

TESTS REQUIRED: Tokenizer counts versus callback bytes/pieces; EOG; maxTokens=1;
callback cancellation, concurrent cancellation/close, thrown Java callback/native
failure; separate evaluated-success versus submitted/unknown partial work;
per-call reset/no stale measurement and strict fixed-size/type/count validation;
default finish/callback behavior unchanged. Root owns all canonical compilation,
native/R8/packaging and actual optional model tests; missing fixtures stay skipped.

ACCEPTANCE CRITERIA: One existing runtime/invocation, optional fixed local storage,
actual token counts, explicit partial/unavailable states, monotonic durations only
with precise scopes, no content/paths/logging or raw native timestamps. JNI guards,
RAII, cancellation branches and original finish return semantics remain intact.
Required CI must pass before merge. No physical/profile/gate outcome is asserted.

OUT OF SCOPE: IPC export (separate ADR), heavy runner, calibration, model/profile
selection, native vendor modifications, second runtime, physical/API/native gate,
performance claims from emulator or real-duration soak.

DESIGN STATUS: Root and operational peer reviewed the design before source
implementation. Names use callbacksAttempted/callbacksReturnedNormally, including
false, without acceptance/delivery claims. Existing pending exceptions always skip
publication untouched. Only a new diagnostic query/copy exception after a clean
precheck may be cleared. A private PUBLISHED bit confirms a full copy; no bit means
discard every array slot while preserving finish. Counter/duration definitions,
partial-work limitations and API-return synchronization scope are specified in
[the technical note](../qualification/F5-NativeLocalMetrics.md).

VALIDATION: Fifteen JVM tests and six new Android cases supplied; not yet executed
by the author. Model-dependent cases preserve explicit fixture skip behavior.
Lightweight diff whitespace, local Markdown links and private native/Kotlin
array-size/publication-bit consistency checks passed. Root centralizes all
Gradle/Docker/native/R8/device execution. No physical/profile/gate outcome.
