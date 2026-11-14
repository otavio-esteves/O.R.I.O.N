# ORION-F5-RUNTIME-EVIDENCE-001 — Offline runtime evidence protocol

OBJECTIVE: Define a bounded, content-free record for one Cortex load/inference/
unload attempt and validate supplied JSON for schema and internal consistency.
The validator does not collect measurements or authenticate supplied identities.

FILES ALLOWED: this work package; docs/qualification/F5-RuntimeEvidenceProtocol.md;
scripts/android/f5_runtime_evidence_protocol.py;
scripts/android/test_f5_runtime_evidence_protocol_harness.py.

CONTRACTS INVOLVED: Architecture V3.2 §§23–30, 99–100, 121; Master Plan
§§11.2–11.11; ADR-0001 and the issued-history scope of ADR-0002; current
OrionCortexWire/OrionInferenceLifecycle, private healthCheck self-PSS and
MAIN-owned complete process inventory. No Android or IPC contract changes.

INVARIANTS INVOLVED: INV-001/002/009/011/012/013/027/028/032/033/036.

TESTS REQUIRED: Pure HOST positive and negative cases for exact external identity
comparison, lifecycle/stream/terminal/unload accounting, same-boot/PID/time and
native-inclusive PSS coverage; strict primitive types, closed fields, duplicate
JSON keys, finite numbers, bounded sizes/depth/counts; no content leakage or
qualification promotion. No Gradle, Docker, ADB or model execution in this package.

ACCEPTANCE CRITERIA: A record and separately supplied expected identity must
match exactly. Successful parsing/comparison always reports UNVERIFIED_INPUT /
NOT_QUALIFIED, never verified provenance, physical observation, a qualified plan
or gate closure. Partial/failed attempts remain visibly incomplete or unsuccessful;
missing memory phases do not become zero readings. Candidate parameters are only
wire-bounded observations, not trusted budgets or admission configuration.

OUT OF SCOPE: Android producer, heavy execution/admission, qualifiedPlans or
profile calibration, device/source/APK/model authentication, installation,
fault injection, restart/rebind execution, physical/performance/native/API/soak
qualification, checklist/CI/preflight changes and architecture changes.

RECOVERY/SECURITY/MIGRATION: No persistent product state or production code is
changed. One boot and stable MAIN/AI PIDs bound a record; death ends that attempt
without a fabricated unload or implicit rebind. Only hashes, fixed enums, counts
and resource/timing metadata are accepted; raw prompts/output/errors/paths and
arbitrary request identifiers are absent. The CLI reads regular bounded JSON
files and emits a fixed diagnostic report without echoing rejected input.

VALIDATION: 18 pure HOST unittest cases pass, including a table of wrong primitive
and container replacements throughout the nested schema and actual offline CLI
success/rejection/symlink cases. `git diff --check` passes. No Docker, Gradle,
Android, ADB or model execution was performed for this package. Tests use synthetic
metadata, not measured resource observations.

STATUS: Software protocol and negative contracts only. Physical producer,
measurement/admission authority and coverage qualification remain separate work
under #89; the Core and post-Cortex soaks remain unclosed.
