# ORION-F5-READINESS-PRODUCER-001 — Real read-only pre-resource diagnostic

OBJECTIVE: Emit a bounded qualification-only Android diagnostic from the actual
MAIN-owned Cortex runtime port, including its exact early refusal or explicit
DECISION_NOT_EVALUATED. Never execute requests or heavy work, including if trusted
profiles become available later.

FILES ALLOWED: OrionCortexRuntimeOwner.kt and narrowly related owner tests;
app/src/cortexReadinessTarget/**; app/src/cortexReadinessAndroidTest/**;
scripts/android/test_f5_cortex_readiness_source_contract_harness.py;
docs/qualification/F5-CortexReadinessProducer.md; this work package. Root owns
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

VALIDATION: Eight HOST source contracts pass, and git diff --check passes. Four
new JVM cases and the expanded owner Android gate tests are supplied but await
root's canonical compilation/execution. No Gradle, Docker, Android or device
command was used in this worktree. Conditional build/CI wiring and actual
emulator receipts remain root-owned validation dependencies.
