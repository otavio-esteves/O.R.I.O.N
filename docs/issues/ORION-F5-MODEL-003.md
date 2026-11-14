# ORION-F5-MODEL-003 — Explicit removal of rejected model artifacts

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/92

OBJECTIVE: Allow trusted MAIN technical management to release the registry/file
capacity held by explicitly selected INVALID or QUARANTINED entries. A subsequent
import requires fresh input and the complete existing validation pipeline.

FILES ALLOWED: repository/models import coordinator and private-file helper;
OrionModelRegistryDao; focused repository JVM and app model Android tests;
verify-transaction-process-death.py and its HOST recorder contract; this work
package and its validation record only.

CONTRACTS INVOLVED: Architecture V3.2 §§20–21 (invalid model quarantine or deletion),
§§56–57 (versioning/transactions), §101 (bounded retention), existing MAIN technical
import boundary, ADR-0001 and accepted ADR-0002. Bounded request-history saturation
is already addressed by ADR-0002 and is not reimplemented here.

INVARIANTS INVOLVED: INV-001/011/019/033. No process, IPC, schema, persistence
strategy, Skill, policy or inference authority changes; no ADR is needed for this
implementation inside the already defined invalid-model deletion boundary.

TESTS REQUIRED: Reject stale version, READY/pending state and forged identity/path;
CAS before file removal; refuse symlinks/special files without deleting targets;
retain rejected row after filesystem failure; rollback after cancellation/death
checkpoint following unlink; safe explicit repetition with a missing artifact;
fresh validated reimport after successful removal; serialize coordinator instances.
The `model_rejected_removal` host scenario must SIGKILL the exact marked MAIN PID
after unlink/directory fsync, explicitly inside the open writer transaction. A new
process must verify the complete original rejected metadata/version, absent file,
denied open/automatic recovery and safe explicit removal repetition. The ordinary
Android suite exercises its cancellation/rollback mode without killing itself.

ACCEPTANCE CRITERIA: Only exact version-matched rejected rows may be removed. The
Room writer transaction acquires the row by conditional delete before touching the
file, retains that writer ownership through the bounded unlink/directory sync and
commits only after successful file cleanup. Filesystem failure rolls back the row.
Crash after unlink leaves a rejected row whose absent artifact can be removed by
explicit repetition; it never produces READY or automatic reimport/inference.
Results contain technical outcome codes only. Existing capacity caps remain intact.

OUT OF SCOPE: User-facing management UI/ingress, automatic repair/retry, deletion
of READY/pending models, arbitrary filenames, inference/session lifecycle, resource
profiles, native/physical/soak qualification, schema/migrations or ADR changes.

PROCESS-DEATH / SECURITY: File deletion and SQLite commit cannot be atomic. The
safe rollback state is rejected metadata with either the original file or no file.
The coordinator's existing process-wide lock serializes import/recovery/removal;
Room CAS/writer ownership also excludes concurrent DAO changes. Only exact private
regular artifacts can be unlinked. No user/model content or exception is logged.

VALIDATION: `python3 scripts/android/test_transaction_process_death_harness.py`
passed all 17 recorder contracts in HOST (no device contacted). Repository JVM,
Android and actual SIGKILL execution remain for the parent's serialized canonical
validation. No physical qualification is claimed by this work package.

PROCESS-DEATH RECORDER: `model_rejected_removal` selects
`OrionModelImportProcessDeathTest#rejectedModelUnlinkBeforeCommitPreservesRejectedMetadata`.
The fixture retains only synthetic metadata, a tiny GGUF and a synced PID marker.
It never restores the removed file, reimports or infers automatically. Existing
model-file and imported-row scenarios keep their distinct outside-transaction
proofs. Scoped fixture cleanup runs after rollback/verification; a prepare attempt
retains its owned artifacts until verification so a failed run remains inspectable.
