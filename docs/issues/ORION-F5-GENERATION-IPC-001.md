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

Implementation and local validation completed; required PR CI remains the merge gate.

- Docker: complete `check assembleDebug assembleRelease` plus app/service test APKs;
  899 JVM tests, zero failures/errors/skips; dependency checks and lint passed.
- Owned API35 x86_64/4KiB emulator: 77 focused app tests and two service metadata
  tests passed. The resource-witness regression first failed against the old scope.
- Build identity: normal Debug remains mask zero with both properties true; the
  previous global opt-in failed this regression with mask 65535. Missing separate
  qualification identity is rejected during configuration.
- R8 qualification: default-disabled target passed all three runtime probes after
  the variant fix. The opt-in target completed one real pinned-model generation,
  immutable cached FINAL reads and unload. With maxTokens=1, decode counts were
  zero and no decode duration was invented. The initial fixture path failed the
  canonical-file guard; the app mount namespace alias passed without weakening it.
- Evidence, source hashes, exact installed artifact checks and environment limits:
  [evidence index](../qualification/f5-generation-metrics-evidence.json).

No physical, performance, profile, 24-hour, complete F5 or F6 gate is claimed.
The existing MAIN owner producer is a separate work package in issue #123.
