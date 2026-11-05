# ORION-F3-TASK-008 — Bounded read-only task query

Issue: https://github.com/otavio-esteves/O.R.I.O.N/issues/39

OBJECTIVE: Provide the read-only bounded task query needed by the task screen, with strict decryption/canonical validation and explicit unreadable content.
FILES ALLOWED: core/queries task contracts and tests; data/tasks read adapter; task DAO pagination query; related tests/dependencies and evidence.
CONTRACTS INVOLVED: GetTasks Query, selected DTO, task schema-v1 encrypted content, stable UUID cursor pagination. Architecture command/query separation and data ownership apply.
INVARIANTS INVOLVED: INV-001/011/019/033/035.
TESTS REQUIRED: page bounds/cursor, immutable/redacted result, encrypted valid/corrupt/legacy/key-unavailable content, stable pagination, no mutation during query; Docker build/lint/boundaries and Android repository tests.
ACCEPTANCE CRITERIA: Query returns bounded selected task DTOs and explicit unreadable rows; never changes state or logs titles; no Room handle crosses Core API.
OUT OF SCOPE: Mutation/runtime/UI integration (following package), schema change, scheduling/AI/voice and physical qualification #23.

PROCESS DEATH / SECURITY / MIGRATION: No writer, action, event, queue drain, pruning or migration is invoked. Selected title content stays in memory; failure is explicit, with no plaintext fallback. Stable UUID pagination does not promise a snapshot across concurrent page requests. Physical evidence remains deferred to #23.

VALIDATION: Docker builds/lint/query JVM tests and all 36 dependency assertions passed. Three API35 emulator repository cases passed using matching app/test APKs. Evidence: docs/qualification/f3-task-query-build-summary.json. Required remote CI remains a merge prerequisite.
