# ORION-F5-PHYSICAL-BASELINE-001 — Unloaded physical resource baseline

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/96

OBJECTIVE: Prepare an opt-in, measurement-only resource baseline using the actual
MAIN observer, authenticated AI self-PSS receipt and manifest inventory without
loading a model, fabricating resources or granting production inference.

FILES ALLOWED: app/build.gradle.kts; .github/workflows/ci.yml; app/src/physicalBaselineTarget/**;
app/src/physicalBaselineAndroidTest/**; this work package and the existing F5
qualification checklist; coordinating scripts/android/record-f5-physical-baseline.py
and test_f5_physical_baseline_harness.py; the explicit required_artifacts extension
in prepare-f5-physical-qualification.py and its focused contracts. The coordinating
agent owns those HOST files and preserves the original model-required default.
Source-boundary regressions are in test_f5_physical_source_contract_harness.py.

CONTRACTS INVOLVED: Architecture §§25–35; Master Plan §11.9; ADR-0001;
ORION-F5-QUAL-001/#89 and ORION-F5-MEMORY-QUAL-001. Existing AIDL and telemetry
semantics are unchanged.

INVARIANTS INVOLVED: INV-001/002/009/011/012/013/027/028/032/033/036.

TESTS REQUIRED: Canonical compilation/R8 of the explicit measurement scope;
verify the normal Debug/Release and ordinary three-probe R8 APKs contain no bridge
or extra keep rule; inspect unchanged normal source selection. The root executes
the explicit physical-environment rejection on its owned emulator. HOST contracts
validate the closed receipt schema, timestamps and blocked qualification semantics.
No actual physical measurement or hardware result is claimed without a target.

ACCEPTANCE CRITERIA: orionPhysicalBaseline=true requires
orionQualificationRelease=true and selects a distinct framework-only runner in
network.orion.qualification. Normal Debug/Release and the original R8 probes are
preserved. Before binding, validate target identity, 1–10 samples, 100–1000ms
interval and the full MAIN+AI manifest inventory; do not remove Debug probes to
make inventory pass. A qualification-only bridge and keep rule exist only in the
explicit scope. Real telemetry is fresh, same-boot, PID-bound and native-inclusive;
the AI remains UNLOADED/nativeRuntimeLoaded=false before and after every sample.
Emit bounded technical observations and NOT_QUALIFIED only; missing or stale data
cannot become zero, a fabricated receipt or profile approval.

OUT OF SCOPE: Model import/load/inference; resource profile creation or approval;
new IPC/components/permissions/variants/ABIs; product schema, processes, authority
or scheduling changes; physical execution now; F5/F6/native/soak gate closure.

RECOVERY/SECURITY/MIGRATION: Only opt-in qualification source/build selection is
added. The runner owns one lightweight binding, unbinds it on completion/failure
and never unloads or cancels existing work. Busy/resident AI is rejected. Technical
PIDs and memory/resource values are observed without prompts, file paths, content,
exit descriptions or traces. Measurements of this separate APK do not qualify the
production identity, model workload or 24/7 operation.

The measurement is not wholly read-only: am instrument starts the separate target
APK, whose ordinary Application bootstrap may create its own Room database,
Keystore material and technical WorkManager state. This is owned qualification
state, not the production identity's state. The Core may still be initializing;
the probe proves only that AI stays UNLOADED/nativeRuntimeLoaded=false, not that
the Core or device is quiescent. No settings or model/inference operation is issued.

Runner review hardening: the request label is separate from the observed
environment. Rejections before physical validation report UNVERIFIED, and direct
bridge entry revalidates Build before emitting any physical sample. Every bind
attempt is released, including a false return or SecurityException, following the
[Context.bindService contract](https://developer.android.com/reference/android/content/Context).
An unregistered cleanup is explicitly non-successful (CLEANUP_NOT_REGISTERED),
never promoted to a completed observation. Positive schema fields are unchanged.

Six HOST source-boundary regressions pass on the corrected source. The same
contracts produce five expected failures/errors against the pre-review 5fbd769
snapshot; the no-heavy-work contract already passed there. All 165 HOST harness
cases in this branch pass. These checks establish source/build boundaries, not a
physical observation; root still compiles/R8-checks and runs the actual owned
emulator rejection case before integration.

CI COVERAGE: The required docker-build-and-check job retains its ordinary builds
and three-probe R8 scope, then builds the opt-in baseline sequentially in canonical
Docker. It performs no device execution or physical qualification.

CENTRAL VALIDATION (root, 2026-11-14): Canonical Docker opt-in R8/native packaging
and test assembly passed in 13m46s. Default canonical check/Debug/Release/four
Android-test APK assemblies passed in 10m16s; 803 JVM report cases have zero
failures/errors/skips (cached/up-to-date tasks included). Ordinary qualification
R8/native packaging/test assembly passed in 2m44s. Docker HOST: 195 cases passed.
The invalid standalone physical flag was actually refused by Gradle (expected
exit 1). Final Debug/Release/ordinary qualification APKs exclude the baseline
bridge/runner; the opt-in target keeps the bridge and selects only its own runner.

Owned API35/x86_64/4KiB emulator: all three ordinary R8 native/JNI/startup probes
passed after correcting an observed SystemUI ANR dialog. The initial failed and
subsequent interrupted attempts remain retained. The actual opt-in framework
runner rejected the emulator before binding: UNVERIFIED, REJECTED,
PHYSICAL_ENVIRONMENT_REJECTED, zero samples and all approval flags false. No AI
process was found afterward. This is a negative Android guard test, not a physical
collection or gate PASS. See [the preparation evidence](../qualification/f5-physical-baseline-preparation-evidence.json).

No S21/API37 physical positive path, model workload, MemoryLimiter stimulus,
profile approval or soak has run. The retained APKs are software-validation
snapshots, not a frozen physical qualification candidate.
