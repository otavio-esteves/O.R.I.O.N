# ORION-F5-RUNTIME-MEMORY-SAMPLER-001 — finite MAIN sampler

Issue: #151. Environment status: implementation and synthetic mechanical fixtures;
**NOT_QUALIFIED**. This package does not admit a workload or close a physical gate.

## Work package

- Objective: collect up to one complete MAIN/AI self-PSS row per seven genuine
  runtime phases under [capture contract #141](../qualification/F5-RuntimeCaptureContract.md).
- Files: final internal app sampler/worker/probe/DTOs, minimum Client diagnostic
  port and phase hints, capture-session coordination, Owner parameter passage,
  scalar `ai/api` terminal view, focused tests, this note.
- Contracts: observation clock #144, Lifecycle source #146, Owner/Client #148,
  existing private `healthCheck`/`getStatus` receipts. Invariants: INV-009/011/012/
  013/014/019/027/028/032/033/035/036. No new IPC, process, authority or persistence.
- Acceptance: explicit configuration; exact owned endpoint/session/turn/boot/PID/
  model fences; one nonwaiting offer per phase; queue at most seven and one global
  worker/RPC lane; complete genuinely closed windows only; no Core policy changes.
- Tests: seven controlled phases, same-ms crossing, missing/unavailable samples,
  endpoint/turn/Binder/PID/boot/model changes, malformed/failed diagnostic replies,
  exact freshness boundary, sums/native flags, stuck worker/queue bound, genuine
  Client functional equivalence, scalar source reads without chunk copies.
- Out of scope: renderer/HOST assembly, workload controller/transport, selected
  operating values/model/template/profile, API37/MemoryLimiter/24-hour measurements,
  F4/F5/F6 qualification or production activation.

## Composition and source

`beginRuntimeCapture()` remains sampling-absent by default. An explicit trusted
`OrionRuntimeSamplingConfiguration(maximumSampleAgeMillis)` opts into this
observational mechanism only. The value must be supplied and representable in
1..1000 ms; 1000 is a format ceiling, never a chosen freshness default. Missing
configuration issues zero sampling RPCs. Unavailable preparation is a fixed
session gap; it cannot enable or repair execution.

Only the existing winning runOnce activates Lifecycle #146 and binds its shared
clock #144. Sampler code never binds or reads the functional Clock. Original
local-load start, true load terminal, inference dispatch, true Lifecycle terminal,
unload dispatch and strict confirmed UNLOADED define the timeline. A terminal
before inference dispatch cannot manufacture DURING_INFERENCE/AFTER_TERMINAL.
Versions detect a crossed phase even when event milliseconds are equal.

The final MAIN probe uses actual self `Debug.MemoryInfo.totalPss * 1024` and actual
`ActivityManager.MemoryInfo.totalMem`. Its observation Moment immediately before
the Debug acquisition is shared exactly as row-start and MAIN-sample time. The
same current private endpoint supplies one diagnostic health receipt followed by
one state receipt. These two optional RPCs have separate counters and never use
`currentMemoryTelemetry`, ResourceObserver, governor/restart decisions, bind,
lostConnection, cancellation or functional PID mutation.

The first matching real health receipt may establish only a capture-local PID.
The endpoint/service/connection/Binder/epoch/turn, selected model, original boot
and functional PID pin when present are fenced again after collection. No PID,
zero, timestamp, native flag, model or total-RAM value is supplied by HOST or
inferred from another field. Identity failure is loss, not a repaired session.

## Finite lifetime and honest loss

There is one process-wide daemon, one serial diagnostic RPC lane and a typed queue
of at most seven tickets. A phase consumes its single nonwaiting offer even if it
has already elapsed, enqueue fails, telemetry is absent or its window crosses.
There is no timer, retry, catch-up, replacement after stuck transport or hotpath
wait. Missing/short phases are missing. PROCESS_DIED stops subsequent AI rows;
unload does not reset genuine retained wrapper/validation flags.

AFTER_UNLOAD remains open until explicit manual `readRuntimeCapture`, after the
original return. Manual close seals immediately and never joins, interrupts or
waits for the worker. Late replies cannot publish a row or restart a sampler.
Synchronous platform/RPC methods may themselves block: neither a local timeout
nor this component proves transport termination or a hard effect-duration bound.
A stuck call permanently occupies that single lane until it actually returns.
The daemon may remain parked, or blocked in that one original call; no observer
loop keeps querying after the source window is sealed.

The report is a bounded sidecar: attempt/start/completion masks, retained rows,
diagnostic RPC lower bounds, fixed gaps, dropped/failure lower bounds and
loss-accounting state. While `unfinished` is true, RPC counters are lower bounds
at manual close, not proof of a terminal transport. Complete rows alone enter
`facts.memory`; no row is clipped or relabeled. Per-phase loss accounting counts
each absent or discarded row once at close. Optional source/clock contention can
invalidate observations while original functional execution continues unchanged.
No content, request entity, path or raw exception is retained by the sampler.

## Validation and remaining limits

New JVM fixtures use bounded synthetic observation values, including test-only
reflection into the already-internal finite clock factory. Android fixtures use
a controlled private Binder endpoint and finite probe; a separate smoke test
reads real MAIN self-PSS/total RAM with synthetic timing. They establish mechanical
contracts only, with no loaded native model or calibrated workload. Controlled
test latches/drains are not production waits. Functional equivalence checks keep
original Clock reads, functional RPC order, callback payload/List path, original
return/terminal and exception behavior; explicit optional diagnostic calls are
accounted separately. The full source snapshot remains manual only; sampler
terminal checks use a constant-size read-only view.

Root's canonical validation passed on frozen source
`c83ad7dc834bd2c05aa92032f3cc981e07bb501d`, tree
`cb7bf1eb5ff68450354e2d005bf1223ef2918a17`. All nine changed Kotlin source/test
hashes matched before and after execution. This later note changes documentation
only; the tested implementation remains unchanged.

Docker completed in 11m 44s with exit code 0: 1893 actionable tasks, 924 executed,
735 from cache and 234 up-to-date. The invocation was:

```sh
./gradlew :ai:api:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest verifyModuleBoundaries testModuleBoundaries \
  :ai:api:lintDebug :app:lintDebug --max-workers=2
```

The XML inventory contains 321 JVM cases in 40 suites: 76 ai/api and 245 app,
with zero failures, errors or skips. This includes five new cases (one ai/api,
four app) and overlaps earlier package invocations; counts are not added across
runs. Module-boundary verification/tests and lint passed. ai/api reported no lint
issues; app reported 15 warnings and no errors. Every warning is outside the nine
changed source/test files; no suppression or source change was used to pass.
XML, HTML and SARIF reports are retained.

Actual framework execution used one dedicated owned API35/default/x86_64 emulator
with 4096-byte pages, after Docker finished. One instrumentation invocation
recorded 89 unique START and 89 unique OK statuses, with no failure, ignore,
assumption or skip; runner duration was 15.93s and ADB exited 0. The selection
includes 13 new controls and overlaps #148's 76 cases; it is not an additional
unique total. Clock, endpoint and workload fixtures remain synthetic, including
the timing used with the real MAIN self-PSS/total-RAM probe. Local/copied and
installed/pulled app/test APK SHA-256 matched their frozen bindings. Root verified
the owned emulator identity and stopped it after collection; no physical target
or unrelated device was used. These are ANDROID_EMULATOR mechanics, not physical,
native-model, calibration, API37/MemoryLimiter or 24-hour evidence.

The original validation summary, raw framework statuses/logs, installed bindings,
JUnit and lint reports are retained separately for durable evidence packaging.
Required exact-head remote PR CI remains pending; local checks do not substitute
for integration approval. No build, Gradle or ADB execution was repeated for this
documentary update. Existing CoreFoundation/factory/defaults, governor,
service/AIDL/wire, core/time, profiles and plans remain unchanged. No durable state
or migration is introduced; process death loses this optional in-memory capture
and never resumes or repeats Core work. Source authentication, physical
verification, profile approval, production inference, full F5/soak gates and F6
authorization remain false. Metadata freezes/renderer and physical qualification
remain separate.
