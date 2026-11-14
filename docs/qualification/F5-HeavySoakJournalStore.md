# Durable Linux HOST heavy-soak journal store

Work package: [ORION-F5-HEAVY-JOURNAL-001](../issues/ORION-F5-HEAVY-JOURNAL-001.md).
The [offline evidence protocol](F5-HeavySoakEvidenceProtocol.md) remains unchanged.
This store adds local durability and concurrency controls, not an executor,
Android producer, trusted admission configuration or qualification authority.

No Galaxy S21/API37 target, heavy workload or physical run is supplied. #89 and
its #64 prerequisite remain open. Every store response is `UNVERIFIED_INPUT` /
`NOT_QUALIFIED`. Physical, real-24-hour, autonomy, profile, production-inference,
F5 and soak approval flags remain false. The store never dispatches a command.

## Closed operations

The CLI has only these operations:

```bash
python3 -B scripts/android/f5_heavy_soak_journal.py init \
  --directory /path/to/NEW-owned-run --plan /path/to/plan.json
python3 -B scripts/android/f5_heavy_soak_journal.py inspect \
  --directory /path/to/existing-owned-run
python3 -B scripts/android/f5_heavy_soak_journal.py append \
  --directory /path/to/existing-owned-run --event /path/to/event.json \
  --expected-snapshot-sha256 THE_EXACT_PREVIOUS_STATE_DIGEST
```

These commands operate only on HOST files. `append` takes exactly `kind`, `data`,
`bootCount` and `deviceElapsedMillis` in its event object. Its kind/data vocabulary
is the existing protocol's closed vocabulary. It accepts no HOST sequence, session,
clock override, executable, callback, command, device serial or arbitrary log body.
Android fields are supplied records and remain unverified; they are not fetched
from a device. Validating an admission-decision hash does not grant admission.

`init` exclusively creates the leaf directory, whose parents must already exist.
It requires and retains owner-only mode 0700. An existing directory is refused;
initialization never overwrites or adopts it. It writes the selected canonical
plan and an **empty** protocol journal. There is no invented `RUN_BEGIN`, Android
boot or Android elapsed time. A valid plan with unavailable dependencies can be
stored as such; the nested protocol report continues to identify those absences.

Python callers can hold one live owner across several records:

```python
import f5_heavy_soak_journal as journal
with journal.JournalStore(existing_owned_directory) as store:
    observed = store.inspect()
    acknowledgement = store.append(selected_closed_event, observed['snapshotSha256'])
```

`JournalStore` exposes `initialize`, `append` and `inspect`; `operation` is a closed
CLI/convenience wrapper returning typed responses. No operation takes a callable.
The store does not loop, schedule work, rebind, replay, restore policies, delete
fixtures or invoke any cleanup instruction. An intent entry records intent only.
A future trusted runner must await the durable intent ACK before performing its
separately authorized operation. That runner and authorization path are absent.

## Owned files and exact content binding

The directory contains these committed names:

- `plan.json`: write-once canonical plan, mode 0400.
- `state.json`: current canonical HOST envelope, mode 0400.
- `.pending-<canonical UUID>`: temporary evidence from an interrupted/failed write.
  These are never adopted or automatically removed.

Unknown entries, symlinks, special files, multiply linked files and files owned by
another UID are refused. Inventory is bounded to 4,100 names. Input/plan bytes are
bounded to the protocol's 2 MiB; the HOST envelope is bounded to 4 MiB and its
embedded journal remains bounded to 2 MiB and 4,096 entries. Failures retain whatever
temporary file was written, including a partial one. Abrupt power/filesystem loss
may lose an uncommitted temporary; its survival is not promised by inspection.

The exact envelope fields are `schemaVersion=1`, `kind=HEAVY_SOAK_HOST_STORE`,
`planSha256`, `writers` and `journal`. `journal` is the unchanged protocol-v1 object.
The selected plan is bound both by the envelope and by the embedded journal's
canonical `planSha256`. Canonical bytes use sorted ASCII-escaped JSON with `,`/`:`
separators and no whitespace. A `snapshotSha256` hashes the **exact entire state
file**, including writer metadata and every retained entry; it is the CAS token.

Each writer record has exactly `hostSessionId`, `hostBootIdSha256`, `hostPid` and
`clockSource=PYTHON_TIME_MONOTONIC_NS`. Session IDs are unique canonical UUIDs;
PID is a positive signed-32-bit HOST PID. The Linux kernel boot UUID is read from
`/proc/sys/kernel/random/boot_id` and retained only as SHA256. Writer references
are bounded to 4,097 sessions and every journal session must have a record.
These metadata are local records, not tamper-proof provenance or liveness leases.

## Live ownership and clocks

A per-instance reentrant mutex serializes initialize/append/inspect/close and
protects the descriptor lifecycle even when one context is shared across threads.
The open context also holds a nonblocking exclusive `flock` on the pinned directory
inode for its lifetime. Other cooperating processes/contexts cannot inspect or
append concurrently. A forked child cannot use an inherited context; owner PID
is checked before acquiring the instance mutex. Closing inherited descriptors in
the child never waits for that inherited mutex. A newly opened context after the old owner exits receives its own
PID/interpreter session. There is no resumed execution lease or persisted permit.

The writer assigns each entry's next consecutive sequence, current HOST session
and `time.monotonic_ns() // 1_000_000`. One interpreter/PID and boot share one
session. Fork or new process gets a fresh session. A caller cannot override it.
One-shot CLI appends in separate processes therefore expose session discontinuity
rather than manufacture uninterrupted recorder ownership. A future long-running
producer should retain one context and must still prove its actual observations. Linux monotonic time excludes HOST suspend;
its recorded source is explicit and it does not attest a full wall-duration run.

The unchanged validator handles supplied device boot/time separately: it never
subtracts elapsed values across Android boots or sums distinct HOST sessions.
Recording real HOST timestamps does not establish a physical workload, observation
completeness, autonomy, calibrated resources or real 24-hour qualification.
`inspect.lastRecordedWriter` is retained metadata, never proof that that PID is
still the original live process (`writerLivenessVerified=false`).

## Filesystem boundary and publication

Every directory component is opened relative to its pinned parent from `/`, with
`O_DIRECTORY|O_NOFOLLOW`. There is no canonical-path or pathname-write fallback.
The owner rechecks the linked inode chain against its pinned descriptors. All
store reads, temporary creation and replacement use the pinned leaf `dir_fd`.
Committed reads require owner-owned regular single-link files and `O_NOFOLLOW`;
`O_NONBLOCK` prevents a swapped FIFO from hanging before type validation.

A candidate is copied and fully validated against the frozen plan before writing.
Under the directory lock the store rechecks the exact prior snapshot digest, then:

1. Creates a unique relative temporary file with `O_EXCL|O_NOFOLLOW`.
2. Writes bounded canonical bytes, flushes, sets 0400 and fsyncs the file.
3. Rechecks the directory chain, replaces the committed relative name atomically,
   fsyncs that same pinned directory inode and rechecks its chain.
4. Returns the durable ACK only after all those steps succeed.

The directory lock prevents cooperating writers from interleaving. Anchored
operations also keep a replaced parent/symlink from redirecting publication into
an external directory. A changed chain fails closed; if detected after publication,
the outcome is uncertain and the moved original must be inspected explicitly.
The store is not an authentication system against a same-UID actor fabricating
or modifying all evidence; hashes and mode bits do not establish qualification.

## CAS, uncertain ACK and interruption

`INITIALIZED_DURABLE` and `APPENDED_DURABLE` acknowledge only the local fsync/rename
sequence. `CONFLICT` means the supplied previous-state digest differs; no entry was
added. Do not replace the token and automatically repeat a request. Inspect and
compare the intended record before any new explicitly selected append. Reusing an
old digest after a lost ACK cannot add that record twice.

An error during the rename attempt, its directory fsync, subsequent chain check
or final wrapper close produces `COMMIT_UNCERTAIN`, not a plain failed-write ACK.
The result identifies `candidateFile` (`plan.json` or `state.json`) and its
`candidateSha256`, with `nextStep=INSPECT_DO_NOT_REPLAY`. A rename error can leave
either the old or new state visible; the store does not guess or retry it. A live
context also blocks further appends with `INSPECTION_REQUIRED` until inspection.
CAS handles uncertainty across processes; there is no new durable retry ledger.

`SNAPSHOT_UNVERIFIED` reports only the currently visible committed name, exact
snapshot hash, retained-temp count and nested offline validation. It does not fsync
or repair evidence and keeps `durabilityProvenByInspection=false`. If either
committed file is missing, `INITIALIZATION_INCOMPLETE` preserves the partial store.
Neither response adopts temporary files, resumes work or proves successful cleanup.
Missing ends, unresolved intents, gaps and interrupted sessions remain visible
through the unchanged validator. Original records are never rewritten as success.

CLI exit 0 means init/append durable ACK or a readable inspection result, including
an explicitly incomplete initialization. Exit 2 means conflict, rejection or
uncertain publication. Every response remains unqualified. The error wrapper emits
fixed codes without paths, raw exceptions, event payloads or supplied text.

## HOST-only verification

```bash
python3 -B scripts/android/test_f5_heavy_soak_journal_harness.py
```

Tests use actual local files, processes, exclusive locks and SIGKILL at three
boundaries: after temporary-file fsync before rename, after rename, and after
directory fsync before ACK. Fault cases cover fsync/rename/close errors, stale CAS,
parent/symlink replacement, forbidden files, changed plans, lost ACK and process
session changes. These are HOST durability tests, not Android process-death,
physical power-loss, heavy runtime or soak evidence. Canonical root integration
checks remain separate; no device or Docker/Gradle work is done by this package.
