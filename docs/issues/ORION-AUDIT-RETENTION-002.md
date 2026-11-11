# ORION-AUDIT-RETENTION-002 — One observable operational retention policy

OBJECTIVE: Remove hidden pruning from all four local domain recovery kernels so
Core applies the declared retention policy once per recovery/maintenance pass and
reports every operational deletion and required continuation. Preserve bounded
startup cleanup before the original ingress drain so a full outbox can recover.

FILES ALLOWED: task/reminder/timer/memory kernel recovery methods; Core runtime
retention wiring, snapshot reader and bounded accounting; repository retention documentation;
focused app retention tests; this work package and its validation record.

CONTRACTS INVOLVED: existing OrionRetentionPolicy, bounded OrionRetentionMaintenance,
RecoveryCoordinator, maintenance snapshots and continuation; Architecture §101;
Master Plan §8.9. Retention eligibility and durable schema remain unchanged.

INVARIANTS INVOLVED: INV-005, INV-006, INV-007, INV-008, INV-019, INV-035, INV-036.

TESTS REQUIRED: every kernel's action recovery leaves technical history intact;
the configured 10,000-row limit retains recent records beyond the former 2,000 cap;
actual Room/SQLite batches report precise deletes and continuation; TTL and count
cleanup survive DB reopen; recovery deletions join the next maintenance snapshot;
UNKNOWN/MANUAL_REVIEW and protected audit remain intact.

ACCEPTANCE CRITERIA: only Core's recovery/maintenance owner calls the operational
retention adapter; its results exactly match SQLite changes and snapshot metrics;
no hidden pruning remains in any domain recovery; narrow and canonical checks pass.

OUT OF SCOPE: retention threshold changes, schema/migrations, business deletion,
external delivery, scheduling authority, physical soak gates and publishing.
