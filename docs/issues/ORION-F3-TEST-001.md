# ORION-F3-TEST-001 — Shared deterministic-domain ingress isolation

OBJECTIVE: Prove task/reminder/timer/memory/notification kernels safely share one
MAIN Room database and one StartupBarrier, even with identical UUIDs and replay
keys across domains. These are real scoped kernels with synthetic platform input.

FILES ALLOWED: One new Android instrumentation class
OrionDeterministicDomainIsolationTest and this test work package. Production,
existing tests, schema, module dependencies and runtime wiring are unchanged.

CONTRACTS INVOLVED: StartupBarrier, scoped encrypted durable ingress, per-scope
idempotency constraints, independent ActionRequest/confirmation binding, local
owned Skills, read-only domain queries and separate notification invocation.

INVARIANTS INVOLVED: INV-001/004/007/008/014/019/021/022/023/031/035/036. A domain
consumer must not claim, expire, reject or mutate another domain's queued input;
a queue transaction never invokes an external notification poster.

TESTS REQUIRED: Interleaved gated requests persist with zero effects; release and
single-domain drain touches only its own scope; independent confirmations create
only their intended local effect; notification confirmation can commit but durable
consumer/drain never invokes externally; safe queries leave queued states,
attempts, ciphertext and action/event/domain counts unchanged, even after release.
The same UUID/key across domains deduplicates separately rather than conflicts.

ACCEPTANCE CRITERIA: All assertions run against the integrated real kernels and
SQLite with injected clocks/field keys, focused UI owners and a fail-if-invoked
fake notification poster. Canonical build and runtime execution are owned by the
integrating primary agent; no unexecuted test is claimed as evidence.

OUT OF SCOPE: Production behavior, UI/navigation, migrations, platform delivery,
F4 scheduling, new authority, physical S21/OEM qualification and phase-ready claims.
The fixture uses isolated disposable databases and synthetic data only.
