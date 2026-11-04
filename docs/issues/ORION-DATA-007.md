# ORION-DATA-007 — Transactional ingress and explicit Startup Barrier

## OBJECTIVE
Add a durable ingress coordinator with an explicit operational Startup Barrier,
separate from declarative health readiness. Persist while gated when storage is
available; never drain while gated. Preserve the existing Foundation runtime's
unavailability until all production recovery and authorization gates are met.

## FILES ALLOWED
`core/ingress/**`, `core/recovery/**`, ingress adapter in `data/repository/**`,
related tests, integration test migration references, and qualification docs.

## CONTRACTS INVOLVED
Architecture V3.2 §§48–49; Master Plan V1.2 §§8.6, 8.11, 10.6–10.7;
IngressCoordinator, Startup Barrier, durable idempotency and recovery order.

## INVARIANTS INVOLVED
INV-007/008/014/019/023/033/035. Readiness is not execution authority.

## TESTS REQUIRED
Durable enqueue while gated; unavailable storage; dedup/conflict propagation;
no draining before actual release even with READY health; concurrent gate closure;
recovery failure and supersession; priority/expiration and transaction rollback;
module boundaries, JVM tests, Android compilation, lint and emulator harness.

## ACCEPTANCE CRITERIA
Barrier release requires completed recovery and eligible readiness. Gate closure
and batch admission are serialized. Store-level draining independently enforces
the gate. Processing is bounded and permits only a trusted transactional local
Core consumer. Neither external execution nor mutable product UI is enabled.

## OUT OF SCOPE
F3 Skills, scheduler F4 operational reconciliation, Cortex, voice, physical-device
qualification. No TRANSACTION_CORE_READY before every F2 gate is evidenced.

## Limits
The ordered recovery runner is an orchestration primitive, not a production graph.
The Foundation runtime remains unchanged: mutable ingress/actions are UNAVAILABLE.
A first queued item blocked by key availability or clock rollback stops the batch;
this fail-closed policy preserves priority and requests, but requires production
health/recovery handling before enabling command ingress. Payloads above 256 KiB
are rejected; referenced private-file lifecycle is not implemented in this increment.
