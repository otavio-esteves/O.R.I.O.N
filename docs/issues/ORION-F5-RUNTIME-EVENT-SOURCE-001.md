# ORION-F5-RUNTIME-EVENT-SOURCE-001 — unwired capture foundation

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/144.
Integration base: main `bd537187e93255d03e6c47972a4314239753df5b`.
Contract: [RuntimeCaptureContract141](../qualification/F5-RuntimeCaptureContract.md),
integrated by PR #145; split-byte source #140 is integrated by PR #143. This unit supplies no genuine event hooks or producer.

OBJECTIVE: Add a final observation-only native elapsed clock and a pure bounded
MAIN event recorder for later real source hooks. Binding uses only original
already-supplied preparation/validated samples; no functional/provider read.

FILES ALLOWED: New core/time observation clock/value types and focused tests;
new internal app/ai recorder/DTO types and focused JVM tests; this bounded note.
Existing clocks/lifecycle/client/owner/factory/driver/wire/formatters/profiles,
persistence and other worktrees are excluded.

CONTRACTS INVOLVED: RuntimeCaptureContract141 event identity/time/loss semantics,
RuntimeEvidence v1; original Clock/BootSessionId and source140 bytes remain intact.
ADR0001/0002/0003 are unchanged; future hook/composition ADR screen is separate.

INVARIANTS INVOLVED: INV-009/011/012/013/019/027/028/032/033/035/036.

TESTS REQUIRED: Inactive/bind-once observational clock with synthetic finite
internal scalar input; original clock count unchanged, unknown/mismatched boot,
regression/negative/signed-wrap/exhaustion/contention/sticky faults. Recorder exact
4097 chunk/128 memory/32 operation limits and independent first terminal; supplied
times, wrong owner/token/model/endpoint/boot, sequence, split zero/unavailable,
terminal consistency, loss/fault isolation and immutable manual snapshots.

ACCEPTANCE CRITERIA: No OrionClock implementation or implicit domain conversion;
no arbitrary production callback/provider. Native calls only in the new final time
adapter. One cached observational boot per capture, never a fresh domain witness
or lease; no repair/clamp/retimestamp. Pure recorder accepts already-observed DTOs,
uses tryLock/no waits and explicit sticky loss/unknown. Technical identities stay
bounded/private/redacted; no request/model entity, content, paths or raw errors.
Only actual supplied event data are retained; this foundation remains unwired.

OUT OF SCOPE: Original algorithm hooks, memory sampling/RPC/workers, delivery
consumer, encoder/HOST/Android composition, operational configuration, native/
physical/API/MemoryLimiter/soak execution, complete RuntimeEvidence v1 or gates.

IMPLEMENTATION: The final time adapter exposes only createInactive, bind once from
existing original witness values, confirm a supplied existing witness, actual
native elapsed observation, and separate manual snapshots. Its capture-only
moment cannot directly act as MonotonicInstant/OrionClock. The boot cache is
observational only, never fresh domain/lease evidence; unknown/discontinuous,
negative/signed-wrap, source faults and contention remain sticky unavailable.
The finite scalar counter factory is internal to core/time, with no callback.

The app recorder receives only already-observed typed scalar DTOs, status/chunk
metadata and an exact opaque reference fence. It performs no acquisition itself.
It has exactly 4097 chunks, 128 memory rows, 32 operation slots and an independent
first-terminal claim/slot. It preserves staged earlier ordered-decision times
when publication follows first terminal; an internal high-water does not rewrite
any source time. Manual snapshots copy bounded immutable lists/sets and a minimized
historical association without owner/client/Binder references. Private bounded
issued/logical IDs expose only a separate manual UTF-8 SHA-256 seam, never raw
getters/logs. True dispatch, terminal and cleanup marker names denote future
acquisition seams, not observations established by this foundation.

Memory DTOs require supplied closed windows, actual ordered collection/sample
times, positive consistent native-inclusive self-PSS, exact PID/boot/state and
explicit age/total-memory values. No sampler or window classification is included.
All snapshots explicitly remain unwired, unauthenticated and not complete v1;
constructor/DTO/parser consistency grants no profile or inference authority.
Representation capacities are fixed defense limits, not operational defaults.

RECOVERY / PRIVACY / UPGRADE: Only ephemeral technical data are retained; process
death discards them without replay/rebind/retry or changes to shared cleanup.
No content, full request/model entity, paths or raw errors are retained. There is
no schema, persistence/migration, component, process or IPC change. Source creation
and manual snapshot/hash failure are unavailable telemetry, not domain failure.
Future hook/sampler/export/Android composition must pass their own boundary screen.

READ-ONLY REVIEW FOLLOW-UP: An in-flight counter read now rechecks invalidation
after acquisition and immediately before publication/high-water advancement. The
last check is its linearization point, not an impossible return-order guarantee
across overlapping threads; invalidated manual snapshots remain unusable for
export. A deterministic finite-source reflection test covers rebind/confirmation/
snapshot invalidation during that read without adding any production callback.
Memory rows require collection start equal to MAIN sample, strict source age,
validation implying runtime, non-disappearing wrapper/validation observations and
true flags for LOADED/INFERENCING or post-successful-load rows, including unload.
Positive seven-phase fixtures now carry consistent synthetic native flags.

VALIDATION: Canonical Docker Android validation passed in 17m 10s:
`:core:time:testDebugUnitTest :ai:api:testDebugUnitTest :app:testDebugUnitTest
:app:compileDebugAndroidTestKotlin verifyModuleBoundaries testModuleBoundaries
:core:time:lintDebug :ai:api:lintDebug :app:lintDebug --max-workers=2`.
The selected reports contain 309 JVM tests in 40 suites (22 core/time, 58 ai/api,
229 app), with zero failures/errors/skips; 23 tests are new (10 clock, 13 recorder).
Android tests were compiled only, not executed. Independent review found and
resolved clock invalidation and native memory-flag consistency bugs; the final
source was re-reviewed. Rebase preserved all five tested source/test hashes.
Required exact-head PR CI remains pending. No physical, native-model, API37,
24-hour or source-authentication evidence is supplied. Original production files
and suites remain untouched. No architecture/ADR waiver is claimed.
