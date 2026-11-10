# ORION-F5-IMPORT-001 — Private verified model staging

OBJECTIVE: Copy a trusted expected model artifact into MAIN-owned private storage,
checking exact length, full SHA256 and an injected format validator before atomic
publication. A file receipt grants no inference or registry READY authority.

FILES ALLOWED: data/repository models source/tests and test dependency; this issue.

CONTRACTS INVOLVED: GGUF trust flow, MAIN storage ownership, interrupted import,
bounded temporary retention. The format validator is a trusted dependency; a full
GGUF parser and registry transaction/recovery wiring remain separate packages.

INVARIANTS INVOLVED: INV-001, INV-011, INV-019, INV-027, INV-033.

TESTS REQUIRED: exact-byte/hash checks, truncation/overflow, low space, interrupted
copy, validator rejection, dedup revalidation, bounded orphan cleanup, symlinks.

ACCEPTANCE CRITERIA: no partial import is published; filenames derive only from
validated hashes; cancelled work preserves cancellation; invalid copies have
bounded quarantine; orphan cleanup never touches verified model files.

OUT OF SCOPE: Room/schema, model loading, UI, full GGUF parsing, physical evidence,
model calibration and inference authorization.

## Implementation boundaries

- Copy uses 64KiB working buffers, exact expected length and full SHA256. A trusted
  validator must accept the staged file before `ATOMIC_MOVE`; no non-atomic copy
  fallback exists. Cancellation/interruption propagates; normal cleanup failure
  leaves a recognizable orphan for bounded recovery.
- At most32 verified files and100 outstanding staging entries are admitted. Small
  invalid copies retain at most4 quarantine entries of at most1MiB each; further or
  larger invalid copies are discarded. Quarantine is retained for manual review,
  with no automatic retry or promotion. Filesystem errors remain observable results.
- File contents are fsynced. The store does not claim a cross-resource SQLite/file
  transaction or power-loss durability of a directory entry. The registry coordinator
  revalidates existing files and invalidates missing READY files during recovery.
- Android entry validates MAIN, unlocked credential storage and no-backup location.
  Public methods expose no arbitrary path or model-load authority. Future UI/user
  import authorization remains a separate boundary.
- JVM tests and Android coordinator tests are supplied; parent executes canonical
  builds serially. No test execution or physical qualification is claimed here.

## Consolidated validation

Canonical checks, scoped emulator results and retained failed attempts are recorded
in [F5 Runtime Gate](../qualification/F5-RuntimeGate.md) and its
[evidence record](../qualification/f5-runtime-evidence.json). That report supersedes
implementation-time validation-pending notes without changing this work package's
scope. Final review corrections/reruns, physical qualification and production
resource profiles retain the explicit status recorded there; no per-issue test
count or phase closure is inferred.
