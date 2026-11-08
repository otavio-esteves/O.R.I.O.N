# ORION-F4-DIAG-001 — Observable qualification and maintenance evidence

OBJECTIVE: Expose the build's native qualification decision and available memory
limiter classification in read-only diagnostics. Emit bounded, strictly technical
maintenance snapshots and distinguish a real periodic worker completion from mere
heartbeat registration, allowing passive host collection without opening Room or
starting MAIN from the collector.

FILES ALLOWED: core/health/** and core/observability/** selected diagnostic models
and focused tests; app build-time qualification resource, MAIN diagnostics wiring,
maintenance worker and snapshot reader; feature/diagnostics/**; related tests/docs.

CONTRACTS INVOLVED: Master Plan §10.10; MAIN Room ownership; bounded observability;
WorkManager as deferrable delivery mechanism; native qualification is separate from
runtime loading or performance. Diagnostic records never authorize execution.

INVARIANTS INVOLVED: INV-001/006/011/014/015/016/023/027/029/030/032/033/035/036.

TESTS REQUIRED: Unknown/failing native metadata cannot appear approved; absent exit
history differs from no observed limiter signal. Snapshot vocabulary/count bounds,
privacy, actual heartbeat identity matching and failure/unavailable samples. Run
focused Docker unit/build checks and emulator diagnostics/maintenance assertions;
run canonical checks before merge.

ACCEPTANCE CRITERIA: Build embeds only validated qualification status. UI distinguishes
dedicated reason availability, fallback classification and unavailable history.
Technical snapshots contain only generated observation IDs, time/boot/process,
closed status/origin enums and numeric aggregate counts/pruning/storage sizes.
Snapshots are log-buffer diagnostics, not durable business truth, execution leases
or new IPC. Errors never change recovery/execution decisions. No identifiers of
domain rows, ciphertext, user payloads, descriptions or exception text are logged.

OUT OF SCOPE: Changing policy, startup readiness, persistence strategy, Room schema,
worker scheduling semantics, inference/resource authorization, adding an exported
component, continuously waking/reviving MAIN, closing physical/24-hour/native gates.
