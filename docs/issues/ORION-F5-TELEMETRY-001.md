# ORION-F5-TELEMETRY-001 — Fresh private Cortex memory receipt

OBJECTIVE: Validate schema1 healthCheck memory receipts from the currently bound
private Cortex service into MAIN-owned process memory evidence. Complete inventory
must be attested by the trusted MAIN lifecycle/manifest owner, never by the receipt.

FILES ALLOWED: app/resources/OrionCortexMemoryTelemetry.kt and focused tests; this
issue. Observer/client/service wiring belongs to the coordinating agent.

CONTRACTS INVOLVED: ResourceGovernor observation, versioned bounded IPC, boot-bound
monotonic freshness and complete native-inclusive application PSS accounting.

INVARIANTS INVOLVED: INV-012, INV-018, INV-027, INV-028, INV-033.

TESTS REQUIRED: valid MAIN+AI receipt; malformed schema/status/name/pid/bytes/native
coverage; stale/future/other-boot/missing boot; incomplete/extra/oversized inventory;
aggregation with fresh MAIN includes every attested process.

ACCEPTANCE CRITERIA: no incomplete or stale receipt is promoted; no fabricated
cross-process ActivityManager freshness; evidence contains the AI reading only and
requires the existing aggregator to supply an independent fresh MAIN reading.

OUT OF SCOPE: observer/client wiring, Android process inventory discovery, policy
or profile calibration, model loading, physical/hardware qualification.

## Implementation scope

The adapter validates actual Bundle primitive types and encoded Parcel size using
the existing Cortex envelope guard. It accepts only schema1/OK with matching AI
identity, positive PID and PSS, explicit native inclusion and a fresh same-boot
monotonic timestamp. A pinned expected PID, when supplied, must match exactly.
The optional absent PID is reserved for a newly authenticated private connection.

Its process inventory comes exclusively from the trusted MAIN owner. Only exactly
MAIN+AI is supported; additional processes or an incomplete inventory return no
evidence. The adapter returns an immutable AI reading and inventory snapshot;
the existing aggregator still requires a separately sampled native-inclusive MAIN
reading. It never probes cross-process ActivityManager PSS, starts/restarts Cortex,
authorizes inference, or declares that complete inventory is currently available.

Eight JVM tests and four Android Bundle/Parcel cases are supplied. The parent
serializes canonical builds/execution. No execution or hardware claim is recorded
by this package's implementation alone.

## Consolidated validation

Canonical checks, scoped emulator results and retained failed attempts are recorded
in [F5 Runtime Gate](../qualification/F5-RuntimeGate.md) and its
[evidence record](../qualification/f5-runtime-evidence.json). That report supersedes
implementation-time validation-pending notes without changing this work package's
scope. Final review corrections/reruns, physical qualification and production
resource profiles retain the explicit status recorded there; no per-issue test
count or phase closure is inferred.
