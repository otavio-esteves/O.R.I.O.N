# ORION-F5-QUAL-PLAN-001 — Current F5 qualification checklist

REVIEW RENEWAL: 2026-11-15, tracked by
[#135](https://github.com/otavio-esteves/O.R.I.O.N/issues/135). Reviewed main
2226b77c8293ae0544ba34fcdbca1e43cb932d80 through PR #131 and the separate
single-attempt R8 candidate cce5ece43bff1954a347ee3926665719e9c67cd5 / PR #134.
PR #134 remains pending integration and required CI at this review; its presence
in the reviewed branch is not main integration or gate approval.
The user confirmed on this date that no physical devices are available. Physical
S21/API37 qualification, measured profile calibration and real-duration stability
remain blocked; this renewal starts no physical run or soak and closes no gate.

OBJECTIVE: Reconcile the historical local Cortex audit with the reviewed current
implementation and prepare the remaining qualification work without inventing
physical results, reopening architecture or duplicating issue #89.
Reconcile delivered partial metrics/resource/cycle/single-attempt sources with
the still-missing complete RuntimeEvidence v1 producer and heavy-work executor.

FILES ALLOWED: This work package,
docs/qualification/F5-QualificationChecklist.md and the related
docs/qualification/audit-20261115-evidence.json index only. The index may add an
explicit active-module inventory correction; its original archive, artifact hash,
payload manifest, raw reports, logs and raw counts must remain unchanged.

CONTRACTS INVOLVED: Architecture V3.2 §§25–35/118/119; Master Plan §§11.9–11.11/31;
ADR-0001 execution environments; accepted ADR-0002 issued request history;
accepted ADR-0003 optional bounded read-only generation metrics, within its exact
superseded clauses only;
ORION-F5-QUAL-001 (#89) and the F4 stability prerequisite (#64).

INVARIANTS INVOLVED: INV-009/012/013/014/027/028/032/033/036. Historical or synthetic
results never authorize a new production profile or stand in for physical evidence.

TESTS REQUIRED: Read the current gate/evidence records and actual command targets;
check local documentation links, named test selectors and CLI flags against source;
verify the diff is documentation-only and clean. No new Gradle, Android runtime,
hardware or soak result is claimed by this planning package.
Review main-integrated packages separately from PR #134's candidate composition;
retain #130 HOST capture and #132 passive lifecycle work as open at this review.
Reconcile archived JVM reports against Git-tracked build.gradle.kts module paths;
verify the legacy exclusions and active totals independently from the immutable
archive. Check that its SHA-256, original manifest and raw counts are preserved.

ACCEPTANCE CRITERIA: The checklist identifies the reviewed main source and the
obsolete implementation, separates already-resolved software work from remaining
physical/model/native/soak checks, names existing commands with their true scope,
and explicitly identifies missing qualification instrumentation. F5/#89 and F4/#64
remain open until the required evidence and review exist.
ADR-0003 is identified as accepted and its delivered implementation as optional
telemetry, without inventing complete RuntimeEvidence v1, a heavy-soak executor,
trusted template/profile, physical calibration, 24-hour evidence or F6 authority.
The audit index retains raw 990 cases/124 suites and records the correction to
937 active cases/121 suites, excluding 53 legacy cases in three archived XMLs.
The later F full-check total of 952/122 is a separate result adding the 15-case
lifecycle-facts suite; it does not replace the valid earlier F scoped 264-case
snapshot or add those 264 again. Corrected inventory is not a new test execution.

OUT OF SCOPE: Runtime changes; a replacement memory/history design; new IPC,
persistence or recovery semantics; installation or mutation of devices; running a
soak; approving profiles; declaring CORTEX_RUNTIME_READY; F6 or release promotion.

RECOVERY/SECURITY/MIGRATION: Documentation only. No executable flow, permissions,
component exposure, logs, durable data or schema is changed.
