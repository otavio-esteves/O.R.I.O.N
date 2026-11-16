# ORION-F5-RUNTIME-HOST-ASSEMBLER-001 — closed SOURCE to HOST v1

Tracking: [issue #158](https://github.com/otavio-esteves/O.R.I.O.N/issues/158).
Base: `0f4c841`. This pure component parses already-supplied technical metadata;
it neither acquires evidence nor authenticates an input or enables a workload.

OBJECTIVE: Strictly assemble the closed MAIN SOURCE/status capsule from encoder
#153 into unchanged RuntimeEvidence v1 using independently supplied expected
identity and checked HOST platform/window observations.

FILES ALLOWED: New `scripts/android/f5_runtime_source_assembler.py`, focused
`scripts/android/test_f5_runtime_source_assembler_harness.py`, and this note only.
Existing validators/protocols, app, controller, runner and architecture remain
unchanged. No new execution framework or CLI is introduced.

CONTRACTS: [RuntimeCaptureContract141](../qualification/F5-RuntimeCaptureContract.md),
[encoder153](../qualification/F5-RuntimeCapsuleEncoder-WorkPackage.md) and
[unchanged RuntimeEvidence v1](../qualification/F5-RuntimeEvidenceProtocol.md).
INV-009/011/012/018/019/027/028/032/035/036 apply. Ownership, authority, processes,
IPC, persistence, recovery and architectural invariants remain unchanged; no ADR
waiver is inferred.

ACCEPTANCE: Exact closed fields/types, strict byte/complexity/count bounds and
duplicate/nonfinite rejection; separate expected model/package identity; matching
HOST boot/window; no MAIN repair/defaults; unchanged v1 validation; minimized fixed
rejections; all eight provenance/approval flags false. Missing sources remain
absent and incomplete observations remain incomplete.

TESTS REQUIRED: Synthetic complete/incomplete/no-record outcomes, exact v1 mapping,
factored chunk identity expansion, loss/status consistency, zero/null preservation,
independent identity/platform/window mismatches, phase/terminal/native faults,
duplicate/extra/missing/nonfinite/encoding/depth/array/byte limits, no mutation or
sensitive-sentinel diagnostics. Root centralizes Docker execution.

## Finite data interface

`assemble_json(source, status, expected_identity=None, host_observations=None)`
returns immutable `Assembly(runtime_json, status_json, report_json)` byte fields.
`runtime_json` is null for a valid no-record outcome. Rejections raise the existing
v1 `Rejected` type with a fixed technical code, never submitted text or exceptions.
The module has no file, device, network, process, clock, persistence or CLI path.

SOURCE/status must be ASCII byte strings within 96 KiB/1024 bytes. Independently
supplied expected identity and HOST observations are strict UTF-8 byte strings,
each within 4096 bytes. The unchanged v1 decoder rejects duplicate keys, nonfinite
numbers, trailing JSON and malformed UTF-8, limits object fields to 32, arrays to
4097, depth to 12 and nodes to 100000. Source memory is additionally bounded to
128 rows. Booleans and floats never stand in for integer fields.

Expected identity has exactly the eight existing v1 fields, including independently
checked model SHA/size. HOST observations have exactly:

```text
declaredEnvironment bootCount bootIdSha256 startedElapsed completedElapsed
api abi pageSizeBytes deviceBuildSha256 totalMemoryBytes
```

These are already checked caller observations, not input authority. The assembler
requires HOST boot equality and a closed HOST window bracketing the unchanged MAIN
session. HOST's wider window never replaces MAIN event times. The current SOURCE
composition accepts only `network.orion.qualification` and its matching test
package. All four MAIN identity fields match independently supplied expected
identity, including model size. Only the other four identity fields and six
platform session fields come from HOST; MAIN PID/boot/candidate/events/bytes/rows
are never supplied, inferred, clamped or repaired by HOST.

The SOURCE temperature is the actual original wire Float, as required by #141's
candidate source map. Encoder153 serializes `wireTemperature.toDouble().toString()`.
SOURCE validation therefore requires an exact IEEE binary32 pack/unpack roundtrip
in 0..2, without replacing or rounding the submitted value. For example, `0.1`
is rejected while the actual widened Float `0.10000000149011612` is retained.
Exactly representable JSON integers 0/1/2 keep their original integer type; signed
zero and representable subnormal Floats also remain unchanged. The unchanged v1
validator still accepts its existing broader finite-number representation.

Each factored SOURCE chunk must omit `requestIdSha256`. Assembly inserts exactly
its already-validated common `inference.requestIdSha256`; sequence, received time,
byte counts, terminal and null fields are copied unchanged. The complete assembled
ASCII document must fit 4 MiB and pass the unchanged v1 validator against the
separate expected identity. There is no alternative validator or schema.

## Status and honest loss

The header's eight flags must be exactly false. Counters are nonnegative Long
lower bounds; positive overlapping-source loss cannot claim complete accounting.
`NO_LOAD_REFUSAL` requires actual `ownerCallInvoked=false` and no SOURCE; null
cannot prove noninvocation. `NO_V1_RECORD` retains unavailable status with no runtime
document; a nullable failure stays unknown instead of acquiring an invented code.
Either no-record outcome can honestly lack independent
model/platform metadata; any metadata supplied anyway is still validated.

`V1_SOURCE` requires SOURCE plus both independent inputs. `ownerCallInvoked=null`
remains allowed because encoder153 has no PROGRAM invocation fact; false is
inconsistent with an available owned source. COMPLETE requires no declared loss,
complete accounting, no failure code and complete v1 cycle metadata. Current
INCOMPLETE SOURCE preserves nullable reasons from the full closed contract list,
not only the current emitter's two reasons. UNLOAD_UNCONFIRMED requires no observed
unload. No source-bearing UNAVAILABLE outcome is accepted.

The unchanged v1 metadata result and capture integrity are reported separately.
All seven supplied rows can be consistent while source-side capture still reports
loss/unfinished work: the assembler retains the v1 result and emits an incomplete
assembly result. It never promotes that capture to complete. Status JSON retains
its original closed invocation/loss fields. Reports are closed ASCII at most
4096 bytes and always UNVERIFIED_INPUT/NOT_QUALIFIED with all eight flags false.

## Validation, recovery and limits

Root canonical Docker validation passed on source `486ad234c2909060c48599e6849501c02fa7995f`:
28 focused synthetic controls in 0.324s, then the complete HOST harness with
490 cases in 29.341s, zero failures. The earlier source `4c898a4` passed its
26 focused controls and 488-case integrated invocation; these overlap and are
not added. Root review identified the missing exact wire-Float representability
check; two added controls prove rejection without rounding and exact preservation.

Commands were `docker compose -p orion run --rm host-harness-check python3 -B
scripts/android/test_f5_runtime_source_assembler_harness.py` and
`docker compose -p orion run --rm host-harness-check`. These are CONTAINER/HOST
synthetic consistency controls, not Android, model, hardware or real24h evidence.
This validation addendum changes only this note; tested Python files are unchanged.
Exact-head required remote PR CI remains pending before merge.

Synthetic consistent fixtures remain fabricated; equality and parser success do
not establish source/build/device/model provenance or profile approval. No new
evidence is persisted by the component. Process death discards its local objects,
with no side effect, resume, retry or cleanup/recovery permission.

OUT OF SCOPE: Acquisition/program/driver/framing/publication, actual Android
composition, calibrated parameters/model/template/profile, admission, native or
physical/API37/MemoryLimiter behavior, real 24-hour execution and F4/F5/soak/F6
closure. A later recorder owns its separate immutable source/installed artifacts,
target windows and publication contract; this data-only API supplies no approval.
