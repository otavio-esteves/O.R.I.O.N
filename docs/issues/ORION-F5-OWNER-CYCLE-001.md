# ORION-F5-OWNER-CYCLE-001 — one optional MAIN owner cycle

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/123
Base: `c0fa4e2`; root owns subsequent packaging/build validation.

## OBJECTIVE

Connect one explicitly observed invocation of the existing MAIN owner to bounded
resource, stream-count, original-result, optional metrics and unload facts. A
manual observation phase is separate from the original run's return. This is a
CORE software producer/consumer, not a heavy driver, calibrated profile or soak.

## FILES ALLOWED

Existing `OrionCortexRuntimeOwner.kt`; narrowly necessary observation additions to
`OrionCortexClient.kt`; new final bounded cycle types/collector; focused app JVM
and Android tests; this package and its qualification note. No factory, AIDL,
service/native, Room/schema, profile or configuration changes.

## CONTRACTS INVOLVED

Architecture §§22/32/33/35/99; Master Plan §§11.9–11.11/30/31; existing runtime and
heavy-soak protocols; accepted ADR-0003 and unaffected ADR-0002/0001. Correlation
comes from the actual owner invocation and client turn, never buffer order or
an input file. The current production factory keeps plans empty.

## INVARIANTS INVOLVED

INV-002/009/011/012/013/014/023/027/028/032/033/035/036.

## TESTS REQUIRED

Real owner early NO_QUALIFIED_PROFILE before storage/inventory/bind; no caller
identifier/content/hash retained by that refusal. Actual execution vs retained
result, dedup race, queued expiry, concurrent observations, death/close and
supersession. Same original result/exception/cancellation and onChunks function/
List/order. Optional failure/contention/drop isolation; immutable bounded facts.
Manual slow/faulty metrics after run returns, no extra run/cleanup Clock/RPC/poll.
Root owns canonical Docker, Android and CI; no Gradle/Docker/ADB by this author.

## ACCEPTANCE CRITERIA

Opaque observer-owned token identifies exactly one invocation. It carries no
request/model/input fields before validation; early refusal is NO_ATTEMPT and
replay is RETAINED_RESULT, never another real cycle. A local token follows its
coroutine into the actual turn. Synchronous resource/restart scopes additionally
check exact token, client, phase and Thread. No arbitrary observer callback.
Stream records only chunk/callback counts, never text/pieces/bytes/tokens or
content-derived hashes. Original callbacks receive the same List and throw the
same original error. Only final tryLock buffers perform bounded diagnostic work.

Manual reading happens after the original call finishes; at most one explicit
metrics query uses the existing client and existing fenced mapping. No bind,
resource refresh, retries, poller, new clock worker or added Clock/RPC inside run.
Unload records only existing RPC/poll facts: no invented post-reply timing or
native-release/finality. Loss, missing identity, stale/late facts stay incomplete.
All receipts are retained snapshots / NOT_QUALIFIED. No phase gate closes.

## OUT OF SCOPE

Operational/R8 driver or export; loops/stress/24-hour runs; trusted profile
enrollment or JSON admission; new owner/client/DB/process/runtime; model import;
calibration, production activation, architecture/IPC changes or F6.

## SOURCE REVIEW / PROVIDED VALIDATION

Provided 12 pure JVM controls and ten Android cases (two real-owner negative
cases plus eight scripted Client/manual-reader controls). No test or compiler
execution is claimed by this author. The new API cannot be tested against the
baseline without its implementation, so there is no standalone compile-ready
red-only commit. Root owns canonical execution after the focused final commit.

Peer review hardened diagnostic enum initialization and removed added monitor
waits from scopes/view selection/cleanup. Endpoint views are invalidated before
normative identity replacement and published only where the original monitor is
already held. An explicit optional per-call marker proves actual runOnce ownership;
buffer loss cannot fabricate replay. Tests coordinate join/queue entry with
UNDISPATCHED starts, admit contract-permitted concurrent loss, and deterministically
lose ownership events without losing original inference success. Resource/stream
facts remain bounded and content-free. `git diff --check` passes.

A lost finish event can remain PENDING+LOSS permanently; it is incomplete evidence,
not recovery/progress or proof that inference remains active. Observer counters
are global cumulative lower bounds, not per-token or atomic. No profile, R8 driver,
export, physical result or phase closure is supplied.

## ROOT INTEGRATION VALIDATION

Canonical Docker validation on source `80eb05a` passed: 190 app JVM tests, lint,
module boundaries, Debug/Release builds and the Android test APK. Owned API35
x86_64/4KiB emulator: 87 Android controls passed (ten new cases). Installed app
and test hashes were verified before and after. The initial compile error in one
test return type and a root test-selection error were preserved separately.

[evidence index](../qualification/f5-owner-cycle-evidence.json) records exact
source, artifacts and limitations. This remains software contract evidence; it
does not exercise a qualified heavy owner cycle or close any hardware/soak gate.
