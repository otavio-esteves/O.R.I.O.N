# ORION-F5-CYCLE-PROJECTION-001 — minimized manual partial receipt

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/125.
Source base: 283348c (issue123 owner cycle).

OBJECTIVE: Project an existing immutable cycle receipt in a separate manual
phase into a deterministic, bounded, minimized partial technical format. This
does not complete RuntimeEvidenceProtocol v1 or collect missing observations.

FILES ALLOWED: One new final pure internal app/ai projector, focused JVM tests,
this work package and a qualification note. No owner/client/factory, runtime,
IPC/native, persistence, Gradle/dependency, profile/admission or clock changes.

CONTRACTS INVOLVED: Issue123 source receipt; architecture sensitivity, MAIN
ownership and observability; accepted ADR0001/0002/0003 unaffected. New own format
ORION_MAIN_CYCLE_PARTIAL/schema1, never RuntimeEvidenceProtocol v1.

INVARIANTS INVOLVED: INV-009/011/012/027/028/032/033/035/036.

TESTS REQUIRED: No IDs/model/hash/path/content/error descriptions in early
NO_ATTEMPT or Completed; actual optional native facts never inferred; absence
and unknown time remain explicit; bounded cardinality/ASCII byte size;
determinism and immutable detached output; malformed/faulty optional input is
unavailable; original inputs remain unchanged; source has no clock/IO/Android/
RPC/evaluation dependency. Root executes canonical Kotlin/build/runtime checks.

ACCEPTANCE CRITERIA: Manual-only projection, no sink or automatic integration.
Only closed typed enums/booleans/nonnegative counts/masks and supported native
API-wall durations. Omit every identifier, model hash, profile/plan/budget, exit
history and timestamp. At most 12 resource summaries/16 metric fields and 8192
encoded ASCII bytes. Stream lower bounds remain independent after loss;
observer counters remain explicitly global. No default UNKNOWN fixture is
manufactured. Missing sections stay absent; malformed sections stay unavailable.
NOT_QUALIFIED and all authentication/approval/phase/provenance flags false.
Optional/manual failures are contained without changing source receipts.

OUT OF SCOPE: Export/storage/log/network, driver/loop/polling, replacement or
completion of RuntimeEvidenceProtocol v1, missing timing reconstruction, full
resource/history evidence, profiles/calibration, physical/F5/soak/F6 closure.

VALIDATION: Sixteen focused JVM tests supplied. Canonical execution remains
pending root/CI. No Kotlin/Gradle/Docker/ADB or device execution by this author.
Light source/dependency/bound-shape checks, local document links and
`git diff --check` pass. Peer review hardened enum identity/mask positions,
companion initialization containment and size-independent bounded iteration.
Technical format/limits are documented in
[F5-CyclePartialProjection](../qualification/F5-CyclePartialProjection.md).
