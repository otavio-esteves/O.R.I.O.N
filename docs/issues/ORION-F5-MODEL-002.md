# ORION-F5-MODEL-002 — MAIN model registry import coordinator

OBJECTIVE: Bind verified private model files to schema6 registry CAS transitions,
reconcile interrupted imports, invalidate unreadable READY entries and open only
revalidated matching READY files through read-only descriptors.

FILES ALLOWED: repository/models coordinator/storage helpers; focused app model
instrumentation tests; this issue. DAO/schema changes are owned separately.

CONTRACTS INVOLVED: Model Registry, GGUF trust flow, MAIN ownership, CAS, bounded
file recovery. This is trusted technical infrastructure, not a mutable Skill or UI
entry point. A future user-facing import flow must provide its own authorized
ingress; these methods confer no business-action or inference authority.

INVARIANTS INVOLVED: INV-001, INV-011, INV-012, INV-013, INV-019, INV-033.

TESTS REQUIRED: real SQLite import and READY transition, no READY after interruption,
pending-row recovery, missing/tampered READY invalidation, bounded orphan cleanup,
read-only descriptor and dedup.

ACCEPTANCE CRITERIA: private atomic publication precedes registry READY; interrupted
states reconcile conservatively; per-pass work and total registry/file counts are
bounded; load requires current READY identity and complete file revalidation.

OUT OF SCOPE: UI, automatic inference, native loadability, authority/confirmation,
physical/performance qualification, migration implementation owned by registry work.

## Implementation boundaries

- File publication precedes IMPORTED→VALIDATING→READY CAS. A process interruption
  can leave a temp, an unregistered verified file, or a pending registry row. None
  authorizes inference. Recovery validates pending records and current READY files;
  missing, mismatched or structurally invalid records become QUARANTINED.
- READY is integrity/structural readiness, distinct from native semantic loading and
  the qualified resource plan. Recovery never loads or infers.
- Registry admission caps at128 rows; the file store caps at32 verified files and
  protects its disk reserve. At capacity, explicit new import is rejected. Existing
  matching READY files remain readable; uncertain/quarantined rows are preserved.
- A read-only descriptor is opened with O_NOFOLLOW and checked as a regular file.
  Hashing and GGUF parsing use that opened descriptor, followed by a rewind. Current
  registry identity/status/metadata are checked again before returning it. Full
  validation cost scales with model size; no latency qualification is claimed.
- Recovery and cleanup use bounded pages and explicit cursors. Unregistered verified
  files are removed; registered invalid/quarantined files are preserved for review.
  Metadata display names derive only from a static prefix and hash, never user text.
- Seven Android tests cover actual SQLite CAS/files/descriptors and synthetic
  interruption checkpoints. These are not actual process-death or physical tests.
  Canonical execution is serialized by the parent agent.
- Subsequent recovery review distinguishes missing/corrupt files from transient
  storage failures. Permission/descriptor/IO unavailability preserves READY or
  VALIDATING and reports a recovery conflict; it never permanently quarantines an
  otherwise valid model. Parser IO propagates separately from structural rejection.
  Descriptor ownership is retained until the final registry read succeeds. An
  eighth Android test revokes/restores a private fixture's file access to prove
  non-destructive handling; no physical/runtime execution is claimed here.
- A previously completed startup scan does not cover subsequent interrupted
  imports. `recoverSelected(modelId)` revalidates exactly one pending/READY row
  under the existing coordinator lock, without source reopening, unrelated-row
  changes, native loading or inference. Transient storage failures preserve the
  row. The caller still rejects the current attempt and requires a fresh request
  and resource decision after recovery; this is not cached execution authority.
  A focused Android regression supplies later IMPORTED and VALIDATING rows after
  completed empty/full scans and checks exact recovery and unrelated preservation.

## Consolidated validation

Canonical checks, scoped emulator results and retained failed attempts are recorded
in [F5 Runtime Gate](../qualification/F5-RuntimeGate.md) and its
[evidence record](../qualification/f5-runtime-evidence.json). That report supersedes
implementation-time validation-pending notes without changing this work package's
scope. Final review corrections/reruns, physical qualification and production
resource profiles retain the explicit status recorded there; no per-issue test
count or phase closure is inferred.
