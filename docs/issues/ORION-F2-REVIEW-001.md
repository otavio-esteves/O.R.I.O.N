# ORION-F2-REVIEW-001 — Independent review regressions

## OBJECTIVE
Resolve concrete independent-review findings before integrating PR #21: preserve
claim validity during supersession, obtain the SQLite writer transaction before
execution-time authorization/clock snapshots, and align failure logs with published
readiness. References: issue #20; Architecture §§49–57/63–73/96–100; Master Plan §8.

## FILES ALLOWED
`data/database/**/OrionRecordDao.kt`; `data/actions/**/OrionActionExecutionStore.kt`;
`app/src/androidTest/**/DomainRecordStoreTest.kt` and
`ActionExecutionOwnershipTest.kt`; `app/src/main/**/OrionFoundationRuntime.kt`;
`core/ingress/**/StartupBarrier.kt` and its coordinator tests;
`app/src/main/**/CoreReadyLogEvent.kt`;
`app/src/test/**/CoreReadyLogEventTest.kt`; qualification docs and this package.

## CONTRACTS INVOLVED
Memory claim validity/supersession; atomic mutation/event; exact confirmation
binding/expiry; active execution ownership; trusted authority revalidation; Room
writer transactions; structured health/readiness logging.

## INVARIANTS INVOLVED
INV-005/006/007/010/011/018/019/021/022/023/036.

## TESTS REQUIRED
- Supersession preserves an earlier claim expiry, caps future/no-expiry claims and
  rejects empty validity intervals without partial writes/events.
- Writer contention followed by confirmation expiry cannot claim an action.
- Writer contention followed by lease expiry cannot commit a terminal callback;
  successful claims obtain a fresh active lease after admission.
- A writer holding the database can read a barrier snapshot while an admitted
  ingress waits for that writer, without lock inversion; admission remains serialized.
- A failed optional key stage publishes/logs DEGRADED_SAFE/CORE_DEGRADED, whereas
  failed critical storage/configuration logs CORE_BLOCKED; no exception content.
- Focused Docker unit/build/lint/boundary checks, Android regressions, required CI
  on the final PR head and review of the fixes.

## ACCEPTANCE CRITERIA
No temporal claim extension or invalid interval; claim/finish snapshots are taken
inside the admitted writer transaction; no stale permission/confirmation snapshot
survives SQLite lock waiting; logs agree with readiness. No schema/IPC/process or
external-effect behavior changes. Record independent reviews and CI evidence.

## OUT OF SCOPE
F3 Skills/product commands; F4 operational recovery/scheduler; S21 physical tests
(explicitly deferred by the user on 2026-11-04); architecture changes; blind retry.
