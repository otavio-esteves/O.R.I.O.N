# ORION-F5-MAIN-LIFECYCLE-FACTS-001 — passive original lifecycle facts

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/132.
Original author base: 0ac873a; local author handoff: 670410c. Integrated validation
source parent: 163e8e0bdb718b28891cbe54c4e10b733287ea04, including the #133 strict
UTF-8 correction and #130 HOST recorder. No original lifecycle regression test
is replaced. The frozen source manifest identifies the six code/test files and
the pre-validation work package; this later update changes documentation only.

OBJECTIVE: Retain optional bounded MAIN lifecycle metadata from existing original
deadline samples and content-byte validation results, and associate the call-local
collector only with the actual runOnce owner. This is partial software evidence,
not exact callback/native timing or a RuntimeEvidence v1 producer.

FILES ALLOWED: ai/api lifecycle, new final buffer/types and focused JVM tests;
Client scoped composition; cycle observer/receipt bounded association/manual
snapshot; focused Android tests; this work package, qualification evidence JSON
and its small log/source/XML archive. No
Foundation/flag/bridge/runner, formatter125/HOST130, wire/AIDL/protocol/service/
native, resource policy, persistence, profile or configuration changes.

CONTRACTS INVOLVED: Original synchronized lifecycle validation/reorder/dedup/
terminality, original Client/Owner/ledger/turnstile, existing byte validation and
OrionClock. Accepted ADR0001/0002/0003 unaffected; no IPC semantics change.

INVARIANTS INVOLVED: INV-009/011/012/013/019/027/028/032/035/036.

TESTS REQUIRED: Same original clock count/order/status/output/Throwable/List;
schema/no-clock, deadline/invalid boot/regression/terminal idempotency and origin;
buffered admission versus ordered release and delivery; reused validated total
UTF8 bytes (not separate text/tool counts); ignored/duplicate/conflict/wrong input;
reserved terminal under saturation/contention, bounded immutable loss/faults;
actual-owner/replay/queue/cancel/error association with no added RPC/Clock. Tests
were supplied for root-controlled validation; the executed scopes are recorded
below and retain their distinct environments.

ACCEPTANCE CRITERIA: Optional final collector default null; no diagnostic object
or enum initialization outside guards when disabled. Existing Clock read and
deadline classification stay outside diagnostic catches and occur once unchanged.
Samples are operation-local associated ORIGINAL DEADLINE SAMPLES, never exact
callback/export/native event timestamps or a previous operation's sample. First
terminal uses a reserved slot only after successful original finish. Chunk records
publish only after constructing the original update, without content/IDs/hashes,
reencoding or validation calls. Bounded scalar bookkeeping inside accept is allowed;
buffer snapshot/copy occurs only in the separate manual receipt phase, never on
Client return/cleanup. Replay constructor facts never attach to retained inference.
Metadata failures/loss remain unavailable;125 stays byte-for-byte unchanged.

OUT OF SCOPE: RuntimeEvidence v1 completion, load/unload or multi-phase memory
sampler, timing reconstruction, any formatter/driver/HOST changes, templates/
models/calibration/profiles, physical/native performance/soak/F5/F6 closure.

VALIDATION (integrated snapshot, 2026-11-15): Docker executed 48 ai/api JVM
cases (including 15 new lifecycle-fact regressions) and 216 app JVM cases:
264 cases in 35 suites, zero failures/errors/skips. AndroidTest assembly passed.
A separate Docker invocation passed module-boundary verification/tests,
ai/api and app lintDebug, and assembleDebug. Task reuse/cache counts remain
explicit in the evidence; these are scoped checks, not a new full-project check.

On the owned API35/x86_64/4096-byte-page emulator, the cycle selection passed
10 cases and the separate Client/Owner/Queued selection passed 41 cases: 51
scoped Android cases, zero failures. Local APK SHA-256 values matched APKs pulled
from the installed app/test before the recorded execution. The owned 4-vCPU,
3072-MiB AVD was stopped and its data removed; other AVDs were unchanged.

Initial emulator bootstrap was NOT COMPLETE, with no app installed or tests
issued. An additional HOST helper readiness query failed before installation or
instrumentation. Both attempts and their original logs are retained separately;
neither is reclassified as PASS or inferred to be an O.R.I.O.N. runtime defect.
The later passing Android selections do not erase these infrastructure attempts.

The Docker HOST harness on this integrated F candidate passed 426 cases in
19.716 seconds. This is one invocation; the other candidate's 426-case invocation
is separate and is not added to this count. Required remote CI is PENDING in this
snapshot. The author's local 670410c handoff recorded partial validation only;
its earlier record is preserved separately from this integrated evidence.

[Evidence index](../qualification/f5-lifecycle-facts-evidence-20261115.json) binds
the frozen source, reconstructable patch, raw logs, current XMLs, artifact hashes
and non-passing attempts to a SHA-256 manifest. Independent read-only review found
no blocking code issue. The original lifecycle regression suite and formatter125
remain unchanged. No Release/qualification/R8 execution is claimed for this new
snapshot beyond the explicitly recorded Debug/static/Android scopes.

RECOVERY / PRIVACY / UPGRADE: Observations are bounded ephemeral MAIN metadata;
process death discards them without automatic replay or changed restart policy.
No content, request/model IDs, content hashes, raw application errors, schema or
migration is introduced. The receipt remains partial: no complete RuntimeEvidence
v1, native benchmark, physical/API qualification, calibrated or approved profile,
production inference approval, F4/F5/24-hour soak closure or F6 authorization.


FULL-CHECK COMPLEMENT (2026-11-15, later execution scope): The clean tested source
is 8a80dd7a386878323888240d62787f11ad31ee9a, tree
f25b26eba0e4e8435172c131101a1f17aff3c31b. Docker's canonical full check passed
in 20m37s: 4,401 tasks, 1,663 executed, 637 from cache and 2,101 up-to-date.
The active Git-tracked module inventory contains 952 JVM cases in 122 suites,
with zero failures/errors/skips. These are current result totals, not a claim
that every case was freshly executed. The former raw 990/124 audit collection
included 53 legacy cases in three XMLs outside that inventory; B active937/121
plus the new 15-case lifecycle suite equals F active952/122. Both correction
records and every active XML are preserved in the complement.

The qualification single-attempt R8 build/package check subsequently passed
in 13m57s: 1,736 tasks, 79 executed, 34 from cache and 1,623 up-to-date.
This is build/package evidence only; no new Android runtime run is claimed.
The earlier 264-case/35-suite scoped snapshot, its 51 Android and 426 HOST
executions, and its 68-payload archive remain unchanged. Those 264 JVM cases
are not added again to the later 952-case total.

[Full-check complement](../qualification/f5-lifecycle-full-check-20261115.json)
binds the canonical/R8 logs, 122 active XMLs, both count-correction records,
the complete tracked source-tree manifest and the six unchanged runtime/test
hashes. This note and the new complement files were added after validation;
they do not rewrite the tested source tree. Required remote CI remains PENDING
in this snapshot. No physical/profile/production inference, native benchmark,
complete RuntimeEvidence v1, F4/F5/soak closure or F6 approval is granted.
