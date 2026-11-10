# ORION-F5-NATIVE-001 — Private CPU llama.cpp JNI boundary

OBJECTIVE: Build a pinned real llama.cpp CPU runtime behind a minimal RAII JNI
wrapper owned only by the future `:ai` service.

FILES ALLOWED: `ai/llama/**`, the existing Debug and R8 native-load qualification
probes, this issue. Root module registration belongs to the coordinating work
package. The probes must accept the packaged C++ runtime name `libc++_shared.so`
while retaining exact target-APK entry loading and the bounded filename guard.

CONTRACTS INVOLVED: Architecture Cortex ownership, JNI, cancel/load/decode/unload,
Master Plan §§11.5, 11.7–11.8; existing NDK/CMake toolchain.

INVARIANTS INVOLVED: INV-001, INV-002, INV-009, INV-011, INV-013, INV-027, INV-033.

TESTS REQUIRED: CPU compilation for arm64-v8a/x86_64, ELF alignment and packaged
artifacts, load/cancel/generate/unload lifecycle and corrupt model handling, release
JNI symbol/keep behavior; physical inference remains a separate required gate.

ACCEPTANCE CRITERIA: Immutable source archive with SHA256 verification, no runtime
network dependency, bounded input/output, no raw pointer outside wrapper, one
native operation at a time, concurrent best-effort cancel, no prompt/model logs,
native exceptions translated, explicit unload and R8/JNI rules.

OUT OF SCOPE: Governor approval/calibration, model registry/import, service/IPC,
prompt policy, automatic process restart, AI/voice activation and phase closure.

## Implementation

- Immutable upstream commit/archive/SHA256 and packaged MIT license; actual CPU
  CMake integration, NDK r29/CMake 4.0.2, arm64-v8a/x86_64, 16KiB linker alignment.
- RAII model/context/sampler, private opaque handle, one active native runtime and
  operation, safe concurrent close/cancel, fixed exception diagnostics and suppressed
  upstream content logs. No native-created thread invokes Java.
- Actual GGUF metadata inspection, decoder tensor loading, tokenization, prefill,
  sampling, streaming bytes and cancellation; KV cache cleared between requests.
- Release JNI keep rules and full native symbols; independent static/runtime
  qualification remains necessary. No production resource plan was introduced.
- Four Android boundary cases plus three opt-in official-model cases cover actual
  load/stream/unload, decode cancellation/reuse and concurrent load cancellation.
  The official model, license, source revision and LFS SHA256 are pinned as a
  test-only manifest. Optional cases use synthetic emulator-only governor fixtures.

Validation is consolidated in the F5 Runtime Gate linked below.

## Consolidated validation

Canonical checks, scoped emulator results and retained failed attempts are recorded
in [F5 Runtime Gate](../qualification/F5-RuntimeGate.md) and its
[evidence record](../qualification/f5-runtime-evidence.json). That report supersedes
implementation-time validation-pending notes without changing this work package's
scope. Final review corrections/reruns, physical qualification and production
resource profiles retain the explicit status recorded there; no per-issue test
count or phase closure is inferred.
