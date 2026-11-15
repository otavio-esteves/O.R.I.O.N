# ORION-F5-METRICS-CLIENT-001 — existing MAIN endpoint metrics reader

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/119
Base: `43082e1`; API model/codec dependencies `bc6749b`, `1765004`.

## OBJECTIVE

Implement one manual finite optional metrics read for the actual last admitted
inference on the existing owned MAIN Cortex connection. Keep inference, resource
decisions, cancellation, deadlines and cleanup independent from this reader.

## FILES ALLOWED

`OrionCortexClient.kt` infer/acquire/release/telemetry regions; new bounded pure
correlation helper and tests; focused Android reader fixture/tests; mechanical
UNSUPPORTED implementations for appended methods in existing client-test Stub;
this package and a related MAIN-reader note. No AIDL, API/codec or service edits.
Dependency commits are integrated separately by the root agent.

## CONTRACTS INVOLVED

Accepted ADR-0003 optional 1.2 metrics, unchanged mandatory issued-history gates,
actual logical-to-issued-transport mapping, existing memory observation and
resource admission. Manual discovery is lazy: at most one optional discovery and
one query, only on an already-owned compatible connection. Unsupported/malformed
discovery is cached unavailable until another connection; no automatic retry.

## INVARIANTS INVOLVED

INV-001/011/012/013/027/028/032/033/036; one logical terminal remains unchanged.

## TESTS REQUIRED

Same endpoint/owner/independent epoch; mandatory history before optional discovery;
minor 1.1 never probes; malformed/throwing/unsupported optional calls leave inference
unchanged; no bind/acquire/health/clock/retry from metrics. One last mapping, actual
admission/bounds, old logical replay cannot retarget. New valid memory witness
during that inference's original resource callback; stale/missing/changed PID/boot
unavailable. Endpoint/correlation/close checks before and after delayed RPC; typed
codec rejection and null statuses. JVM pure tests and focused Android fixtures
provided; root centralizes builds/runtime validation.

## ACCEPTANCE CRITERIA

No monitor held across optional RPC. One bounded mapping and optional mask cache
belong to the actual Binder/ServiceConnection/epoch. Receipt identity must be newly
observed during the already-approved resource callback, with known matching boot,
sample time inside the actual decision/request window and actual admitted limits.
No extra resource evaluation, clock read or health query creates this witness.
Retained snapshots never become fresh resource observations at query time. Missing
fresh admission witness, changed endpoint or reported identity returns null. No
new model/profile/configuration, runtime launch, authority or phase-gate closure.

## OUT OF SCOPE

AIDL/service/native/codec implementation; auto-polling or publication; persistent
history, rebind/replay/recovery; unload changes owned by the other agent; hardware,
model/performance qualification, F4/F5 closure or F6. No Gradle/Docker/ADB here.

## IMPLEMENTATION / VALIDATION STATUS

Provided 15 pure JVM and 13 Android local-Stub tests. Peer review identified and
closed admission-time lower bounds for KNOWN_BOOT capture/wrapper start, plus
Throwable containment exclusively around optional allocation/projection/query.
A focused Android case preserves the exact original resource AssertionError;
optional Stub AssertionErrors instead return null. Missing clocks are never
repaired and no upper deadline bound is invented for later native completion.
`git diff --check` passes. No compiler/runtime checks were run by this author;
root integration must first provide appended AIDL and CURRENT minor 2 and then
execute the canonical checks. All source/runtime/hardware gates remain separate.

Follow-up causal guard: review found the initial scope enclosed all of `approved`,
including restart. Regression-only commit `b25b707` supplies a fresh receipt only
from restart and expects no metrics discovery/query. The fix brackets exactly
`resources(...)` after restart returns, using one private final optional holder
allocated inside diagnostic isolation. Load remains on its original uncaptured
path; resource/restart count, order, returned decision and original exceptions
are preserved. No new health/RPC/clock is introduced. This Android regression is
provided for root red/green execution, not claimed executed by the author.
