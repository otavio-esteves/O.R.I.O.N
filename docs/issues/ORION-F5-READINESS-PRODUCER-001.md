# ORION-F5-READINESS-PRODUCER-001 — Real read-only pre-resource diagnostic

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/103

OBJECTIVE: Emit a bounded qualification-only Android diagnostic from the actual
MAIN-owned Cortex runtime port, including its exact early refusal or explicit
DECISION_NOT_EVALUATED. Never execute requests or heavy work, including if trusted
profiles become available later.

FILES ALLOWED: OrionCortexRuntimeOwner.kt and narrowly related owner tests;
app/src/cortexReadinessTarget/**; app/src/cortexReadinessAndroidTest/**;
scripts/android/test_f5_cortex_readiness_source_contract_harness.py;
docs/qualification/F5-CortexReadinessProducer.md;
docs/qualification/f5-readiness-producer-evidence.json; this work package. Root owns
app/build.gradle.kts and CI wiring for orionCortexReadinessProbe=true and its
mutual exclusion with other explicit qualification modes.

CONTRACTS INVOLVED: Existing MAIN Core startup/readiness, Cortex early admission
predicates, ResourceGovernor/restart boundaries; Architecture §§23–35, 48, 124;
Master Plan §§11.9–11.11; ADR-0001/0002 unchanged. No new IPC or authority.

INVARIANTS INVOLVED: INV-001/002/009/011/012/013/027/028/032/033/036.

TESTS REQUIRED: Shared early predicates retain exact order and lazy evaluation;
owner query and run agree on early refusal without inventory/database/Binder
access; future configured/Core-ready state returns DECISION_NOT_EVALUATED only.
HOST source contracts forbid requests, model/IPC operations and shadow owners.
Root performs canonical compile/R8 and explicit labelled emulator execution,
including physical-declaration mismatch; no physical claim without a target.

ACCEPTANCE CRITERIA: Use only OrionApplication.mainRuntime -> cortexRuntime().
Null maps to CORE_PORT_UNAVAILABLE without guessing its reason. Only shared
pre-resource checks run; AI/aggregate/governor/restart/model remain NOT_EVALUATED.
Environment comes from actual Build signals, never the requested label. Bounded
same-boot elapsed times and point Core snapshots accompany fixed enums. Every
receipt says NOT_QUALIFIED and all authority/heavy/gate flags are false. The
normal product and other qualification runners/keep scopes remain unchanged.

OUT OF SCOPE: run/newRequestId/load/infer/unload/cancel, binds, model import or
selection, resource evaluation, calibrated parameters, profile injection, private
field reflection, second DB/owner, device execution now, physical/soak closure.

RECOVERY/SECURITY/MIGRATION: No durable schema, IPC, process, policy or recovery
change. The producer never closes the Core-owned runtime. Starting its separate
qualification APK may initialize its own DB/Keystore/WorkManager technical state;
the diagnostic is not a claim of a wholly read-only device or quiescent Core.
Only technical hashes/PID/boot/elapsed/enums leave the APK; no prompts, outputs,
serials, raw Build strings, paths or exception text. Timings and Core snapshots
are observations, never execution permits.

VALIDATION: Nine HOST source contracts pass, and git diff --check passes. Four
new JVM cases and the expanded owner Android gate tests are supplied but await
root's canonical compilation/execution. No Gradle, Docker, Android or device
command was used in this worktree. Conditional build/CI wiring and actual
emulator receipts remain root-owned validation dependencies.

ROOT WIRING: app/build.gradle.kts accepts the strict boolean flag only with
orionQualificationRelease=true and refuses simultaneous baseline/readiness modes.
The required CI retains normal/probe/baseline scopes and builds this mode last.

Cold-start correction: the first real root emulator run exposed a one-time null
Application.mainRuntime read that skipped the requested wait. The bridge now
re-reads that real field and point health on the owning MAIN looper throughout
the same original boot-bound window, with a separate final snapshot. It never
calls Application.onCreate, changes field visibility or starts another owner.
The source-threading regression requires these properties; root retains the
initial run and performs the canonical rebuild/actual emulator rerun separately.

CENTRAL VALIDATION: Root canonical default check/Debug/Release/four Android-test
assemblies passed in 7m09s; 807 current-module JVM report cases /109 suites have
zero failures/errors/skips (cached/up-to-date tasks included). Fixed readiness
R8/native packaging/test assembly passed in 6m34s; ordinary R8 scope passed in
6m59s. Docker HOST after the fix: 287 cases passed. Both invalid standalone flag
and conflicting baseline/readiness flags were actually rejected by Gradle.

Owned API35/x86_64/4KiB emulator: all five owner-gate cases and three ordinary R8
native/JNI/startup probes passed. The fixed actual readiness receipt observed
STARTING -> DEGRADED_SAFE and owner NO_QUALIFIED_PROFILE (four port queries in
217ms), without request/bind/heavy issuance. A declared physical environment was
rejected as mismatching the observed emulator. No AI PID was found after readiness
attempts; ordinary native probes run separately and intentionally start AI.
Default Debug/test and ordinary R8 target/test/mapping exclude the readiness bridge.
The original cached-null receipt and APKs remain retained. No physical observation,
production profile or soak approval; see [the evidence](../qualification/f5-readiness-producer-evidence.json).

After integrating the separately reviewed journal store from main, canonical
Docker HOST harness passed all 315 cases at 25109eb. The readiness runtime/source
files remain identical to the earlier fixed Android/R8 evidence. Raw pidof output
and command status from an additional clean qualification readiness observation
are retained, and the Release APK is frozen beside its matching manifest.
