# One trusted MAIN owner attempt — core preparation

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/127

## OBJECTIVE

Prepare and consume one opaque candidature for the existing MAIN Owner from an
explicit trusted internal template. Keep execution and manual PARTIAL observation
separate. Current empty plans/null template refuse honestly; no operational
configuration or qualification is supplied.

## FILES ALLOWED

- `app/src/main/kotlin/network/orion/app/ai/OrionCortexRuntimeOwner.kt`: narrow selection/request preparation only.
- New final internal single-attempt template/candidate/driver types in the same package.
- `OrionCortexCycleObservation.kt`: approved addendum, discard only an exact-owner unclaimed/unfinished reservation; existing release unchanged.
- Focused corresponding JVM/Android tests.
- This package and a qualification contract note.

## CONTRACTS INVOLVED

Architecture V3.2 MAIN ownership, ResourceGovernor first, Core barrier, no automatic
retry; existing Owner runObserved/readCycleObservation/release; issue125 bounded
PARTIAL projector. ADR0001/2/3 are unaffected.

## INVARIANTS INVOLVED

INV-002/009/011/012/013/014/023/027/028/032/033/035/036. Existing MAIN-only Room
ownership is preserved. Selection is never a resource permit.

## TESTS REQUIRED

- Empty configuration refuses before registry/request/clock/inventory/bind; missing/invalid template,
  READY/profile mismatch, closed/Core blocked.
- Foreign owner/driver and concurrent consumption cannot execute twice.
- Original guards, callback/List, result, Error and cancellation identity.
- Manual observation only after run returns/throws, no query or fresh resource observation in run/error handling.
- Explicitly mechanical positive controls, no fixture qualification.
- Unclaimed reservation discard restores capacity; foreign/claimed/finished discard rejected; existing release unchanged.

Root runs canonical Docker/Gradle/Android checks. Author does not run those tools.

## ACCEPTANCE CRITERIA

Template is immutable internal/null-default, references exactly one existing trusted
plan and explicitly declares request parameters/workload. No values are added by
this package and no CLI/JSON controls it. Selection applies original early gates,
reads only the referenced READY row and existing profile binding, with no scan,
recovery, import or fallback. Private candidate is owner/driver-bound, one-use and
bounded. ID/deadline creation follows selection, consumption and observer reservation.
Original runObserved performs all guards unchanged. Pre-run refusal is distinct
DRIVER_NOT_ATTEMPTED, never a fabricated run result or cycle. Manual read/project/release
remain explicit and NOT_QUALIFIED/PARTIAL. No query/clock/poll/thread inside original run.

## OUT OF SCOPE

FoundationRuntime/factory/build/bridge/runner/Client changes; new IPC/native/DB or
persistence; profile/timing/budget activation, model import, calibration; HOST export,
loops/stress/soak/24h; physical qualification, RuntimeEvidence v1 completion, F5/F6 closure.

## VALIDATION STATUS

Implementation and 7 JVM / 9 Android tests provided, pending peer review and root
canonical execution. Author ran staged whitespace checks and a source-equality assertion (passed): the
original Owner execution/read/release body plus Client/FoundationRuntime/app build
are unchanged from base 8fb0b81. No Kotlin/build/Docker/ADB/device execution claimed. No schema/persistence/exported component or
profile enrollment changes. Process death discards only ephemeral candidates and
observations; no automatic resume/replay is introduced. Qualification note:
[F5-SingleAttemptCore](../qualification/F5-SingleAttemptCore.md).

ROOT VALIDATION: source `e2a76d1` passed canonical Docker app JVM213 (seven new),
lint, module boundaries, Debug/Release and Android test packaging. Owned API35
x86_64/4KiB emulator passed96 focused Android controls (nine new), with installed
app/test hashes verified before and after. [Evidence index](../qualification/f5-single-attempt-core-evidence.json)
records exact source/artifact/report hashes. No heavy owner admission, physical
qualification, configured workload or phase closure is claimed.
