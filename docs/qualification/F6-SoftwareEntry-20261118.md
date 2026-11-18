# F5 diagnostic corrections and first F6 software increment — 2026-11-18

Functional source: `89a6baa829148a86a417d9ed7277676c10df07a1`.
Diagnostic fix: `ee421d4`; sequencing decision: `36bb915` / ADR-0004.
Branch: `feat/f6-deterministic-intent-router`, stacked on
`fix/f5-diagnostics-observation`. This delivery's later evidence/documentation
changes do not change the tested functional source. No remote CI, PR or merge is
claimed; required CI remains a precondition to merge.

## Normative scope and corrected source discovery

[Architecture V3.2](../../1-orion.md) and
[Master Plan V1.2](../../2-plano-mestre.md) were already tracked at the repository
root. The earlier filename-based search missed their generic filenames; there is
no missing-baseline finding. The final routes follow Architecture sections 15–18
and the examples in Master Plan section 12.1. Neither baseline file was changed.

[Accepted ADR-0004](../adr/ADR-0004-f6-development-before-physical-qualification.md)
records the user's explicit instruction to begin bounded F6 software development
with physical gates deferred. It does not declare F5 qualified, production
profiles approved, CORTEX_RUNTIME_READY or the F6 cognitive MVP complete.

## Changes

- Diagnostic reads isolate ordinary scheduling/queue failures independently,
  replace stale values with missing observations, and preserve cancellation and
  interruption. No exception text or user payload is logged.
- Only the selected, RESUMED diagnostics screen observes serially, immediately
  on resume/settings/recovery and then every two seconds. Cancellation prevents
  publication after a suspended read. Observation never drains ingress, executes
  an action, changes readiness or opens the Startup Barrier.
- The pure Intent Router recognizes `minhas tarefas`, `listar tarefas`,
  `ver tarefas`, `bateria`, `consultar bateria`, and `criar tarefa: <title>`.
  It returns existing GetTasks/GetDeviceInformation queries or an existing-schema
  ValidatedCreateTask proposal inside OrionIntent. It dispatches none of them.
- Raw UTF-8/Unicode/control bounds, literal title preservation, the existing raw
  title limit/NFC normalizer, supplied identity/correlation, untrusted/nonfinal
  input rejection and redacted diagnostics are tested. Unknown final user text
  is a cognitive candidate only; it never binds, loads or invokes Cortex.

No Room schema, migration, IPC/process, Policy Engine, action/confirmation,
recovery or scheduling-authority contract changed. All state added here is
ephemeral. Process recreation reconstructs observations; interpretation loss
cannot replay an effect. Existing ingress/idempotency/confirmation gates remain
required before a future adapter submits a mutable proposal.

## Validation

| Environment | Result | Scope |
| --- | --- | --- |
| CONTAINER | PASS | Canonical check/lint/module/native ELF/ABI/16 KiB packaging; Debug/Release APK, release AAB and four instrumentation APK selections. 4,404 tasks: 182 executed, 6 from cache, 4,216 up-to-date. |
| CONTAINER | 1,104 JVM cases / 136 suites | Active module reports only; zero failures/errors/skips. Gradle may reuse valid results. Includes 8 new diagnostic and 15 new router cases; focused/full invocations overlap and are not summed. |
| CONTAINER | 629 HOST harness cases; 36 boundary fixtures | Zero failures. The HOST harness contracts run without a device in the pinned Python container. |
| CONTAINER | PASS | Base qualificationRelease R8 build, mapping check, native packaging and instrumentation APK; 1,736 tasks, 42 executed. Other opt-in qualification modes were not rerun. |
| ANDROID_EMULATOR | 3 PASS / 10.6s | Final Debug APK pair: read-failure containment, pause/resume/settings/poll refresh without new recovery, and the existing diagnostics-screen check. No skips. |
| ANDROID_EMULATOR | 3 PASS | Final non-debuggable R8 pair: all four packaged native libraries load, AI constructor/native rejection of a corrupt synthetic model succeeds, and healthy Core database startup is observed through the diagnostics UI. No inference/model performance claim. |

Runtime environment was observed as API35 / x86_64 / 4 KiB / qemu=1. Both final
APK pairs were installed on an owned fresh emulator; all four local SHA256 values
matched their actually installed base APKs before execution. The emulator and its
owned data were removed after verification. Original AVDs were preserved.

Executed canonical commands:

```bash
docker compose -p orion run --rm --no-deps android-build check assembleDebug assembleRelease :app:assembleDebugAndroidTest :core:security:assembleDebugAndroidTest :ai:llama:assembleDebugAndroidTest :ai:service:assembleDebugAndroidTest --max-workers=2
docker compose -p orion run --rm host-harness-check
docker compose -p orion run --rm --no-deps android-build -PorionQualificationRelease=true :app:verifyQualificationNativePackage :app:assembleQualificationReleaseAndroidTest --max-workers=2
```

The [evidence index](f6-software-entry-evidence-20261118.json) binds installed
artifact hashes, the 1,080-file functional Git source manifest, current active JVM
reports, exact logs and their [archive](artifacts/f6-software-entry-evidence-20261118.tar.gz).
All 155 archive members were checked against their retained bytes/hash manifest.
No APK, model weights, signing key or emulator disk is included. Marker checks
found no raw host-home path or private-key PEM header in the retained payloads;
these checks are bounded checks, not a general attestation of provenance/privacy.

Retained attempts include the initial test-nullability compilation failure and
successful correction, the earlier overlapping diagnostic/router runs, and the
first integrated build explicitly stopped with exit 143 when the normative source
files were located and the final aliases were added. Two earlier AVD setup
attempts were rejected for disk space before any test; their raw host-path logs
remain local. No failed/superseded attempt is relabeled as a passing final run.

### Report-inventory correction

The earlier chat audit's raw 1,134/137 count included 53 cases in three stale XML
reports from inactive `ai/cortex` (30) and `data/modelregistry` (9+14). Those modules
are absent from settings.gradle.kts and their reports are preserved but excluded.
That earlier active count was 1,081/134. Adding the 23 new cases/two suites yields
the current 1,104/136, with reports and hashes retained in the archive. Historical
counts are not silently rewritten, reused as new execution or added together.

## F6 remains incremental

| Master Plan section | Current delivery |
| --- | --- |
| 12.1 Fast Path | First deterministic interpretations implemented; live gateway/orchestrator dispatch is still pending. |
| 12.2 Intent Router | Initial bounded typed OrionIntent producer implemented and tested. |
| 12.3 Cognitive Path | Pending Context Builder → fresh ResourceGovernor → Cortex → validation → policy integration. |
| 12.4 Structured Tool Calling | Pending closed parser/schema/normalization/semantics/descriptor/policy pipeline. |
| 12.5 Context Budget | Pending the specified priority and token-budget implementation. |
| 12.6 Chat | Pending text input, stream, cancel, safe inference retry and Cortex state UI. |
| 12.7 Cognitive MVP | OPEN; no cognitive tag or phase-completion claim. |

Physical S21/profile, actual API37 MemoryLimiter, the required native/API matrix,
F4/#64 stability and post-Cortex/#89 real-duration stress/soak remain deferred and
OPEN. Production trusted profiles remain absent. Software consistency, R8/emulator
success and this sequencing ADR do not supply those physical/readiness approvals.
