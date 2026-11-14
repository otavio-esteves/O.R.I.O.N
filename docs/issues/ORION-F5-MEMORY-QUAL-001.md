# ORION-F5-MEMORY-QUAL-001 — Current memory-source review and physical protocol

OBJECTIVE: Review the current F5 memory evidence path against official Android
contracts and state the measurements needed to qualify it without replacing
missing evidence, reopening architecture or duplicating existing telemetry.

FILES ALLOWED: this work package; docs/qualification/F5-MemoryEvidence.md; its
machine-readable source-review record. Production, tests, IPC and profiles are
read-only in this package.

CONTRACTS INVOLVED: Architecture V3.2 §§33–35 and resource budgets; Master Plan
§11.9 and physical qualification; ADR-0001; current MAIN-owned manifest inventory,
authenticated Cortex healthCheck receipt and complete fresh PSS aggregation.

INVARIANTS INVOLVED: INV-001/002/012/013/027/028/032/033. A memory receipt grants
neither inference authority nor Skill authority. Missing coverage remains unknown.

TESTS REQUIRED: inspect the existing unit/Android cases for timestamp boundaries,
same-boot identity, stale/future values, receipt schema/types/status/PID, every
configured process, unknown coverage and arithmetic limits. Verify documentation
links, source identities and consistency. No new test execution or physical result
is inferred from this source review; execution remains in the integration record.

ACCEPTANCE CRITERIA: identify exactly which current mechanisms establish each
condition; distinguish PSS from RSS/heap; distinguish documented API limitations
from verified behavior; define measurable physical prerequisites and refusal
criteria. Do not claim an unavailable source, approved model plan or closed F5 gate.

OUT OF SCOPE: production implementation, new IPC semantics, process registration,
nativeIncluded relaxation, model/profile calibration, runtime measurement, soak,
release/readiness promotion and the overall qualification matrix.

RESULT: Current source already implements private self-PSS telemetry and a bounded
manifest inventory. No reproduced software defect was identified by this review;
no duplicate provider or structural ADR is introduced. The measurement limitations
and physical protocol are documented in
[F5-MemoryEvidence.md](../qualification/F5-MemoryEvidence.md).
