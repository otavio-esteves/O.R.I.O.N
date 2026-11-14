# ORION-F5-HEAVY-JOURNAL-001 — Durable HOST heavy-soak journal store

Parent: #89; predecessor ORION-F5-HEAVY-SOAK-EVIDENCE-001.

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/105

OBJECTIVE: Store the bounded offline heavy-soak plan/journal durably, with explicit
CAS and post-commit uncertainty. No executor, device command or admission authority.

FILES ALLOWED: This work package; new scripts/android/f5_heavy_soak_journal.py;
new scripts/android/test_f5_heavy_soak_journal_harness.py; new
docs/qualification/F5-HeavySoakJournalStore.md only. Existing validator is unchanged.

CONTRACTS INVOLVED: HOST evidence protocol v1, F4 intent-before-mutation/atomic
journal conventions, Architecture §§32–35 and Master Plan §§11.9–11.11/31; ADR-0001.
This is HOST evidence storage, not app persistence, outbox, IPC or recovery authority.

INVARIANTS INVOLVED: INV-005/006/011/019/027/028/032/036. Supplied Android clocks,
resource/admission records and hashes remain unverified. No journal authorizes work.

TESTS REQUIRED: Pure HOST tests for regular-file/directory anchoring, parent/link
replacement, atomic durability failures before/after rename, flock exclusion,
CAS stale/lost ACK, assigned HOST sequence/session/clock, unchanged plan binding,
process death at critical write boundaries and preservation/inspection of committed
snapshots and temporary files. No Gradle, Docker, device or executor tests here.

ACCEPTANCE CRITERIA: Exclusive owned directory; frozen plan plus canonical digest;
empty initial journal with no invented Android boot/RUN_BEGIN. Closed init/append/
inspect operations. Pin directory chain, use relative NOFOLLOW operations and
file/directory fsync; no pathname fallback. Validate each full candidate before
persistence; ACK only after durable success. Compare expected previous journal
hash under flock. After rename uncertainty, inspect rather than blindly replay.
Never adopt/delete stale temporary files or execute cleanup/resume. All results
remain UNVERIFIED_INPUT / NOT_QUALIFIED, with no real-duration/gate approval.

OUT OF SCOPE: Heavy workload, callbacks, arbitrary commands, ADB, admission or
calibration; changing the validator; app schema/IPC/architecture; automatic recovery,
profile/F5/F6/physical/24-hour approval; shared checklist or CI changes.

RECOVERY/SECURITY/MIGRATION: Directory-owned HOST files only. Failed attempts and
temporary files remain visible; only committed snapshots are inspected. A new
writer process/session creates discontinuity rather than recovering old monotonic
ownership. No input content/raw errors/serials/prompts are echoed in error reports.

VALIDATION: 28 HOST contracts passed, including real SIGKILL before rename, after
rename and after directory fsync, stale CAS/lost ACK, file/dir/close failures,
parent replacement, same-instance thread serialization, init/inspect/close lifecycle
and fork with another thread holding the mutex. Python AST and whitespace checks
passed. No device, Docker, Gradle or workload/executor was run. Canonical checks
remain root integration work.

CENTRAL VALIDATION: Root ran `docker compose run --rm host-harness-check`
in canonical Docker against the clean author worktree at 23c19fc: all 306 cases
passed (278 preceding contracts plus 28 store cases). Root cherry-pick b3ecac6
preserves the implementation byte-for-byte. Independent peer review confirmed the
same-instance CAS/close serialization fix, including fork-before-mutex guards.
No Android/device operation or actual soak was performed.

Retained log: `/home/otavio/Projects/O.R.I.O.N-evidence/f5-next-gates-20261114/logs/heavy-journal-host.log`.
Log SHA256: `417e56d364f35a294ffbefb79b8d2f6c1477991b1868e9fce3f15dea1e7e1ea1`.
