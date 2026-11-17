# F5 remaining qualification — current operational checklist

## Current offline software closeout — 2026-11-17 UTC

**The reviewed offline F5 implementation and local validation are complete. F5 and F4 physical stability gates remain OPEN; F6 entry is NOT AUTHORIZED.** The independent audit's original P2 is fixed and no new demonstrable P1/P2 remains in its reviewed scope.

Sampler/encoder #151/#153, HOST assembly #158, capture composition #159 and finite instrumented workload #160 are integrated through PRs #156/#157/#162/#164/#166. Paired exit/original-policy acquisition and the finite manual Core path #163, together with the build-specific limiter acquisition procedure #161, are integrated through PR #167. PR #167 merged `fdf3d8c7949a14ccde6e506c089650547c2f26b4` at 2026-11-17T03:03:20Z after exact-head `d537b7277b4f2dc8ad571416a213373e82477990` [CI 44002282709](https://github.com/otavio-esteves/O.R.I.O.N/actions/runs/44002282709/job/133769025849) succeeded at 2026-11-17T03:02:46Z.

This revision delivers #165's final selected-package passive monitoring correction and evidence/checklist reconciliation. Acceptance requires its own exact-head required CI to pass before merge; that live integration record is on the PR closing [#165](https://github.com/otavio-esteves/O.R.I.O.N/issues/165). Historical PENDING fields in the immutable evidence packages are timestamped snapshots, not a claim that this revision's CI has already passed or that delivered source is still missing.

Validation remains scoped rather than additive: [source/manual evidence](F5-ExitPolicyManualValidation-20261116.md) records 383 JVM cases/46 suites and 118 Android START/OK, zero skips, plus two actual R8 OPEN controls; [monitoring evidence](F5-QualifiedMonitoringValidation-20261117.md) records 629 HOST cases and short actual target-binding mechanics. Earlier failed invocations remain retained. Production code stayed unchanged by the Android fixture correction. No positive configured qualification factory, model admission or actual paired limiter incident is inferred from the no-profile controls.

| Remaining qualification | Required evidence |
| --- | --- |
| Galaxy S21 8GB and reviewed profiles | Actual lifecycle, memory/headroom, performance, thermal/battery and storage observations, followed by model/context/thread/budget review. |
| Physical API37 MemoryLimiter | Execute the prepared build-specific owned stimulus/correlation/policy-transition and restoration procedure; preserve unsupported/missing/UNKNOWN results. |
| Native/API hardware matrix | Complete the declared physical/native/API cells with the frozen and actually installed artifacts. |
| Core stability #64 and post-Cortex #89 | At least 24 real elapsed hours of approved workload plus memory stress, with visible gaps, backlog/UNKNOWN, retention, growth, reminder, exit and recovery review. |

Production profiles/template remain absent, inference remains disabled and CORTEX_RUNTIME_READY is not declared. RuntimeEvidence v1's unavailable native phase/token fields and source-authentication limits remain explicit. Execute the prepared paths only after allocation and calibration; collection completion, brief emulator controls and cleanup do not qualify a soak.

The sections below preserve earlier preparation and execution snapshots, including their then-pending integration wording. This current closeout governs the delivered offline scope; no historical count, source identity or failure is relabeled.


## Historical preparation snapshot — 2026-11-17, 02:22:16 UTC

**F5 and F4 stability gates remain OPEN; F6 entry remains NOT AUTHORIZED.** Production configuration is still absent; no approved model/resource profile, production inference or CORTEX_RUNTIME_READY is declared. This dated section supersedes earlier software-gap/status statements below without replacing their historical source hashes, failures or test totals.

| Delivery | Integration status at this snapshot | Evidence and remaining boundary |
| --- | --- | --- |
| #151 sampler / PR #156; #153 encoder / PR #157 | INTEGRATED | Bounded seven-phase memory acquisition and rendering exist; their mechanical controls are not physical calibration or authenticated source proof. |
| #158 HOST assembly / PR #162; #159 qualification composition / PR #164 | INTEGRATED | The source-bound RuntimeEvidence v1 assembly and fixed qualified capture runner exist. Missing source/configuration remains explicit; the empty composition refuses heavy work. |
| #160 finite instrumented workload / PR #166 | INTEGRATED: merged `ee0b5d8b429dcbac2f57f13273f18b416e05d9eb` at 2026-11-17T02:07:49Z | Required [CI run 43995351105](https://github.com/otavio-esteves/O.R.I.O.N/actions/runs/43995351105), exact head `ad259082e97b55a44c42a347a3d84cca18b37708`, succeeded before merge. This is software integration, not a calibrated heavy run or soak. |
| #163 paired exit/original-policy source and finite manual Core requests; #161 limiter acquisition procedure | LOCALLY VALIDATED; [PR #167](https://github.com/otavio-esteves/O.R.I.O.N/pull/167) published, required CI/integration pending | Original Main/Owner authority, instance-safe death settlement, explicit new requests, no UNKNOWN replay and a bounded target-build/owned-cleanup procedure are prepared. No real limiter incident, admitted model or physical profile is supplied. |
| #165 selected-package passive monitoring | LOCALLY VALIDATED; integration/required CI for this revision not asserted | Collector source `4c987d761c875a4259c113098724f4d569f8d21d` passed its scoped 629-case HOST invocation and short actual qualified-package mechanics. [Scoped evidence](F5-QualifiedMonitoringValidation-20261117.md). |

PR #167 exact head `d537b7277b4f2dc8ad571416a213373e82477990` was OPEN/BLOCKED with [CI 44002282709](https://github.com/otavio-esteves/O.R.I.O.N/actions/runs/44002282709) IN_PROGRESS when read at 2026-11-17T02:22:16Z. This pending snapshot is separate from PR #166's successful merged state.

The #165 runtime used an owned API35/x86_64/4KiB emulator with both packages installed under distinct UIDs. It observed actual qualified Main PSS and three preexisting maintenance baseline events, **zero subsequent events**, and unavailable private storage without bypass. Resource collection remains INCOMPLETE; maintenance collection is REVIEW_REQUIRED, not growth/retention/autonomy qualification. The independent targeted audit resolved its one original P2 and found no new demonstrable P1/P2 in scope; it is not exhaustive execution or current-CI proof.

#163 evidence snapshots remain separate: 383 JVM cases/46 suites on functional source `5cb5246` (unchanged production code in documentary `3208ace`); 619 HOST cases before #165; the first Android selection at `3208ace` retained 117 PASS/1 FAIL out of 118 because its fake qualification namespace was correctly rejected by the existing Main ownership guard. Test-only `3d34e35` corrected that fixture and a new run passed 118 START/OK in 41.617s, zero skips, plus two R8 OPEN controls with the same Main identity. These are overlapping local snapshots, never a summed unique total. R8 still reports NO_QUALIFIED_PROFILE/owner-call false; no model, actual paired incident or real Core resource decision is claimed.

The historical inventories below describe their stated baselines. #130 remains a one-shot partial recorder and #142 remains an abstract/port-based controller in its own scope; they are supplemented by the concrete #159/#160/#163 paths, not retroactively relabeled. RuntimeEvidence v1 native token/prefill/decode timing stays UNAVAILABLE, source authentication remains unproven, trusted operating values remain absent, and physical S21/API37/native-matrix plus real 24-hour obligations remain open.

## Historical review baseline — 2026-11-16

Work package: [ORION-F5-QUAL-PLAN-001](../issues/ORION-F5-QUAL-PLAN-001.md).
Review renewed on 2026-11-16 against main
`dc130a0e0728e50cc2da99f24d79aa2c4be4668e`, including integrated PRs #143, #145,
#147, #149, #150 and #152 after the earlier #134/#136/#137/#138 closeout.
The [software evidence package #154](../issues/ORION-F5-RUNTIME-CAPTURE-SOFTWARE-EVIDENCE-001.md)
separates historical local validation from exact-head integration CI.
Historical lifecycle-facts source:
`89924f8ba0768c2ec4a5a7ee4891ac7a6e65d3b4`,
[PR #138](https://github.com/otavio-esteves/O.R.I.O.N/pull/138).
Lifecycle integration status: **INTEGRATED via PR #138; required remote CI SUCCESS; issue #132 CLOSED**.
Local execution records are separate from integration status. This document is a
plan, not a new execution or qualification approval record.

F5 remains **OPEN**. Production inference stays disabled and
`CORTEX_RUNTIME_READY` is not declared. Physical qualification is already tracked
by [#89](https://github.com/otavio-esteves/O.R.I.O.N/issues/89), with the work-package
contract in [ORION-F5-QUAL-001](../issues/ORION-F5-QUAL-001.md). Remaining Core
stability work stays under [#64](https://github.com/otavio-esteves/O.R.I.O.N/issues/64).
No replacement physical issue or waiver is needed.

Memory-specific source review, measurement boundaries and the proposed acquisition
protocol are in [F5-MemoryEvidence.md](F5-MemoryEvidence.md). They do not approve a
model/resource profile.

The opt-in unloaded baseline has a separate [HOST recorder procedure](F5-PhysicalBaselineRecorder.md),
including its exact model-free source manifest and non-qualifying results.

## Reconcile the source before starting work

The historical local branch ending at `f68ed6d` contained
`ai/cortex/OrionCortexClient.kt`, manual Binder framing and a lifetime 1,024-ID cap.
Its four observation-race regressions apply to that implementation. The reviewed
main no longer contains that module or those tests. Its client lives in
[app/ai/OrionCortexClient.kt](../../app/src/main/kotlin/network/orion/app/ai/OrionCortexClient.kt),
uses AIDL, an operation mutex and the inference turnstile. Do not restore the old
module or transplant its private-lock tests into the current architecture.

| Earlier proposed next step | Current main | Remaining action |
| --- | --- | --- |
| Resolve lifetime ID exhaustion | [Accepted ADR-0002](../adr/ADR-0002-bounded-cortex-request-history.md) defines issued IDs, bounded retained results and a retirement watermark; live work and retired replay protection are preserved | Use the current bounded-history regression and 33-cycle real-model evidence; do not remove caps or restart processes to clear history |
| Obtain current cross-process memory observations | MAIN-owned self-PSS aggregation and authenticated Cortex self-PSS telemetry are implemented; inventory completeness remains trusted MAIN authority | Validate the actual target's inventory, freshness, native accounting and measured limits before qualifying a profile |
| Complete runtime software validation | Historical [20261110 integrated closeout](Audit-20261110-Closeout.md) records 798 JVM reports, 127 host cases, 395 API37/16KiB application cases, real Qwen, actual process death and R8 probes; later bounded preparation is inventoried below | Freeze a new source/artifact identity and rerun affected checks after software changes; do not relabel historical results |
| Qualify hardware, native/API behavior and stability | Still open under #89/#64 | Obtain the actual targets and required uninterrupted windows; execute the remaining profile and review the evidence |

The [F5 runtime gate](F5-RuntimeGate.md) already maps every Master Plan §11.11
criterion to its scoped evidence. The [integrated evidence index](audit-20261110-evidence.json)
binds that historical source, installed artifacts, raw logs, model and platform identities.
Overlapping test selections are not additional unique cases. A discovery run with
`log=true` is not execution evidence.

The [20261115 audit index](audit-20261115-evidence.json) carries an explicit JVM
inventory correction. Its original raw collection contains 990 cases in 124 XML
suites, including three legacy reports outside the Git-tracked module inventory:
23 cases from `data/modelregistry` (2026-11-08) and 30 from `ai/cortex` (2026-11-14).
The active audit B total is **937 cases in 121 suites**, with zero failures,
errors or skips. The original tar, manifest, logs and raw totals remain intact;
the external index records the exclusion method and exact archived report hashes.
The later F full check has **952 cases in 122 active suites**, adding only the
15-case lifecycle-facts suite. This separate run does not replace or add again
the valid earlier F scoped 264-case snapshot. Correcting report inventory is not
a new execution, CI approval or qualification result.

The reviewed lifecycle source's [scoped local evidence](f5-lifecycle-facts-evidence-20261115.json)
records 264 JVM cases/35 suites, including 15 new lifecycle-fact cases, and 51
API35/x86_64/4KiB Android cases (10 cycle plus 41 Client/Owner/Queued). Its
[later full-check complement](f5-lifecycle-full-check-20261115.json) records the
952/122 active JVM result and a successful single-attempt R8 build/package check,
with no additional Android runtime execution. The 426-case HOST invocation is
also scoped separately; another candidate's invocation is not added to it.
Both indices bind their own frozen source and artifact/log identities. The scoped
index preserves the initial non-passing bootstrap/readiness attempts without
inferring a product defect; the later complement references that unchanged
snapshot. These local CONTAINER and ANDROID_EMULATOR results do not establish
remote CI approval. Their snapshot CI fields remain PENDING; current lifecycle
integration status is stated above.

The [20261116 capture software index](f5-runtime-capture-software-evidence-20261116.json)
binds the integrated controller, observation foundation, genuine Lifecycle events
and Client/Owner checkpoints. Their Docker JVM invocations are **309/40**, **304/38**
and **316/39** cases/suites; these overlap and are not added. The controller's
**462 HOST cases include 426 existing cases**. Its four expected RED failures remain
a failed invocation. For #148, actual API35/x86_64/4KiB framework execution records
**76 START/OK cases, zero skips/assumptions**, including 64 existing and 12 new
controls. Source clocks and endpoints are synthetic. The earlier one-case RED and
11-case RED2 (10 pass, one expected failure) remain separate from GREEN. The two
capture-fence regressions were reproduced and fixed. Exact-head CI for PRs
#147/#149/#150/#152 passed before merge; the installed-APK evidence remains bound
to historical local source `c4687b620b692fbef910fdddb9e826ae52e030fd`, whose eight
tested files match PR #152 head `45488c816353626b38e0ea38a73783782e9a8a73`.
This is software evidence, with no native-model, physical, API37 or 24-hour approval.

## Remaining gate matrix

| Workstream / owner boundary | Evidence available | Evidence required before closure |
| --- | --- | --- |
| S21/arm64 Cortex lifecycle | Real-model import/load/stream/cancel/timeout/unload and actual AI death/rebind on scoped emulators | Same paths on Galaxy S21 8GB, with the exact source, installed APK pair, model hash, API/build, ABI and page size recorded; failures retained |
| Resource/model profiles | Fail-closed governor, authenticated fresh telemetry and deterministic restart/backoff tests | Real cold/warm load, prefill/decode, per-process and aggregate memory/headroom, thermal/battery, foreground/background and storage-pressure measurements; reviewed bounds for the exact model/context/threads/tokens |
| API37 memory limiter | Offline consistency/classification controls plus the locally prepared #161 acquisition procedure and #163 paired-source/manual-request implementation | Integrate/review pending software and execute that prepared procedure on an actual compatible physical API37 build, with its real signal, original policy transition before heavy work, cooldown/fallback/refusal and owned override restoration |
| Native/API compatibility | Docker ELF/ABI/APK/AAB/16KiB checks, scoped API35/API37 x86_64 R8 probes, an API35 x86_64 real-model minified optional-metrics probe with synthetic admission, historical earlier physical loads | Complete the declared physical/native/API cells using current artifact identities; production admission and physical arm64/16KiB qualification cannot be inferred from the scoped emulator probes or debug loading |
| Core stability (#64) | Functional F4 exit and short, artifact-bound workloads | Complete approved 24-hour Core workload, including tasks/reminders/heartbeat, reboot/unlock, actual process death, continuous backlog/UNKNOWN observations, retention and growth review |
| Post-Cortex stability (#89) | 33-cycle regression; no 24-hour approval | At least 24 real elapsed hours plus memory stress, with actual heavy work and crash/ANR/resource-exit/recovery/queue/reminder/thermal/battery/storage review |
| Complete MAIN/HOST runtime and heavy-work producers | Integrated #151/#153 sampler/encoder, #158 assembly, #159 capture composition and #160 executable finite workload; #163 actual paired-source/policy/manual requests and #165 passive qualified monitoring are locally validated pending integration | Finish pending integration/required CI and execute the prepared, source/artifact-bound paths after profile calibration/target allocation, preserving genuine time, missing phases/loss, UNKNOWN, owned cleanup and original Core authority |
| Final F5 decision | Software checklist and explicit remaining gates | Review every §11.11 criterion, #64 prerequisite and #89 evidence against the frozen candidate; only then decide CORTEX_RUNTIME_READY and F6 entry |

The user reconfirmed on 2026-11-15 that no physical devices are available,
including a Galaxy S21 or physical API37 target. This current availability block
is recorded by the [review renewal #135](https://github.com/otavio-esteves/O.R.I.O.N/issues/135),
alongside the existing #89/#64 requirements. No new physical run or soak is started
by this renewal. Physical execution, measured profile calibration and actual-duration
stability remain blocked. An offline or unrelated emulator is not an allocated
physical target. Inventory must be checked again before any new run. A completed
observer collection, short workload or cleanup is not a soak PASS.

## Commands that already exist

Run compilation and device-independent checks in Docker, sequentially when sharing
the Gradle cache. These are the current CI task names, not physical qualification:

```bash
docker compose run --rm host-harness-check
docker compose run --rm android-build check assembleDebug assembleRelease \
  :app:assembleDebugAndroidTest :core:security:assembleDebugAndroidTest \
  :ai:llama:assembleDebugAndroidTest :ai:service:assembleDebugAndroidTest --max-workers=2
docker compose run --rm android-build -PorionQualificationRelease=true \
  :app:verifyQualificationNativePackage :app:assembleQualificationReleaseAndroidTest \
  --max-workers=2
```

Before HOST/device work, select one explicitly allocated target, freeze the source
and produced APK/model hashes, and retain the actual device fingerprint/API/ABI/page
size. Compare installed APK hashes with those exact local APKs before instrumentation.
Record the signing and baseline provenance for installed-upgrade work. Never
uninstall, clear data or downgrade as an unrecorded fallback.

The following existing command is an **emulator software regression only**, after
the verified fixture is provisioned in the test target's private files directory:

```bash
"$ORION_ADB" -s "$ORION_EMULATOR_SERIAL" shell am instrument -w -r \
  -e class network.orion.app.ai.OrionCortexRuntimeTest \
  -e orionModelPath /data/user/0/network.orion/files/qwen2.5-0.5b-instruct-q4_k_m.gguf \
  -e orionEnvironment ANDROID_EMULATOR \
  network.orion.test/androidx.test.runner.AndroidJUnitRunner
```

`OrionCortexRuntimeTest.fixture` deliberately requires `ANDROID_EMULATOR`, and its
governor inputs are synthetic. Changing the label to `PHYSICAL_DEVICE` fails that
fixture; leaving the emulator label while running on hardware misrepresents the
environment. It is not an existing physical benchmark command. Missing model
arguments cause assumptions/skips; a passing runner with skipped model cases does
not prove inference. The immutable fixture identity and its limited purpose are
in [the model manifest](../../ai/llama/test-fixtures/model-manifest.json).

The current non-debuggable R8 probe has its own separate target and runner:

```bash
"$ORION_ADB" -s "$ORION_ALLOCATED_SERIAL" shell am instrument -w -r \
  network.orion.qualification.test/network.orion.app.compatibility.OrionQualificationInstrumentation
```

Only run it after validating the intended qualification package identities and
installing the exact owned APK pair. Preserve the primary `network.orion` install.
Follow [the scoped R8 procedure](F4-MinifiedNative.md) for refusal of preexisting
qualification packages, installed-hash checks and owned cleanup. Its three probes
establish packaged library loading, AI constructor/corrupt-model handling and Core
storage startup; they are not real-model inference/performance qualification.

The existing HOST recorder supports actual model-import death boundaries:

```bash
python3 scripts/android/verify-transaction-process-death.py \
  --adb "$ORION_ADB" --serial "$ORION_ALLOCATED_SERIAL" \
  --environment PHYSICAL_DEVICE --scenario model_file \
  --evidence-dir "$ORION_NEW_FILE_ONLY_EVIDENCE"
python3 scripts/android/verify-transaction-process-death.py \
  --adb "$ORION_ADB" --serial "$ORION_ALLOCATED_SERIAL" \
  --environment PHYSICAL_DEVICE --scenario model_registered \
  --evidence-dir "$ORION_NEW_REGISTERED_EVIDENCE"
```

These commands install/debug-instrument and SIGKILL MAIN on the selected target.
Use one dedicated target with no concurrent qualification. Their result covers
owned import crash recovery, not a real production profile, power loss or a soak.

After the full target window and approved Core profile are available, the existing
controlled workload can collect one component of the pending Core soak:

```bash
python3 scripts/android/verify-f4-soak-runtime.py \
  --adb "$ORION_ADB" --serial "$ORION_S21_SERIAL" \
  --environment PHYSICAL_DEVICE --dedicated-target \
  --app-apk app/build/outputs/apk/debug/app-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --duration-seconds 86400 --reminder-interval-seconds 3600 \
  --maintenance-history --evidence-dir "$ORION_NEW_CORE_SOAK_EVIDENCE"
```

Read [the soak procedure](F4-SoakObservation.md) before scheduling it. The recorder
changes temporary policies, prepares approved owned fixtures and performs an actual
MAIN death. It preserves an incomplete attempt and a recovery journal. Do not run
it for a partial device window, run competing device operations, normalize unknown
preflight settings, or treat restoration/cleanup as qualification.

Read-only collectors `record-soak-observation.py` and
`record-maintenance-history.py` provide bounded resource/storage and typed Core
maintenance observations. Their CLI accepts the explicit environment, APK,
duration/interval and a new evidence directory. They retain sampling gaps. They
do not generate a Cortex load, prove continuous heartbeat autonomy by themselves,
or qualify undisturbed idle/battery behavior while ADB is polling.

## Historical software preparation inventory — 2026-11-16

This historical inventory records the stated review baseline; the dated 2026-11-17 section above provides the current integration status. It does not close F5 or imply that all preparation is integrated and only hardware remains.
Counts below retain their individual execution scopes; they are not additive.

| Package | Current software/evidence scope |
| --- | --- |
| PR #94 — physical preflight | Integrated: source/artifact/target integrity checks and blocked execution plan; no heavy runner. [Work package](../issues/ORION-F5-QUAL-PREFLIGHT-001.md). |
| PR #95 — rejected model management | Integrated: explicit MAIN-owned removal/CAS/recovery of selected rejected artifacts; no inference authority. [Work package](../issues/ORION-F5-MODEL-003.md). |
| PR #97 — unloaded physical baseline | Integrated: opt-in real MAIN/AI resource collector and HOST recorder; no model load/inference. [Protocol](F5-PhysicalBaselineRecorder.md), [work package](../issues/ORION-F5-PHYSICAL-BASELINE-001.md). |
| PR #101 — MemoryLimiter protocol | Integrated offline correlation/consistency validator; no stimulus or physical limiter proof. [Protocol](F5-LimiterEvidenceProtocol.md), [work package](../issues/ORION-F5-LIMITER-EVIDENCE-001.md). |
| PR #102 — runtime protocol | Integrated offline identity/lifecycle/stream/time/PSS consistency validator; no Android heavy producer. [Protocol](F5-RuntimeEvidenceProtocol.md), [work package](../issues/ORION-F5-RUNTIME-EVIDENCE-001.md). |
| PR #104 — heavy-soak protocol | Integrated offline bounded journal/fence/cleanup consistency validator; no workload executor. [Protocol](F5-HeavySoakEvidenceProtocol.md), [work package](../issues/ORION-F5-HEAVY-SOAK-EVIDENCE-001.md). |
| PR #107 — durable HOST store | Integrated atomic/fsync/CAS evidence storage with explicit uncertain commit, owned files and serialized writers; no device executor. [Protocol](F5-HeavySoakJournalStore.md), [work package](../issues/ORION-F5-HEAVY-JOURNAL-001.md). |
| PR #108 — pre-resource readiness | Integrated real read-only diagnostic; required CI passed. Actual emulator receipt reports NO_QUALIFIED_PROFILE. Local checks: 807 JVM, 315 HOST, five owner-gate cases and three ordinary R8 probes. [Producer](F5-CortexReadinessProducer.md), [work package](../issues/ORION-F5-READINESS-PRODUCER-001.md). |
| PR #109 — exit capture | Integrated with required CI green: R8/348-HOST validation and actual emulator AI_NOT_AVAILABLE/truncated-history observations; no AI auto-start. Live-endpoint/death-correlation branches currently have synthetic HOST coverage only. [Producer](F5-ExitCapture.md), [work package](../issues/ORION-F5-EXIT-CAPTURE-001.md). |
| PR #113 — unload observation | Integrated with required CI green:142 app JVM cases (including10 focused),34 scripted Android cases, lint/Debug/Release and ordinary R8/native packaging. Optional bounded internal collector observes existing UNLOADED replies; production collector remains disabled. No new Cortex RPC or heavy producer. [Technical note](F5-UnloadObservation.md), [work package](../issues/ORION-F5-UNLOAD-OBSERVATION-001.md). |
| PR #115 / issue #111 — native-local metrics | Integrated same-runtime tokenizer/sample/decode counts and optional API-wall measurements. Scoped validation includes 16 native JVM, 142 app JVM, 13 library Android, three private-AI integration and three ordinary R8 cases; no throughput, calibrated profile or physical approval. [Technical note](F5-NativeLocalMetrics.md), [evidence](f5-native-local-metrics-evidence.json). |
| PR #114 / issue #112 — metrics IPC ADR | [ADR-0003 is ACCEPTED](../adr/ADR-0003-optional-cortex-generation-metrics.md). Its optional read-only Cortex minor-2 extension changes only the explicitly scoped contract; unaffected architecture, history semantics and gates remain binding. Implementation is tracked separately by PR #124 below. |
| PR #120 / issue #116 — HOST time and workload window | Integrated suspend-aware HOST CLOCK_BOOTTIME observations and fences against effects after the workload window. [HOST clock implementation](../../scripts/android/qualification_host_clock.py); [heavy journal v1](F5-HeavySoakJournalStore.md) retains its separate monotonic/session semantics. No actual 24-hour result. |
| PR #121 / issue #118 — unload isolation | Integrated asynchronous unload diagnostics isolation; additional diagnostic clocks do not hold the execution turn or caller return. [Technical note](F5-UnloadObservation.md). |
| PR #122 / issue #117 — resource facts | Integrated bounded passive copies of actual resource/restart decisions; no reevaluation, fresh sampling, exporter or admission authority. [Technical note](F5-ResourceFacts.md). |
| PR #124 / issue #119 — optional generation metrics | Integrated closed codec/AIDL/service/native adapter and correlated manual MAIN reader under ADR-0003. Scoped real-model minified emulator generation/FINAL/cache/unload was observed with synthetic admission; wire time remains UNAVAILABLE. [Service](F5-GenerationMetricsService.md), [reader](F5-GenerationMetricsClient.md), [R8 probe](F5-GenerationMetricsR8.md), [evidence](f5-generation-metrics-evidence.json). |
| PR #126 / issue #123 — owner cycle | Integrated bounded observation of one existing MAIN owner invocation and a separate manual metrics read; receipts remain incomplete and NOT_QUALIFIED. [Technical note](F5-OwnerCycleObservation.md), [evidence](f5-owner-cycle-evidence.json). |
| PR #129 / issue #125 — partial projection | Integrated pure bounded ORION_MAIN_CYCLE_PARTIAL projection; no persistence/export sink or RuntimeEvidence v1 completion. [Technical note](F5-CyclePartialProjection.md), [evidence](f5-cycle-projection-evidence.json). |
| PR #131 / issue #127 — single-attempt core | Integrated one-use trusted-candidate preparation/consumption API. Default plans remain empty and template null; no operational workload is supplied. [Technical note](F5-SingleAttemptCore.md), [evidence](f5-single-attempt-core-evidence.json). |
| PR #134 / issue #128 — single-attempt R8 composition | Integrated qualification-only composition on the existing MAIN owner. Current empty configuration yields PREPARE_REFUSED / NO_QUALIFIED_PROFILE, with Driver method counters 1/0/0/0 and no partial. [Technical note](F5-SingleAttemptR8.md). |
| PR #136 / issue #133 — replay and Unicode audit | Integrated reminder replay proof independent of prunable domain events, strict inference Unicode/fingerprint validation and scoped audit evidence. [Index with active-module correction](audit-20261115-evidence.json); its archived raw reports remain unchanged. |
| PR #137 / issue #130 — one-shot HOST capture | Integrated; issue #130 is closed. One already-installed runner invocation, source/local/installed APK hash checks and HOST/device windows, with UNKNOWN on interruption or timeout and no retry. This is partial capture integrity, not source authentication or proof of selected READY model identity. [Procedure](F5-SingleAttemptHostCapture.md). |
| PR #138 / issue #132 — passive lifecycle facts | Optional bounded facts reuse original deadline samples and validated byte totals; first terminal, buffered admission, ordered release and downstream delivery remain distinct. No extra clock/RPC/content-byte validation, exact event timestamp or complete RuntimeEvidence v1 is supplied. Integration status is stated above. [Work package](../issues/ORION-F5-MAIN-LIFECYCLE-FACTS-001.md), [scoped local evidence](f5-lifecycle-facts-evidence-20261115.json), [full-check complement](f5-lifecycle-full-check-20261115.json). |
| PR #143 / issue #140 — original split bytes | Integrated passive reuse of the original validated text/tool UTF-8 counts, including pending release and known zero; no additional encoding or token claim. [Work package](../issues/ORION-F5-SPLIT-BYTE-FACTS-001.md). |
| PR #145 / issue #141 — capture contract | Integrated complete implementable field/source, time, fence, memory and bounded-export contract; it does not itself implement or authenticate a producer. [Contract](F5-RuntimeCaptureContract.md). |
| PR #147 / issue #142 — finite heavy controller | Integrated durable intent ACK before typed effects, real HOST BOOTTIME guards, interruption/UNKNOWN fencing and no replay. The 462-case HOST invocation uses synthetic ports; production adapters and full heavy executor remain absent. [Technical note](F5-HeavySoakController.md). |
| PR #149 / issue #144 — observation foundation | Integrated inactive final observation clock and bounded recorder; native-flag consistency checks, sticky clock failure and immutable manual snapshots. The 309/40 JVM invocation is scoped software evidence. [Work package](../issues/ORION-F5-RUNTIME-EVENT-SOURCE-001.md). |
| PR #150 / issue #146 — genuine Lifecycle events | Integrated opt-in exact first-terminal/ordered-acceptance checkpoints using the separate observation clock, original split bytes and publication after original update construction; independent reserved terminal and explicit loss. The 304/38 JVM invocation overlaps #144/#148. [Work package](../issues/ORION-F5-LIFECYCLE-EVENT-HOOKS-001.md). |
| PR #152 / issue #148 — Client/Owner checkpoints | Integrated explicit internal capture association, real runOnce ownership, endpoint/turn/PID/boot/model fences, genuine dispatch/cleanup points and separate manual historical import. Default capture remains absent; 316/39 JVM and 76 Android controls are mechanical evidence. [Work package](../issues/ORION-F5-CLIENT-OWNER-CAPTURE-001.md), [software index](f5-runtime-capture-software-evidence-20261116.json). |
| Issues #151 / #153 — sampler and encoder | IN_PROGRESS at this review baseline; neither candidate is integrated or included in this evidence package. Seven-phase acquisition, bounded rendering and their eventual composition require their own checks/CI; they do not supply workload, profile or physical approval. [Sampler issue](https://github.com/otavio-esteves/O.R.I.O.N/issues/151), [encoder issue](https://github.com/otavio-esteves/O.R.I.O.N/issues/153). |

Readiness observes only the real owner's early predicates. It never issues a
request, evaluates resource/restart policy or grants admission, even if trusted
profiles are added later. A null Core port remains CORE_PORT_UNAVAILABLE; it is
not assigned a guessed cause. Actual emulator execution is software evidence.
Exit capture is CAPTURE_ONLY/NON_DEFINITIVE, not a limiter stimulus or proof of
incident causation; unavailable AI and truncated history remain explicit.

Baseline, readiness, exit capture, single-attempt, runtime-capture and
instrumented-workload programs are mutually exclusive opt-in modes of the separate network.orion.qualification APK.
The independent `orionCortexGenerationMetrics` property is not enabled by the
single-attempt mode. Normal Debug/Release keep single-attempt disabled even when
both `orionQualificationRelease` and `orionCortexSingleAttempt` properties are true.
Each selected APK/test pair must be
frozen and bound independently; one mode's receipt cannot qualify another mode
or the production identity. Bootstrap may create that identity's technical
DB/Keystore/WorkManager state; these runs do not attest a wholly read-only device
or quiescent Core. Physical Build candidate classification is not verified
physical provenance. Neither JSON consistency nor CLI inputs approve a profile.

## Prepared paths and remaining integration/execution — 2026-11-17 UTC

- The MAIN/HOST receipt path is implemented through #151/#153/#158/#159. #160 supplies concrete finite HOST/adapters and the existing Owner invocation; #163 adds local paired exit/original-policy sources and a separate finite manual post-death path; #165 binds passive monitoring to the actual qualification package/UID. The published #163/#161 PR #167 still needs its required CI/integration; #165 still needs publication/integration/CI before treating the combined candidate as integrated. A source capsule or consistent JSON is not source authentication or a calibrated profile.
- Keep source meanings distinct: original deadline samples (#132), split bytes (#140), dedicated first-terminal/ordered-acceptance moments (#146), Client/Owner call/reply points (#148), memory acquisition (#151) and bounded export (#153) do not become downstream delivery/native compute time. #130 remains a one-shot minimized partial recorder; #142's own abstract ports are not retroactively a concrete Android executor. Formatter125 and v1 formats remain unchanged.
- Native counters/API-wall measurements under ADR-0003 do not supply synchronized native phase compute, throughput, authenticated TTFT or production budgets. RuntimeEvidence v1 still reports native token/prefill/decode metrics unavailable and rejects extra metric fields.
- Trusted model provisioning, a reviewed attempt template/resource profile, physical native/memory coverage and budget calibration remain required. Production plans remain empty and the template null; unavailable configuration causes actual refusal.
- The bounded physical MemoryLimiter acquisition/correlation/policy-transition procedure exists locally under #161, with #163's actual-source/manual-request support. It still needs pending integration and execution on the allocated build. Unsupported symbols, ambiguous units, missing pairing and UNKNOWN remain explicit; no stimulus, public reason or override restoration has been physically qualified.
- The finite instrumented workload and separate passive monitoring paths exist. Execute their reviewed schedule on the allocated target; do not use missed-slot catch-up, Owner replacement or UNKNOWN retry. Complete Core and post-Cortex duration/memory-stress evidence remains absent. Collection completion or before/after samples do not establish continuous backlog, autonomy, real 24h or a full gate.

A runner RESULT_OK is a program outcome and remains NOT_QUALIFIED. The optional IPC, retained partials, capture/source assembly and prepared executors do not approve a benchmark, a production profile, F4 stability, CORTEX_RUNTIME_READY or F6. The physical #64/#89 obligations remain open alongside the explicitly pending software integration/CI.

## Bounded work needed before the physical run

1. Finish required exact-head CI/integration for the published #163/#161 PR #167, then the pending #165 publication/review/CI/integration. Freeze the combined source, local/actually-installed artifacts, source/model/configuration references and target identity; retain failed and historical snapshots separately.
2. After target allocation and calibration, execute the prepared Main/HOST runtime measurement paths with actual reviewed configuration. Keep physical observations separate from synthetic functional controls and record model input/load/prefill/decode/unload, native-inclusive memory, thermal/battery/deadline facts without content or sensitive traces. Absent values remain refusal/unavailable.
3. Execute the prepared API37 limiter procedure on the identified compatible build: actual bounded owned stimulus, instance-safe pairing, original Core policy transition before another heavy request, bounded new requests until refusal and explicit owned restoration. No watchdog/synthetic exit substitutes for that evidence.
4. Allocate the complete approved Core/post-Cortex 24-hour windows and execute the prepared finite workload/passive history components, including memory stress, genuine elapsed time and visible gaps. The Core reminder workload alone supplies no Cortex workload; partial windows and cleanup do not qualify a soak.

This documentary reconciliation executes no device command and closes no gate. Historical counts, source identities, failed attempts and scoped controls remain intact. It makes no claim that general software preparation or all required CI is complete.

## Read-only preflight and blocked execution plan

[ORION-F5-QUAL-PREFLIGHT-001](../issues/ORION-F5-QUAL-PREFLIGHT-001.md) supplies
`scripts/android/prepare-f5-physical-qualification.py`. It prepares identity and
integrity evidence only. It never installs, instruments, starts/stops a process,
changes settings, approves a profile or runs a workload.

The source manifest is a JSON object with exactly `schemaVersion: 1`,
`sourceCommit`, `files` and `artifacts`. `files` must map **every tracked file** of
the clean source checkout to its SHA256, without omissions, extra files or symlinks.
`artifacts` maps exactly `app`, `test` and `model` to the selected binaries' SHA256.
The manifest must live outside the checkout so it does not make its own source
freeze dirty. One way to generate it after a reviewed build and source freeze is:

```bash
python3 - <<'PY'
import hashlib, json, os, subprocess
from pathlib import Path
def git(*args):
    return subprocess.check_output(['git', *args], text=True)
def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()
assert not git('status', '--porcelain=v1', '-z', '--untracked-files=all')
paths = list(filter(None, git('ls-files', '-z').split('\0')))
value = {'schemaVersion': 1, 'sourceCommit': git('rev-parse', 'HEAD').strip(),
         'files': {name: sha(name) for name in paths},
         'artifacts': {'app': sha(os.environ['ORION_APP_APK']),
                       'test': sha(os.environ['ORION_TEST_APK']),
                       'model': sha(os.environ['ORION_CANDIDATE_MODEL'])}}
with Path(os.environ['ORION_SOURCE_FREEZE']).open('x') as stream:
    stream.write(json.dumps(value, indent=2, sort_keys=True) + '\n')
PY
```

The candidate model manifest uses the immutable model manifest's existing fields
(`schemaVersion`, `purpose`, publisher/repository/revision, filename/size/SHA256,
license/URL/licenseSHA256), with the explicit purpose
`PHYSICAL_CANDIDATE_NOT_QUALIFIED`. Create a separate candidate provenance record;
do not rewrite the committed emulator fixture as a qualified profile. The preflight
rejects synthetic/emulator/fixture markers and unknown fields such as
`qualified: true`. No operator-supplied boolean is accepted as profile approval.

After the physical target is explicitly allocated and the exact app/test/model
files are already installed/provisioned by their reviewed procedure:

```bash
python3 scripts/android/prepare-f5-physical-qualification.py \
  --adb "$ORION_ADB" --serial "$ORION_S21_SERIAL" --target S21 \
  --source-root "$ORION_REVIEWED_SOURCE_ROOT" \
  --source-manifest "$ORION_SOURCE_FREEZE" \
  --app-apk "$ORION_APP_APK" --test-apk "$ORION_TEST_APK" \
  --model-file "$ORION_CANDIDATE_MODEL" \
  --model-manifest "$ORION_CANDIDATE_MODEL_MANIFEST" \
  --evidence-dir "$ORION_NEW_PREFLIGHT_EVIDENCE"
```

Use a separately allocated physical API37 device with `--target API37` for that
role. The tool requires explicit device state, non-emulator qemu/fingerprint/
hardware signals, supported API/arm64/page size and the expected target model. It
hashes serial/fingerprint identities instead of retaining raw values. These are
observable consistency checks, not tamper-proof physical attestation or RAM/model
performance qualification. It also checks local manifest/artifact hashes against
installed base APK hashes and the selected regular, app-private model file hash.
Split APKs, unavailable `run-as` access or any mismatch stop the preflight.

Even when all technical integrity checks agree, the report is **BLOCKED** and the
CLI exits **3**. The physical runtime, actual API37 MemoryLimiter and post-Cortex
24-hour memory-stress cases remain **NOT_IMPLEMENTED**, without runnable commands.
Profile qualification is `NOT_EVALUATED`; all phase/profile approval fields remain
false. Source/APK/model hash agreement does not prove a build's provenance or
substitute measurement and review. Invalid/incomplete attempts are **REJECTED**
(CLI exit **2**) in a fresh evidence directory and retain no raw device errors.

The standard-library HOST tests use synthetic files and a fake ADB executable:

```bash
python3 scripts/android/test_f5_physical_preflight_harness.py
```

They establish preflight contracts only. Integration runs the same test module in
the canonical `host-harness-check` container; this package itself uses no device.

## Prepared unloaded resource baseline

[ORION-F5-PHYSICAL-BASELINE-001](../issues/ORION-F5-PHYSICAL-BASELINE-001.md) adds an
explicit measurement scope to the existing separate qualification identity. It is
not a model workload, physical inference test, memory-limiter stimulus or soak.

```bash
docker compose run --rm android-build \
  -PorionQualificationRelease=true -PorionPhysicalBaseline=true \
  :app:verifyQualificationNativePackage :app:assembleQualificationReleaseAndroidTest \
  --max-workers=2
```

The flag requires qualificationRelease and selects a different framework-only
runner. It includes a qualification-only bridge which calls the actual
`supportedCortexInventory`, `OrionCortexMemoryTelemetry`,
`OrionAndroidResourceObserver` and `AndroidOrionClock`. A bridge-specific keep rule
exists only in this explicit scope; normal Debug/Release and the ordinary three
R8 probes do not contain this bridge or keep rule. No new variant, ABI, process,
permission, AIDL method or production profile is introduced. The qualification APK
has no Debug voice probes; the measurement never removes components to make its
inventory pass.

This is the same variant output directory as the ordinary qualification APK.
Freeze/copy both selected artifacts and hashes before changing flags or rebuilding;
never relabel an ordinary probe APK as a baseline APK, or the reverse. Measurements
of `network.orion.qualification` include this explicit bridge/instrumentation and
do not qualify the production identity or optimized production footprint.

This measurement is not a read-only device preflight: am instrument starts the
separate APK and its normal Application bootstrap may create that identity's own
Room/Keystore/technical WorkManager state. The Core can still be initializing
during collection. Only the unloaded/native-runtime-free AI state is checked;
the measurement does not attest a quiescent Core/device. It issues no model,
inference or settings operation and does not touch the production identity.

After the allocated physical target and exact installed qualification APK pair
pass the HOST recorder's source/artifact/identity checks, its approved component is:

```text
network.orion.qualification.test/network.orion.app.compatibility.OrionPhysicalBaselineInstrumentation
```

The only accepted instrumentation arguments are `orionEnvironment=PHYSICAL_DEVICE`,
`orionSampleCount` (1–10) and `orionSampleIntervalMs` (100–1000). The runner rejects
unknown arguments, an emulator build, the wrong target identity, invalid limits or
an unsupported manifest inventory before binding. Binding is bounded to five
seconds; collection is bounded to thirty seconds and owns just one lightweight
AI-service connection. There is no model file or model/profile parameter.

The AI must report UNLOADED, no native runtime and no native validation before and
after every sample. Busy/resident work is rejected, never cancelled or unloaded.
Each row uses actual same-boot/PID-bound native-inclusive MAIN and AI self-PSS,
their exact aggregate, system memory, battery, thermal, power-save, foreground and
memory-pressure readings. Missing, stale, malformed or unavailable observations
produce an incomplete result, never fabricated zero use. Only bounded technical
metrics leave the target; no prompts, output, file paths, raw exit descriptions,
traces or device serials are included.

Schema 1 rows use `orionPhysicalBaselineSample`; the terminal summary uses
`orionPhysicalBaselineSummary`. Samples are sequential and freshness is less than
1000ms at completion. The summary reports
`OBSERVATION_COMPLETE_REVIEW_REQUIRED`, `INCOMPLETE` or `REJECTED`, always with
`NOT_QUALIFIED`, no heavy work, and false profile/production/F5/soak approval fields.
An owned emulator can verify rejection; it cannot supply physical baseline values.
No physical sample is claimed by compilation, HOST fixtures or the rejection case.
The requested PHYSICAL_DEVICE argument is not an observation: rejected arguments,
target identity or emulator builds retain environment UNVERIFIED in the runner's
summary. The bridge revalidates the physical Build before any receipt as well.
Every attempted bind is released even if bindService returns false or throws;
NOT_REGISTERED cleanup is explicitly non-successful, never a completed observation.
