# ORION-DATA-008 — Persistence, reference event outbox and bounded retention

## OBJECTIVE

Extend the existing action-only database from schema 2 to 3 with F2 domain-record
foundations, a reference-only Domain Event Outbox, durable ingress persistence and
bounded retention. Prove atomic local mutations, duplicate-tolerant consumption,
explicit retry exhaustion and preservation across migrations/reopening.

Baseline: Architecture V3.2 §§10–13, 48, 56–57, 63–73; Master Plan V1.2 §§8.1–8.3,
8.6–8.10. This implements inside those boundaries and does not change authority.

## FILES ALLOWED

`data/database/**` except action-ownership changes owned by DATA-006;
`data/events/**`; `data/tasks/**`; the new retention adapter in
`data/repository/**`; `app/src/androidTest/**/TransactionCorePersistenceTest.kt`;
this work package. Root integration owns shared application configuration.

## CONTRACTS INVOLVED

Room schema/migrations, optimistic concurrency, atomic mutation/event publication,
at-least-once event delivery, durable consumer receipts, minimized payloads,
encrypted field envelopes, durable ingress idempotency and operational retention.

## INVARIANTS INVOLVED

INV-001/004/006/007/008/010/011/014/019/023/033/035. DAO/repository access grants no
Skill authority and never releases the Startup Barrier. No external effect runs.

## TESTS REQUIRED

- Populated schema 2 PENDING/UNKNOWN actions survive migration to 3 unchanged;
  schema 1 traverses both explicit migrations and validates every table.
- Failed event insertion rolls back the originating mutation; competing CAS writes
  have exactly one winner and one resulting fact.
- Owner/event sensitivity and aggregate/version mismatches cannot commit.
- Consumer projection and dedup receipt commit atomically; duplicate delivery and
  reopening preserve the receipt; projection failure leaves no receipt.
- Retry attempts and next delivery time persist before invocation; retry exhaustion,
  interrupted final attempt and poison schema terminate as DEAD_LETTER.
- Encrypted task content round-trips after reopening; raw secret is absent from
  SQLite files and outbox; key absence blocks readback.
- Inferred memory cannot be created confirmed; claims and event share the commit.
- Pruning respects batch limits, review preservation and ingress key retention.
- JVM backoff tests cover exponential limits, jitter, malformed configuration and
  arithmetic overflow. Docker compile/lint/module-boundary checks cover integration.

## ACCEPTANCE CRITERIA

Explicit migration 2→3 registered alongside 1→2; no destructive fallback. Durable
events contain IDs/references only, never copies of user content. All task content
is encrypted by the task adapter with entity/field/schema AAD. Task changes and
their outbox rows are atomic. Consumer receipts and local technical projections
are atomic and bounded to 32 consumers per event. Outbox and ingress admission cap
at 10,000 stored rows; capacity failure never silently evicts unresolved work.

Delivery reserves a durable attempt and backoff before callbacks. Default retry
budget is five attempts with exponential equal jitter from a 1-second base to a
1-hour cap. Unknown schemas/unregistered event types/permanent failures go to
DEAD_LETTER. Pending work is not automatically executed by application bootstrap.

Retention defaults: delivered events and terminal ingress 7 days, terminal actions
and technical audit 90 days, technical events 7 days/10,000 rows. Every invocation
prunes at most 50 parents per table by default (configurable 1–100); receipt cascade
is bounded by the consumer cap. Idempotency retention overrides action/ingress TTL.
UNKNOWN/MANUAL_REVIEW actions, pending/dead-letter events, failed/processing ingress
and review-held audit are preserved. Action audit is removed only with an eligible
terminal action, in the same transaction.

## OUT OF SCOPE

Product Skills, user confirmation gestures, Policy Engine, production action
execution, runtime enabling of mutable ingress, full ordered F4 recovery, OS
scheduler, semantic-memory FTS/ranking, native/runtime hardware qualification,
large temporary-file payload lifecycle, and maintenance scheduling. Reminder/memory
records are initial encrypted storage foundations; full domain semantics,
supersession and complete update/query repositories remain future bounded work.
The DAO accepts trusted opaque ciphertext; it does not authenticate it. Content
producers must use the existing versioned crypto adapter, never raw plaintext.

## Failure/security/upgrade review

Death before transaction commit leaves neither domain mutation nor event. Death
after commit preserves both. Death after consumer commit before delivery ACK may
redeliver; durable consumer receipts prevent duplicate local mutation. Uncaught
consumer bugs retain the previously committed retry attempt, without logging user
payloads. No database/queue deletion is used as recovery. Event callbacks are
trusted local consumers; arbitrary external callbacks do not gain authority.

No FTS or plaintext user-content columns were added. Generic diagnostic/audit
records have technical-code fields rather than arbitrary messages. No initializer,
component, permission, process topology or production bootstrap behavior changes.
Schema migration adds tables/indexes without rewriting or deleting existing
action rows. Physical-device upgrade/kill qualification is still required.

## Validation evidence

Implemented test sources: `TransactionCorePersistenceTest` (14 Android cases),
`EventRetryPolicyTest` (2 JVM cases). Execution results belong to the consolidated
root validation report; source presence alone is not passing evidence.
No physical runtime/Keystore/native evidence is asserted by this package.

F2/TRANSACTION_CORE_READY is not declared by this package. In particular, full
runtime recovery, migration/upgrade with scheduler artifacts, remaining domain
repositories and physical qualification must be evaluated independently.
