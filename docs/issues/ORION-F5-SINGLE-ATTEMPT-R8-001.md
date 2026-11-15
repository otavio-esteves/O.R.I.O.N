# ORION-F5-SINGLE-ATTEMPT-R8-001 — existing-owner qualification entry

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/128.
Base: 8fb0b81; issue127 core is a separately reviewed dependency.

OBJECTIVE: Compose one optional bounded observer into the existing MAIN owner
only for an explicit qualification variant, then expose separate framework-typed
prepare/execute/observe/release phases. Current empty/null configuration must
produce a real prepare refusal with no owner run or fabricated cycle receipt.

FILES ALLOWED: app/build.gradle.kts; FoundationRuntime observer composition only;
new qualification-only bridge/keep/Java runner; focused tests; one CI build step;
this work package and qualification note. No Owner/Client/driver/projector edits,
factory replacement, bootstrap/scheduler/DB changes, profile/template/timing
values, native/service/IPC or dependency changes.

CONTRACTS INVOLVED: Same Application.mainRuntime/FoundationRuntime/cortexOwner;
issue123/125/127 functional/manual phases; unchanged architecture and accepted
ADR0001/0002/0003. The build flag changes observation composition, not admission.

INVARIANTS INVOLVED: INV-002/009/011/012/013/014/023/027/028/032/033/035/036.

TESTS REQUIRED: Strict flags/exclusive modes; ordinary Debug/Release false and
bridge absent even with qualification properties; actual same owner singleton
and default observer absent. Cold MAIN publication/Core wait; target/process/
thread/argument checks; no configuration/workload input. Real R8 current refusal:
prepare1/execute0/read0/NO_QUALIFIED_PROFILE/partial absent, no driver bind/model/
inference. Mechanical positive core/projection tests stay in127/125. Root owns
canonical build/Android/R8/CI and source/artifact evidence.

ACCEPTANCE CRITERIA: Strict orionCortexSingleAttempt defaults false in normal
variants, override only qualificationRelease with separate identity and scoped
source/keep/runner. Excludes baseline/readiness/exit modes; metrics remains an
independent opt-in. FoundationRuntime may attach only failure-contained capacity1
final observer to its existing cached owner; plans/timing/template remain omitted.
Context-only bridge has one private driver/candidate, no caller configuration or
identifiers. One prepare/execute/manual-read/release, preserving original errors;
no read in execution/cleanup/exception propagation and no extra owner/client/DB,
worker, clock, rebind/retry/fallback. Closed primitive status header<=1KiB,
optional125 partial<=8192bytes, total<=10KiB; no IDs/hash/path/content/timestamps
or raw exceptions. All approval/authentication/phase flags remain false.

OUT OF SCOPE: Core127 changes, production activation, parameter/profile/template
enrollment, import/exporter/loop/soak, missing timing reconstruction, physical
calibration/qualification, F5/F6 closure or new authority/IPC/ADR.

VALIDATION: Ten HOST source-boundary tests pass; two Android normal-target tests
supplied, not executed here. Source/link/diff checks pass and independent
source-only review approved the final bridge/runner/composition. No
Kotlin/Gradle/Docker/ADB/PR or device work by this author;
root performs canonical validation after handoff. See
[F5-SingleAttemptR8](../qualification/F5-SingleAttemptR8.md) for exact framing,
UTF-8 versus Parcel bounds and unavailable/current-refusal semantics.
