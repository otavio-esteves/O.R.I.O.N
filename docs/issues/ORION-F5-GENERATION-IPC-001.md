# ORION-F5-GENERATION-IPC-001 — optional Cortex metrics

Issue: [#119](https://github.com/otavio-esteves/O.R.I.O.N/issues/119).

- Objective: implement the exact accepted ADR-0003 optional Cortex extension.
- Files allowed: ai/api contracts/AIDL/codec/tests; explicit ai/llama service
  observation port; ai/service request-local adapter/build opt-in/tests; existing
  MAIN client optional reader/tests; focused CI and evidence documentation.
- Contracts: accepted ADR-0003, unaffected ADR-0002/0001, Architecture V3.2
  §§22/23/32/33/35/99, Master Plan §§6.3/11.11.
- Invariants: INV-002/009/011/012/013/027/028/032/033/035/036.
- Tests: closed schema/Parcel/mask/actual admission bounds; old envelopes and
  peers; source/worker finality; retained identity/retirement; no query-triggered
  work; optional failures; bounded same-runtime adapter; build/lint/R8 and owned
  emulator integration. Record exact source/artifacts and environment.
- Acceptance: default-disabled source, opt-in actual owned measurements,
  immutable bounded snapshots, current endpoint correlation, unchanged functional
  authority/outcomes, required CI green before merge.
- Out of scope: production profiles, new runtime/process/model, persistence,
  polling, policy/restart changes, physical/24h qualification, F6.

Implementation/validation in progress. No hardware gate is claimed.
