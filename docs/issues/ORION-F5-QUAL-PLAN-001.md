# ORION-F5-QUAL-PLAN-001 — Current F5 qualification checklist

OBJECTIVE: Reconcile the historical local Cortex audit with the reviewed current
implementation and prepare the remaining qualification work without inventing
physical results, reopening architecture or duplicating issue #89.

FILES ALLOWED: This work package and
docs/qualification/F5-QualificationChecklist.md only.

CONTRACTS INVOLVED: Architecture V3.2 §§25–35/118/119; Master Plan §§11.9–11.11/31;
ADR-0001 execution environments; accepted ADR-0002 issued request history;
ORION-F5-QUAL-001 (#89) and the F4 stability prerequisite (#64).

INVARIANTS INVOLVED: INV-009/012/013/014/027/028/032/033/036. Historical or synthetic
results never authorize a new production profile or stand in for physical evidence.

TESTS REQUIRED: Read the current gate/evidence records and actual command targets;
check local documentation links, named test selectors and CLI flags against source;
verify the diff is documentation-only and clean. No new Gradle, Android runtime,
hardware or soak result is claimed by this planning package.

ACCEPTANCE CRITERIA: The checklist identifies the reviewed main source and the
obsolete implementation, separates already-resolved software work from remaining
physical/model/native/soak checks, names existing commands with their true scope,
and explicitly identifies missing qualification instrumentation. F5/#89 and F4/#64
remain open until the required evidence and review exist.

OUT OF SCOPE: Runtime changes; a replacement memory/history design; new IPC,
persistence or recovery semantics; installation or mutation of devices; running a
soak; approving profiles; declaring CORTEX_RUNTIME_READY; F6 or release promotion.

RECOVERY/SECURITY/MIGRATION: Documentation only. No executable flow, permissions,
component exposure, logs, durable data or schema is changed.
