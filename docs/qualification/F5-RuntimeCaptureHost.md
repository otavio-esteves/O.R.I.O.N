# Runtime capture HOST recorder — one fixed invocation

Work package: [issue #159](https://github.com/otavio-esteves/O.R.I.O.N/issues/159).
This HOST unit complements the separate Android composition. It implements one
source-bound technical capture, with no profile, physical or phase approval.

OBJECTIVE: Invoke the fixed qualification runtime-capture program once, validate
its closed final frame and independent source/artifact/target bindings, and use
[assembler158](../../scripts/android/f5_runtime_source_assembler.py) for unchanged
RuntimeEvidence v1 when representable. A valid no-load refusal needs no model.

FILES ALLOWED: New `scripts/android/record-f5-runtime-capture.py`, focused
`scripts/android/test_f5_runtime_capture_host_harness.py`, and this HOST note.
The root owns the shared index; this author edits no Android, assembler, helper,
existing protocol, build or other file and makes no commit.

CONTRACTS: [capture contract141](F5-RuntimeCaptureContract.md), assembler158,
[runtime v1](F5-RuntimeEvidenceProtocol.md), existing read-only source/installed
identity helpers from [#130](F5-SingleAttemptHostCapture.md), suspend-aware HOST
clock and the new final framework runner. INV-009/011/012/013/014/018/019/027/028/
032/033/035/036 apply. No process, authority, IPC, business persistence, policy or
recovery boundary changes are introduced.

ACCEPTANCE: One fixed no-argument invocation, complete clean tracked-source
freeze/local/installed APK pair before and after, actual target/boot/build/window/
memory properties, independent expected identity for SOURCE, strict bounded final
framing, minimized valid output only, uncertainty without replay or Android cleanup.
All eight flags remain false and capture remains NOT_QUALIFIED.

TESTS REQUIRED: Synthetic refusal/source/incomplete/runner outcomes, nullable actual
owner-call facts, exact command/target guards, independent identity/model/window/
boot changes, no split APKs, bounded streaming/BOOTTIME watchdog, fixed framing,
interruption/original BaseException, publication failure and sensitive sentinels.
Root owns canonical Docker and actual emulator validation.

## Input and one-shot transport

The finite API is:

```text
record(adb, serial, target, source_root, source_manifest, app_apk, test_apk,
       directory, expected_identity_path=None, clock=None)
```

Production CLI constructs the closed `CaptureAdb`; the Python ADB/clock seams are
for trusted orchestration and synthetic controls, not arbitrary CLI commands or
time providers. The clock must actually report CLOCK_BOOTTIME/HOST_CLOCK_ONLY
with a known same-session HOST boot identity. No unavailable/synthetic fallback
is accepted. There is exactly one consumed instrumentation command:

```text
shell am instrument -w -r network.orion.qualification.test/network.orion.app.compatibility.OrionCortexRuntimeCaptureInstrumentation
```

No arguments, noRestart, test selection, grants, model/template/profile input,
installation, provisioning, setting changes, force-stop, device cleanup or second
invocation is available. Normal instrumentation can restart its target; this is
not an autonomy/24-hour loop. The allowlist reuses only the existing read queries
and adds exactly `shell settings get global boot_count`.

Popen streams stdout through one bounded pipe, with stderr discarded and no raw
stdout/stderr file. The hard raw bound is 128 KiB; the real HOST BOOTTIME watchdog
is 180 seconds for transport, not a chosen request/workload duration. On failure
only the owned local HOST ADB child may be terminated. This does not prove the
Android program stopped. No timeout or local child exit grants replay permission.

Targets are explicit `S21`, `API37`, `API35_EMULATOR` or `API37_EMULATOR`. Physical
roles use existing physical/model/API/arm64 guards. Emulator roles require an
emulator serial plus positive actual qemu/emulated fingerprint/hardware signals,
matching API, supported ABI and page size. A label cannot promote an emulator to
physical or turn an ordinary physical serial into an emulator allocation. Reports
retain only technical hashes, not raw serial/model/fingerprint strings.

## Independent binding and frame

Before and after, the existing helper validates every tracked file of the clean
source checkout against the external complete source manifest, with exactly app
and test artifacts. The recorder hashes local files and compares their installed
base APK hashes; missing/split/changed artifacts refuse or make capture uncertain.
No reinstall, clear-data or downgrade fallback exists. BOOT_COUNT and boot-id,
uptime precision bounds and actual MemTotal are bracketed and checked for drift.
Target build/API/ABI/page signals are independently rechecked.

Optional `--expected-identity` is only closed independently checked v1 metadata;
it cannot select/import/approve a model. If supplied, its exact source commit,
source-manifest SHA and qualification app/test APK/package identities must match
the checked HOST observations before invocation and remain unchanged afterward.
SOURCE requires it and compares actual MAIN model digest/size through assembler158.
The HOST window brackets the unchanged MAIN session and matches its actual boot.
HOST never fills a missing MAIN PID, boot, event, byte count, phase or candidate.
No-source outcomes honestly omit nonexistent model provenance instead of inventing
it. Model/source authentication remains a separate review, with all flags false.

The raw final frame permits only `orionRuntimeCaptureStatus`, optional
`orionRuntimeCaptureSource`, fixed `stream` and exactly one final result code.
Intermediate status traffic, duplicate/extra/trailing keys, controls/non-ASCII,
invalid UTF-8, missing/surplus frames or mismatched source/stream/code refuse it.
The status and SOURCE limits remain 1024 bytes and 96 KiB; the full closed schema
and all eight false flags are checked by assembler158. `receipts` checks framing
only; `validate_frame` adds code/owner/stream pairing. Neither alone is SOURCE
validation. The recorder must successfully call `assemble_json` with independent
identity/platform evidence before persisting any source-bearing runtime file.

Code -1 is a valid program result: NO_LOAD_REFUSAL requires owner=false and
ORION_RUNTIME_REFUSED; V1_SOURCE requires owner=true and ORION_RUNTIME_SOURCE.
NO_V1_RECORD uses ORION_RUNTIME_UNAVAILABLE and preserves nullable owner facts:
true is a known call with unavailable source, false is a known noninvocation,
null remains invocation UNKNOWN. Code 0 requires source absent, NO_V1_RECORD,
owner=null and UNAVAILABLE; normal ADB exit alone does not turn it into success.

## Owned publication and uncertainty

A new private directory is required. It contains `capture-report.json`, and only
when fully validated, `capture-status.json`, `assembly-report.json` and optional
`runtime-v1.json`. There are no raw transcripts, APKs, models, paths, exceptions or
content logs. Publication anchors the new directory's descriptor/inode and opens
every parent component without following symlinks. Parents must already exist.
Before/after each normal write it checks original path identity, private mode/
owner and the exact set of published names against their recorded file inodes.
A missing earlier published file is a failure as well as an unexpected member. A swapped
directory, symlink or foreign member makes publication unavailable. Mutable paths
cannot redirect writes from the original owned inode.

Payloads use anonymous Linux `O_TMPFILE` descriptors tracked as owned regular
inodes. Partial writes are completed only within that same payload; zero/failed
writes refuse publication. File fsync precedes an atomic no-clobber linkat from
the controlled `/proc/self/fd` entry into the pinned directory descriptor;
directory fsync follows. Existing final names, including symlinks or earlier
reports, are never overwritten. Cleanup only closes owned descriptors: it never
unlinks a named temporary, foreign collision or replacement inode. Linux
filesystem `O_TMPFILE` and procfd-link support are required; absence fails closed
with no named-temporary or replace fallback. These are HOST Linux filesystem
requirements, not Android API claims.

A report does not claim its own final durability ACK (`FINAL_ACK_NOT_PROVEN`).
A storage failure returns publication UNKNOWN and attempts to retain a minimized
failure report only on the same anchored owned inode, even if its original path
moved. This also refuses to overwrite an existing report. If storage cannot write
or a foreign final report occupies the name, no failure record can be guaranteed.
Valid already-written partial files and any foreign entries are left untouched,
never turned into a retry journal.

Any post-invocation timeout, interruption, bad framing, binding change or ambiguous
owner fact remains UNKNOWN. Original non-Exception interruptions, including
KeyboardInterrupt/SystemExit/GeneratorExit, propagate after a minimized UNKNOWN
record attempt; no Android cleanup or replacement command runs. Transport cleanup
attempts selector close, local child kill/wait and stdout close
independently; a cleanup failure cannot replace the original body exception.
The HOST report's capture completeness describes technical observation, not source completeness,
runtime success, admission or qualification. Inner status and v1 completeness
remain separately visible; missing SOURCE never becomes an empty runtime record.
PROGRAM_OBSERVED/programFrameObserved identify a valid program receipt, not an
observed Core terminal or inference success; NO_V1_RECORD never supplies one.

## Validation and limits

Root's historical Docker run passed 27 focused tests in 0.619s on the initial
HOST source SHA256 `0b8f22c72de01705d51c0c9bc4e0613a6af5345a6945657a9ac0473035e8b27a`.
Later review found foreign temporary deletion, final clobber and mutable-directory
publication hazards. New regression controls and the anchored anonymous-inode
publisher address those findings; the later 40-case invocation is recorded below. The initial
passing invocation does not cover this hardening. The pre-hardening snapshot was
retained separately for RED/GREEN comparison without rewriting the original logs.

Root subsequently passed the 40-case publisher selection in Docker in 0.832s on
source SHA256 `d9c8b026e3766ded35c503c32562c7d8c8a4f31c5c1434ed40062e4c13b8ec04`.
Independent review then identified two further defects: a previously published
status could disappear without exact membership detection, and transport cleanup
could mask an original GeneratorExit. Two new methods first reproduced one FAIL
and three ERROR subcases in Docker (0.108s), covering selector close, child kill
and stdout close. The 42-case selection now includes both regressions. Exact
membership and original-exception preservation fixes are in this snapshot;
root passed all 42 focused controls in canonical Docker in 0.867s (exit 0), on recorder SHA256 `b1bbf20ebfcf258096cd103a9f66ce0843ae9015522c17bf9e984cc6eb07efab` and harness SHA256 `916acfc0409be51bc527b995304df035edfe92f02686052ae8156af13897d701`. Independent static review approved both corrections at that recorder hash. Full HOST validation is recorded separately. Earlier successful checks
are historical scopes and are not added to or substituted for the new run.

Synthetic controls were written before code. This author performs only AST,
source/diff and link checks; no test suite, Docker, Gradle or ADB execution. Root
must run focused and full HOST validation in canonical Docker, including:

```sh
python3 -B scripts/android/test_f5_runtime_capture_host_harness.py
```

Owned Python pipe children in transport controls are synthetic HOST mechanics,
not actual ADB/device execution. Root's separate actual emulator framework run
must freeze source/local/installed bindings and retain failures. Required CI
must pass before merge. Existing #125/#128/#130/v1 formats stay unchanged.

OUT OF SCOPE: New workload/controller execution, calibration/templates/models,
admission authority, native/physical/API37 MemoryLimiter measurement, real 24-hour
duration and F4/F5/soak/F6 closure. This recorder never bypasses current empty
configuration or grants production inference/source authentication.

Root full integration: canonical Docker executed532 HOST cases in30.241s, exit0, zero failures. The preceding532-case invocation failed one obsolete literal source-layout assertion in the existing single-attempt source-contract test. That assertion now verifies the actual mutually-exclusive list and its <=1 guard while requiring all existing modes, including runtime capture. Its10 focused cases passed in0.033s before the successful full rerun. These invocations overlap and are not summed. No physical gate is closed.
