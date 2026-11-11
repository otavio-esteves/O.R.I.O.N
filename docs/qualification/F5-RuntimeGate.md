# F5 Cortex runtime — implementation and scoped validation complete

**F5 remains open; CORTEX_RUNTIME_READY is not declared.** The runtime, canonical
checks, installed upgrade and complete R8 probes have passed within their recorded
emulator scopes. The reviewed 372-case API37/16KiB full suite passed with four
vCPUs/4096MiB, zero failures and zero skips. Physical/resource
qualification remains open. [The evidence JSON](f5-runtime-evidence.json) retains
run identities, complete logs/provenance references, failures and pending gates.

Production inference remains disabled: the app has no approved model/resource
profile and retains its unavailable governor fallback. The MAIN runtime owner is
wired behind Core readiness, registry, fresh resource/restart and trusted inventory
checks. No Cortex UI, F6 routing or direct LLM→Skill authority is enabled.

## Audit refresh — 2026-11-10

The resource, retention and Cortex audit corrections passed their separate CI
reviews. The integrated candidate passed all **395 application tests** on
API37/x86_64/16KiB with zero failures/skips, including the 33-cycle real-model test.
API35 installed 5→6 upgrade and three real Cortex lifecycle cases, two API37 MAIN
import SIGKILL scenarios, and three API37 minified probes also passed. The
[audit closeout](Audit-20261110-Closeout.md) and
[evidence index](audit-20261110-evidence.json) bind these newer results to their
source/APK identities; the older runs below retain their historical scope.

Neither physical target is available, as confirmed by the user. S21 performance,
actual target-build MemoryLimiter, approved profiles, the full physical native/API
matrix and the 24-hour post-Cortex soak plus memory stress remain open under
[#89](https://github.com/otavio-esteves/O.R.I.O.N/issues/89). This refresh does not
declare CORTEX_RUNTIME_READY or open F6.

## Master Plan §11.11 checklist

PASS means the recorded container/emulator scope; it grants no physical,
performance, production-profile or stable-release qualification.

| Criterion | Scoped result/evidence | Open boundary |
| --- | --- | --- |
| Model imports | PASS: private import, SQLite CAS registry and real Qwen MAIN import | Physical/resource qualification |
| Model validates | PASS: bounded GGUF parser, exact real fixture and native tensor loading | Other models/hardware profiles |
| Interrupted import never yields READY | PASS: actual MAIN SIGKILL at file-only and committed-IMPORTED checkpoints, new-PID verification | Power loss/physical behavior not inferred |
| Orphan temporaries reconcile | PASS: bounded JVM cleanup and actual file-only SIGKILL orphan cleanup without adoption | Long-run growth/soak |
| Disk/corruption handled | PASS: reserve/length/hash, native corrupt-model rejection and Android access-recovery cases | Device storage pressure/performance |
| Model loads | PASS: actual CPU native and private descriptor-based Binder load | Full native/API/physical matrix |
| Inference works | PASS: real Qwen native and MAIN→`:ai` inference | Production performance/profile |
| Streaming works | PASS: actual ordered stream, UTF8 boundaries and terminality | Broader compatibility/soak |
| Cancel works | PASS: decode cancellation/reuse, load cancellation and client/before-start handling | Actual llama-hang fault injection |
| Timeout works | PASS: service deadline, native unwind/Core survival; queued deadline passed APIs35/37 | Physical calibration |
| Unload works | PASS: actual native/service unload and reuse | Physical RSS/thermal/battery |
| AI death preserves Core | PASS: AI SIGKILL; MAIN PID 7683 and durable state survived | Physical/OEM/soak |
| MAIN detects Binder death | PASS: PROCESS_DIED from actual AI death, no action reconstruction | Wider API matrix |
| Explicit rebind works | PASS: no terminal replay; explicit new request bound PID 8021 after 7710 died | Broader lifecycle/soak |
| Large payload avoids TransactionTooLarge | PASS: 300KiB SharedMemory Binder case and read-only model descriptors | Broader stress/compatibility |
| Memory Limiter avoids restart loop | PASS: deterministic governor/restart and synthetic memory-exit binding fence; watchdog self-kill also tested | Actual target MemoryLimiter, physical calibration and production limits OPEN |

## Validation summary

- Canonical Docker build/lint/module/benchmark/native ELF/ABI and Debug/Release
  APK/AAB packaging/16KiB checks passed. Latest reviewed run: 778 JVM cases across
  106 suites, zero failures/errors/skips; host contracts 127 and module negatives 36
  passed. Nine added JVM regressions include totals of 12 turnstile and 6
  `OrionInferenceRequestLedger` cases. SharedMemory cleanup fixes are separate.
- API37/x86_64/16KiB: native boundary 4 and real-model JNI 3 passed; Core/Cortex 3
  passed in 144.629s with actual AI death, Core survival, explicit rebind and timeout.
  Synthetic watchdog 1 passed; Keystore 4 passed. Overlapping contract selections
  remain separate runs, not a summed unique-case count.
- Two actual MAIN import SIGKILL recorder runs passed: file-only PID 9206→9260
  cleaned the unregistered orphan; committed-IMPORTED PID 9353→9410 recovered through
  exact selected validation. Both checkpoints were outside Room transactions.
- API35/x86_64/4KiB: reviewed F5 contracts 38 passed in 7.21s without failures/skips,
  including queued timeout. Installed version 5→6 upgrade retained encrypted records,
  queues and original keys and did not replay pending/UNKNOWN actions. Watchdog 1
  passed in 0.578s. Its fresh separate AVD booted in 55.578s.
- The complete minified R8 probe passed 3 checks on **both API35/4KiB and
  API37/16KiB**: all packaged native libraries load, the AI R8 constructor/native
  corrupt-model rejection path runs, and non-debuggable Core storage is healthy.
  This does not prove real-model R8 inference or the entire physical load matrix.
- The preceding full 371 run failed with 3 failures/3 notification assumption skips.
  The reviewed full 372 run completed in 400.801s with 2 legacy UI-focus failures and
  zero skips; Cortex 3, queued deadline and native filename guard passed within it.
  A captured window identified a System UI ANR. A pre-run install-inflight hash
  mismatch was blocked before instrumentation; zero tests began in that attempt.
- The final reviewed 372 run **passed in 371.42s**, without failures or skips. The parent restarted the
  separate API37 AVD with preserved userdata, 4096MiB/four online vCPUs, no concurrent
  compilation; actual pages remain 16384 bytes. R8 full 3 also passed there. The
  recorder verified both actually installed APK hashes before execution.

## Reproducibility and artifact scope

Reviewed source: 587 files, manifest SHA256
`1ba033a3b31b87dbfad9d5745e634bd2613ba254be5dc6274e7d22e8e882ea8c`.
The reviewed Debug pair is SHA256
`693db7840c77e3c84989270213484f5a5f8b5cea56567eb69615bc5c9c31a861` and
`9acb457ea299ea548dc6589bee24a851f04caacb99bc6a0a97cfd99f73894adf`;
the R8 pair is `2101f63c368b55f16d298249225721e96f201a1aa2d62f713b34d0c4ac15a44c`
and `5092b0280f8e34882dd9fb12cbccb3f2a07b50f2178c227108b9a80721d79b28`.
The one-shot ignored HOST evidence helper checks local/actually-installed SHA256
equality **before instrumentation** and records the source freeze. It is not a new
repository test harness. Historical unbound logs are not assigned newer hashes.
The earlier 371-run identity was captured during execution, explicitly labelled.

Raw evidence is versioned in the
[evidence bundle](artifacts/orion-f5-device-independent-evidence.tar.gz); its
SHA256 and content manifest are recorded in the evidence JSON. It contains no
private signing key, model, system image or APK. Temporary emulators are stopped.

llama.cpp commit `7d7f0287530bc8981690f361d8c1627dbae1c99a` and archive
SHA256 `2f15b173875f53cb2a3d099b65875a24824859b0b9a7ede276d58ec2881ae085` are
pinned with MIT attribution. The official Apache-2.0 Qwen fixture, immutable revision
and SHA256 are in its [manifest](../../ai/llama/test-fixtures/model-manifest.json).
The prior upgrade APK payload was HOST-built and only re-signed in Docker; originals
and non-signing ZIP payloads remained unchanged. It was not rebuilt canonically.
The official [API37/16KiB image](https://dl.google.com/android/repository/sys-img/google_apis/x86_64-ps16k-37.0_r08.zip)
was hash/CRC-verified; complete image, AVD, signing and execution provenance is in
the evidence JSON. Original user AVDs were preserved; no physical device was used.

Retained failures include early build/API/fixture-subset issues, platform boot/ANRs,
framework-alias rejection, runner selection, the initial Core whole-test timeout,
Debug/R8 filename-whitelist assertions and unsuccessful full-suite/UI attempts.
The corrected runs retain separate outcomes. JSON records the exact logs and
artifacts; no failed aggregate build or probe is relabelled as a pass.

The authorized work possible without a physical device is complete. Physical S21/arm64 loading,
performance/thermal/battery, real MemoryLimiter evidence, approved production
profiles, the complete native/API matrix and post-Cortex 24-hour/memory-stress work
remain open. Emulator results do not authorize stable release or 24/7 readiness.
