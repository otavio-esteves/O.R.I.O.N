# F6 hybrid orchestrator — software validation, 2026-11-18

Status: **SOFTWARE COMPLETE — 8/8 F6 software criteria verified**.
This is the software scope authorized by the user, including real-model emulator
execution; it does not close the deferred qualification gates.
Physical qualification and production Cortex readiness remain OPEN under ADR-0004.

## Scope and contracts

User request: “tenta finalizar f6 (de ponta a ponta ignorando aparelho físico)”.
Authority: Architecture V3.2 §§15–18, 58–61, 76; Master Plan V1.2 §§12.1–12.7;
[ADR-0004](../adr/ADR-0004-f6-development-before-physical-qualification.md);
[work package](../issues/ORION-F6-HYBRID-001.md).

`core/context` selects whole context blocks in the seven normative priorities.
Required policy/request/skill/safety blocks must fit with reserved output and full
chat/transport framing. Optional history is bounded before selection and can be
omitted whole. Unknown token counters fail closed. The one pinned Qwen2 byte-BPE
model uses a conservative UTF-8 byte upper bound plus transport newline/BOS/EOS,
not character truncation or a generic tokens-per-character guess. Native actual
context guards remain authoritative too. Model/profile configuration is a candidate,
never a governor or restart permit.

`OrionIntentRouter` produces deterministic task/battery intents and a cognitive
OrionIntent for unmatched final UI input. The hybrid controller consults Cortex
only on the latter path. The closed structured schema allows messages and exactly
`task.list`, `device.battery`, `task.create`. Duplicate/unknown fields, malformed
Unicode, excessive size/depth, trailing text, free-form output, unknown tools and
model-provided authorization/IDs/confirmation are rejected. There is no automatic
repair or tool retry. Partial streaming text is display-only and discarded on a
failed terminal; no partial tool is dispatched.

`feature/chat` provides input, streaming, Cortex state, cancellation and explicit
safe inference retry. A model task is only a proposal. A separate human Prepare
button enters the existing TaskGateway/Ingress/Policy/ActionRequest pipeline;
confirmation still uses the exact persisted action/version and current presence,
capabilities and permissions. Cancellation applies only to inference. An uncertain
durable admission cannot become an inference retry. No new execution authority,
IPC semantics, exported component, permission or scheduling path was introduced.

## Verification

Full check on functional source `fb323ce83c4e8bd31389217c18a59f70c5818e8d`;
subsequent focused checks cover prompt clarification, post-death reads and the
test-only bounded emulator plan. The inventory below reflects those completed checks:

| Check | Environment | Result |
| --- | --- | --- |
| `check assembleDebug assembleRelease :app:assembleDebugAndroidTest --max-workers=2` | Docker | PASS, 12m26s, 4475 tasks |
| Active-module Debug JVM inventory | Docker | 1127 cases / 140 suites / 53 modules; zero failures/errors/skips |
| New F6 unit coverage | Docker | 23 cases: budget 4, structured parser 4, controller 11, adapter 4 |
| `host-harness-check` | Docker | PASS, 629 cases |
| Chat, navigation, F5 diagnostics/platform diagnostics | Android emulator API35 x86_64 / 4096-byte pages | PASS, 10 cases, 42.115s |
| Optimized native chat / cancel / retry / exact confirmation / AI death | Android emulator | PASS, 2 cases, 485.337s, no skips |
| Final R8 qualification build and native package verification | Docker | PASS, 2m32s, 1808 tasks |

The JVM inventory includes only Gradle modules currently declared in settings;
old XML from inactive modules is excluded. The full check includes lint, dependency
boundaries and static native APK/AAB packaging. These are not physical/native
runtime qualification. Final runtime source is
`ba6d0f9a0cf30d194cdcc04f28fe97089e733dd5`; APK hashes are recorded in the
[machine-readable evidence](f6-hybrid-software-evidence-20261118.json).

The optimized chat probe is selected explicitly with:

```sh
docker compose -p orion run --rm --no-deps android-build \
  -PorionQualificationRelease=true -PorionHybridChatQualification=true \
  :app:verifyQualificationNativePackage :app:assembleQualificationReleaseAndroidTest \
  --max-workers=2
```

It has the separate `network.orion.qualification` identity, Release native code
and R8. Additional test entry retention rules apply only to this opt-in probe;
R8 remains enabled; the separate test APK requires retained shared dependency APIs
(and stable directly tested Core APIs). Those probe APIs are not optimized away;
other app code remains eligible for optimization/obfuscation. Ordinary Debug/Release and ordinary
qualification probes do not receive these rules or production inference profiles.
The emulator test uses the pinned 491400032-byte Qwen GGUF and explicitly synthetic
resource observations through the real minimum governor/restart guard and existing
Cortex client. It does not qualify those observation values or budgets for a device.

The runtime invocation is explicit; without `orionModelPath` the real-model test
is not evidence (it is skipped). The final probe must report both tests passed,
not merely an ADB process exit code:

```sh
adb -s emulator-5584 shell am instrument -w -r \
  -e class network.orion.app.chat.OrionChatRealModelTest,network.orion.app.chat.OrionChatScreenTest \
  -e orionModelPath /data/user/0/network.orion.qualification/files/orion_f6_fixture_20261118.gguf \
  -e orionEnvironment ANDROID_EMULATOR \
  network.orion.qualification.test/androidx.test.runner.AndroidJUnitRunner
```

The fixture is the repository-pinned Qwen2.5-0.5B-Instruct Q4_K_M, revision
`9217f5db79a29953eb74d5343926648285ec7e67`, 491400032 bytes, SHA-256
`74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`.
It is not bundled with the APK or evidence archive. ADB root was used only to copy
the fixture into private storage on the task-owned AVD; test execution uses the
ordinary package UID and ADB shell UID 2000. The test imports and verifies the real
model through the existing importer/private-FD path. Its test-only resource plan
uses context 1024, output reserve 128, four threads and no keep-alive. The unchanged
inference deadline is 120s. These are emulator calibration values, not a production
profile or a hardware benchmark.

## Software gate evidence

| Master Plan §12.7 criterion | Evidence | Result |
| --- | --- | --- |
| Fast Paths without Cortex | Controller tests assert zero Cortex configuration/generation calls; real UI battery/task queries with no production profile | PASS |
| Simple natural language routed | Real Qwen interprets “Crie uma tarefa com o título Comprar pão.” and proposes exactly “Comprar pão” | PASS |
| Cortex only when needed | Deterministic route tests; post-death task query does not add a sixth inference | PASS |
| Structured output validated | Strict parser, static descriptors, normalization, bounded context and real valid message/tool output | PASS |
| Policy outside LLM authority | Model proposal creates no task; real Prepare and exact human Confirm are required by the existing Core | PASS |
| Invalid tool call executes nothing | Malformed/privilege-injected/partial output tests assert zero create, confirm and read dispatches | PASS |
| Cortex death degrades without killing Core | Actual same-UID `:ai` PID killed during a native delta; same Main PID survives; exact committed task UUID/title remains readable | PASS |
| Local chat functional | Real Compose message, streaming, cancel, explicit fresh inference retry and normal unavailable-profile fallback | PASS |

The final real-model probe observed **43 native text deltas, five fresh request IDs
and ten governor approvals**. After task confirmation it killed `:ai`, verified
`PROCESS_DIED` degradation, preserved the Main PID and read the exact persisted
task both through the Core and through the deterministic chat route. No tool was
reconstructed from partial output. The normal-profile UI case passed immediately
afterward in the same instrumentation invocation.

## Recorded attempts

- Initial UI attempt: System UI ANR during fresh emulator startup prevented window
  focus; Core nevertheless reached DEGRADED_SAFE. Not a positive runtime result.
- Second UI attempt: the test incorrectly waited for Send to become enabled after
  clearing its input. The assertion was corrected to observe actual chat readiness;
  the product still disables empty submissions.
- Native Debug attempt: existing 120s deadline expired during scalar Debug prefill,
  before the first delta. Not a positive inference result. Deadline and native
  contract were preserved; optimized qualification code is used for the next probe.

- Initial optimized probe build: main R8 APK/native packaging passed, but test
  packaging failed on a JDK type referenced by compiler meta-annotations. The test
  Kotlin root also needed explicit selection; no runtime evidence was claimed.
- Second optimized probe build: Kotlin tests compiled, but entirely excluding the
  annotation artifact left three CLASS-retention annotations unresolved. R8 correctly
  rejected that classpath. The final fix provides the pinned annotations as
  `androidTestCompileOnly`, excluding them only from test runtime packaging. No
  `dontwarn` or missing-class check suppression was added.

The Android/JDK meta-annotation incompatibility is also documented in the
[upstream Error Prone issue](https://github.com/google/error-prone/issues/2122).
The local dependency bytecode was inspected to verify CLASS retention before
choosing compile-only placement. This affects the opt-in test harness only.

The first built R8 chat probe then failed before test discovery because the target
had optimized away `androidx.tracing.Trace`, while AGP omitted the shared dependency
from test dex. The opt-in probe now retains shared AndroidX/Kotlin/coroutines and
direct test entry APIs for cross-APK linkage. The normal app and ordinary R8 probe
receive none of those retention rules. No test execution is credited for the
interrupted runner, and its crash log is preserved.

Subsequent probe attempts exposed a second cross-APK Kotlin ABI mismatch; the
opt-in probe retains shared dependency names as well as APIs. The first functioning
native run reached five requests, ten governor decisions, exact task confirmation
and actual AI process death with the Main PID and exact persisted task surviving,
but its final UI read assertion failed. This partial result is not a full PASS.
A later native request expired under the unchanged 120s deadline; its partial
output was discarded. The explicitly synthetic emulator plan was then reduced
from context 4096 to 1024 and set to four threads for four emulator vCPUs. A unit
test proves mandatory prompt plus output reserve still fits this cap; optional
history is omitted whole. No production profile or native deadline was changed.
The next run exposed UI send admission failures. The probe now observes each new
user turn before completion and exercises the actual Send button through its
accessibility action to avoid IME viewport movement during pointer injection.
Two subsequent short probes corrected test assertions about the TextField label
and lazy-list node lifetime. The final short UI probe passed (14.417s), followed
by the complete two-test real-model invocation above. Earlier failed executions
remain failures in the archive.

## Recovery, privacy and upgrade

Chat history/drafts are ephemeral, bounded to 20 turns and cleared with the Activity.
No new conversation persistence, memory promotion, FTS or Room schema was added.
Durable task state retains existing encryption, idempotency, recovery/boot leases
and exact confirmation binding. MAIN remains the sole database owner. AI death
cannot reconstruct or execute an action from partial text. Product diagnostics and
probe checkpoints do not include prompts, generated text or task payloads. Test
failure assertions may contain synthetic fixture text; no real user conversation
was used. Migration is not applicable:
Room remains version 6; existing recovery/upgrade contracts are unchanged.

Preserved invariants: INV-001–009, INV-011–014, INV-018–019, INV-021–025,
INV-027–028, INV-031, INV-033, INV-035–036. No exemption beyond ADR-0004's
physical qualification sequencing is inferred.

## Evidence and cleanup

The [verified archive](artifacts/f6-hybrid-software-evidence-20261118.tar.gz)
contains 203 files (316843 bytes), with SHA-256
`78d6a323216a55b9617bad63da870151e33579ec1f33c5d4ffe9f36de94b32bd`.
It preserves build/runtime logs, all 140 active JVM XML suites, environment/model
metadata, installation hash comparisons, cleanup evidence and the 1100-file
source manifest at the functional source commit. Every archived member was
verified against its SHA-256/size manifest. Host home prefixes in logs are
sanitized; no APK, GGUF or private key is included. The final documentation commit
is separate from the functional source manifest.

The task-owned `OrionF6Hybrid20261118` emulator was stopped and its private
`/dev/shm` AVD directory removed after checking its exact name. Existing user AVDs
were untouched. ADB was unrooted before shutdown.

## Remaining qualification boundary

Normal product configuration still contains no approved model/resource profile;
its cognitive path reports unavailable while Fast Paths remain useful. Positive
emulator fixture evidence must not be substituted for production profile approval,
S21/API37/MemoryLimiter/native runtime matrix, 24h/stress evidence or
CORTEX_RUNTIME_READY. F7 intelligent memory and voice are outside this work package.
No push, remote CI, PR merge, stable release or cognitive milestone tag is claimed.

## Subsequent audit follow-up

The [F6 audit follow-up](F6-AuditFollowup-20261118.md) records fixes for notification
dismissal starvation, MAIN-thread Cortex negotiation and reversed selected history,
with RED/GREEN evidence and fresh scoped Docker/emulator/R8 validation. Its source
and artifacts are separate from the historical evidence above. F7 planning is a
separate delivery; production/physical/stability qualification remains open.
