# F5 remaining qualification — current operational checklist

Work package: [ORION-F5-QUAL-PLAN-001](../issues/ORION-F5-QUAL-PLAN-001.md).
Source reviewed: main `3f244d1fcb6abf33b01c38b194434c793404b4d1`, the merge of
PR #91. This document is a plan, not a new execution or approval record.

F5 remains **OPEN**. Production inference stays disabled and
`CORTEX_RUNTIME_READY` is not declared. Physical qualification is already tracked
by [#89](https://github.com/otavio-esteves/O.R.I.O.N/issues/89), with the work-package
contract in [ORION-F5-QUAL-001](../issues/ORION-F5-QUAL-001.md). Remaining Core
stability work stays under [#64](https://github.com/otavio-esteves/O.R.I.O.N/issues/64).
No replacement physical issue or waiver is needed.

Memory-specific source review, measurement boundaries and the proposed acquisition
protocol are in [F5-MemoryEvidence.md](F5-MemoryEvidence.md). They do not approve a
model/resource profile.

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
| Complete runtime software validation | [Integrated closeout](Audit-20261110-Closeout.md) records 798 JVM reports, 127 host cases, 395 API37/16KiB application cases, real Qwen, actual process death and R8 probes | Freeze a new source/artifact identity and rerun affected checks after software changes; do not relabel historical results |
| Qualify hardware, native/API behavior and stability | Still open under #89/#64 | Obtain the actual targets and required uninterrupted windows; execute the remaining profile and review the evidence |

The [F5 runtime gate](F5-RuntimeGate.md) already maps every Master Plan §11.11
criterion to its scoped evidence. The [integrated evidence index](audit-20261110-evidence.json)
binds the newer source, installed artifacts, raw logs, model and platform identities.
Overlapping test selections are not additional unique cases. A discovery run with
`log=true` is not execution evidence.

## Remaining gate matrix

| Workstream / owner boundary | Evidence available | Evidence required before closure |
| --- | --- | --- |
| S21/arm64 Cortex lifecycle | Real-model import/load/stream/cancel/timeout/unload and actual AI death/rebind on scoped emulators | Same paths on Galaxy S21 8GB, with the exact source, installed APK pair, model hash, API/build, ABI and page size recorded; failures retained |
| Resource/model profiles | Fail-closed governor, authenticated fresh telemetry and deterministic restart/backoff tests | Real cold/warm load, prefill/decode, per-process and aggregate memory/headroom, thermal/battery, foreground/background and storage-pressure measurements; reviewed bounds for the exact model/context/threads/tokens |
| API37 memory limiter | Synthetic exit classification/backoff and watchdog coverage | Actual compatible physical API37 build, actual reported reason/description classification, changed policy before the next heavy request, bounded cooldown/fallback and eventual refusal under repeated resource deaths |
| Native/API compatibility | Docker ELF/ABI/APK/AAB/16KiB checks, scoped API35/API37 x86_64 R8 probes, historical earlier physical loads | Complete the declared physical/native/API cells using current artifact identities; real-model R8 inference and physical arm64/16KiB cannot be inferred from x86 or debug loading |
| Core stability (#64) | Functional F4 exit and short, artifact-bound workloads | Complete approved 24-hour Core workload, including tasks/reminders/heartbeat, reboot/unlock, actual process death, continuous backlog/UNKNOWN observations, retention and growth review |
| Post-Cortex stability (#89) | 33-cycle regression; no 24-hour approval | At least 24 real elapsed hours plus memory stress, with actual heavy work and crash/ANR/resource-exit/recovery/queue/reminder/thermal/battery/storage review |
| Final F5 decision | Software checklist and explicit remaining gates | Review every §11.11 criterion, #64 prerequisite and #89 evidence against the frozen candidate; only then decide CORTEX_RUNTIME_READY and F6 entry |

The target availability recorded in #89 is the outstanding external prerequisite:
neither a Galaxy S21 nor a physical API37 target was available at the recorded
integration. An offline or unrelated emulator is not an allocated physical target.
Inventory must be checked again before any new run. A completed observer collection,
short workload or cleanup is not a soak PASS.

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

## Bounded work needed before the physical run

1. Define a physical Cortex instrumentation/measurement package under #89. Keep
   actual device resource observations and reviewed candidate limits separate from
   synthetic functional controls; do not weaken the emulator fixture to manufacture
   physical qualification. Record exact model input, load/prefill/decode/unload,
   memory/native accounting, thermal/battery and deadline results without content
   or sensitive traces in diagnostics.
2. Plan the actual API37 memory-limiter stimulus and classify the build's returned
   signal. Unsupported or unavailable signals remain explicit. Synthetic low-memory
   tests and watchdog self-kills do not prove this physical behavior.
3. Add the post-Cortex heavy-work/memory-stress component to the approved 24-hour
   profile. The existing Core reminder workload does not issue Cortex requests.
   Preserve interruption journals, real elapsed duration and observation gaps;
   define bounded owned cleanup before running it.
4. Freeze candidate source, produced and actually installed artifact identities,
   qualification criteria, per-device allocation and the complete run schedule.
   Keep hardware-dependent profile approval after measurement and review.

This planning package executes no device command and closes no gate. No new
runtime defect was established by the source-only review of the current AIDL
client; the old client's unload/status hypotheses must not be reported as current
main failures without a current-source reproduction.

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
