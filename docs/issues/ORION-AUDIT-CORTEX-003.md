# ORION-AUDIT-CORTEX-003 — Cortex payload identity and pre-I/O deadlines

OBJECTIVE: Reject changed payloads for a retained transport request ID and stop an
expired/cancelled MAIN request before model recovery, verification or further I/O.

FILES ALLOWED: ai/service request identity and service source/tests; app/ai client,
owner, request ledger and focused tests; data/repository/models import/file adapters
and their focused tests; related Binder/model Android tests; this work package.

CONTRACTS INVOLVED: accepted ADR-0002; Architecture §§25–30, §§33–35; existing private
Cortex 1.1 wire, issued/legacy histories, cancellation tombstones, model registry,
OrionClock/BootSessionId. INVALID_REQUEST remains the conflict response; no AIDL,
capability, protocol version, process topology, schema or production profile changes.

INVARIANTS INVOLVED: INV-009, INV-011, INV-012, INV-013, INV-019, INV-027, INV-028,
INV-033, INV-036. Fingerprints and tombstones are bounded ephemeral mechanics only.

TESTS REQUIRED: real Binder same/changed load and inference payloads, operation,
model, input, parameters and boot/deadline binding; cancellation and retired replay;
canonical fingerprint field separation; early expired/invalid-clock owner requests
without database/inventory/Binder access; retained results/conflicts before I/O;
cooperative interruption during file hash/parse and pending recovery without false
quarantine, READY promotion or descriptor leakage. Focused JVM/Android tests then
canonical check/lint/dependencies/Debug/Release/native/R8 checks.

ACCEPTANCE CRITERIA: a changed payload cannot receive an unrelated retained result
or overwrite/execute the original; exact replays and cancellation remain bounded
and idempotent. Expired work performs no model recovery, and cancellation/expiry
is checked between bounded reads and before registry/file mutations. Interrupted
validation remains recoverable; unavailable keys, production profiles and physical
gates remain unchanged.

OUT OF SCOPE: Action Outbox/business effects, scheduling, schema/migrations, voice,
physical MemoryLimiter/performance/soak qualification, production activation,
publishing, and the 19 pre-existing resource/retention working-tree changes.

## Validation — 2026-11-10

The validated working tree includes the 19 pre-existing resource/retention edits,
which were preserved byte-for-byte. Base commit: `f50650b`; branch:
`fix/cortex-request-validation-20261110`. No commits or remote publication were made.

CONTAINER:
- Focused `:ai:service:testDebugUnitTest :app:testDebugUnitTest
  :data:repository:testDebugUnitTest`: 176 cases passed.
- Canonical `check assembleDebug assembleRelease :app:assembleDebugAndroidTest
  :core:security:assembleDebugAndroidTest :ai:llama:assembleDebugAndroidTest
  :ai:service:assembleDebugAndroidTest --max-workers=2`: passed. Current aggregate
  reports: 798 JVM cases in 108 suites, zero failures/errors/skips; unchanged test
  tasks may reuse valid Gradle results. Lint, dependency/module checks, Debug/Release,
  APK/AAB, native ABI/ELF and 16KiB packaging checks passed.
- `docker compose run --rm host-harness-check`: 127 passed.
- `-PorionQualificationRelease=true :app:verifyQualificationNativePackage
  :app:assembleQualificationReleaseAndroidTest --max-workers=2`: passed with R8.

ANDROID_EMULATOR (API35, x86_64, 4096-byte pages, 4096MiB/four vCPUs):
- Real Binder boundary, early owner gates and model-file/registry tests: 26 passed.
- Existing MAIN client and queued-deadline regressions: 15 passed.
- Non-debuggable R8 probe: all packaged libraries load, AI constructor/native corrupt
  model rejection, and Core startup with healthy storage: 3 passed.
- APK SHA256 values were compared against the actually installed target/test pairs
  before instrumentation. No real-model inference or physical performance is claimed.

The canonical stream client exited 143 while its container continued. The final
container log and `docker wait` confirmed `BUILD SUCCESSFUL` and exit 0; no duplicate
build was started. Two R8 visual attempts were inconclusive behind System UI/Pixel
Launcher ANR dialogs. Their logs/UI captures were retained. After closing only those
platform dialogs, the unchanged installed APK hashes passed all three R8 probes.
No assertion was disabled and no failed attempt was relabelled as a pass.

Local ignored evidence: `build/cortex-fixes-20261110/` (source manifest, exact commands,
complete logs, installed hashes and retained platform failures).

## Recovery, security and upgrade review

Fingerprints stay inside the existing bounded ephemeral issued/legacy histories;
no prompt content is added to persistence or logs. Changed payloads cannot replace
or execute an existing request. Cancellation tombstones bind their first valid
submission without execution, while exact retained replays do not renew deadlines.

MAIN checks the same logical fingerprint/history before optional I/O. Expired or
boot-invalid work returns its terminal outcome without database/inventory/Cortex
binding. Job cancellation and expiry are checked between bounded hash/parser reads,
before cleanup, and after acquiring SQLite write ownership. A partially completed
validation may remain VALIDATING for existing recovery; cancellation is never proof
of corruption and cannot silently quarantine/promote the model. Open descriptors
close on interruption; OS process death discards ephemeral scopes and does not replay
native inference or create actions from partial output.

Room schema/migrations, process roles, exported components, permissions, AIDL,
protocol 1.1/capabilities, policy/ResourceGovernor authority, and production profile
gates are unchanged. F5/S21, actual MemoryLimiter, real-model performance and soak
qualification remain open. The disposable AVD is stopped and removed after validation.
