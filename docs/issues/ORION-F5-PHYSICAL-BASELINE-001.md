# ORION-F5-PHYSICAL-BASELINE-001 — Unloaded physical resource baseline

OBJECTIVE: Prepare an opt-in, measurement-only resource baseline using the actual
MAIN observer, authenticated AI self-PSS receipt and manifest inventory without
loading a model, fabricating resources or granting production inference.

FILES ALLOWED: app/build.gradle.kts; app/src/physicalBaselineTarget/**;
app/src/physicalBaselineAndroidTest/**; this work package and the existing F5
qualification checklist; coordinating scripts/android/record-f5-physical-baseline.py
and test_f5_physical_baseline_harness.py; the explicit required_artifacts extension
in prepare-f5-physical-qualification.py and its focused contracts. The coordinating
agent owns those HOST files and preserves the original model-required default.

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
