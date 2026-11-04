# ORION-DATA-010 — Encrypted reminder and memory repositories

OBJECTIVE: complete F2 storage adapters and guarded lifecycle mutations for
reminders and memories without enabling Skills, scheduling, ranking or FTS.

FILES ALLOWED: data/database/OrionRecordDao.kt; data/reminders/**; data/memory/**;
app instrumentation and test dependencies; this package and qualification reports.

CONTRACTS INVOLVED: Architecture §§63–73; Master Plan §§8.1–8.3/8.7;
atomic mutation/event, CAS, encrypted fields, memory provenance and supersession.

INVARIANTS INVOLVED: INV-001/007/008/010/011/019/033/035.

TESTS REQUIRED: authenticated round-trip/reopen; no raw content; missing keys;
bounded queries; pending reminder edits/cancellation with stale-version rejection;
atomic supersession with claims/history; rejected inferred confirmation; memory
deletion removes claims/content; failed outbox insert rolls back all mutations;
concurrent CAS has one winner. Build, lint and dependency gates in Docker.

ACCEPTANCE CRITERIA: every mutation publishes reference facts in its transaction;
encrypted content is bound to entity/field/schema; no query mutates state;
supersession preserves prior history and CAS; deletion removes derived claims;
queries are bounded; no implicit inferred confirmation or sensitive indexing.

OUT OF SCOPE: F3 commands/Skills, F4 alarms/delivery, F7 ranking/FTS/consolidation,
user confirmation authority, product wiring and physical-device qualification.

Validation: CONTAINER `:app:assembleDebug :app:assembleDebugAndroidTest
:data:reminders:lint :data:memory:lint :data:database:lint verifyModuleBoundaries
--max-workers=2` passed. ANDROID_EMULATOR API35: all seven DomainRecordStoreTest
cases passed, including CAS concurrency and supersession rollback. No schema
change or Android component/permission was introduced. Deletion removes encrypted
content and claims logically; physical SQLite page erasure is not claimed.
