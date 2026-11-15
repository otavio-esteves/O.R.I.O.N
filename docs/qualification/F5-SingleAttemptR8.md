# Existing-owner single-attempt qualification entry

Scope: [issue128](https://github.com/otavio-esteves/O.R.I.O.N/issues/128),
[work package](../issues/ORION-F5-SINGLE-ATTEMPT-R8-001.md), dependencies:
[single-attempt core127](F5-SingleAttemptCore.md),
[cycle123](F5-OwnerCycleObservation.md) and [partial125](F5-CyclePartialProjection.md).
Accepted ADR0001/0002/0003 and all architecture/gates remain unchanged.

## Build and composition

The strict boolean property `orionCortexSingleAttempt` defaults false and requires
`orionQualificationRelease=true` when enabled. The app's ordinary Debug/Release
BuildConfig value is always false, including builds requested with both properties
true. Only qualificationRelease overrides it. Bridge source, exact keep rules
and framework runner are added only to that variant and mode, whose application
ID is `network.orion.qualification`. The mode is exclusive with baseline,
readiness and exit capture. `orionCortexGenerationMetrics` remains independent;
this mode never enables it automatically.

```text
-PorionQualificationRelease=true -PorionCortexSingleAttempt=true
:app:verifyQualificationNativePackage :app:assembleQualificationReleaseAndroidTest
```

FoundationRuntime alone may create a final capacity1 cycle observer, contained
against diagnostic allocation/linkage failure. It passes this reference to its
existing cached Cortex owner. The Application/FoundationRuntime/Room/owner/client
are not replaced or cloned. Plans, timing and trusted attempt template remain
omitted/default empty/null; this package supplies no operational parameters,
model, prompt or profile. Creating an observer cannot authorize inference.
Normal builds retain the same cached owner with no observer attached.

The reflection bridge verifies the actual final OrionApplication, installed
ApplicationInfo class/UID, process name, caller UID, qualification identity and
non-debuggable target. A caller's ContextWrapper strings alone cannot satisfy
these checks. It obtains app.mainRuntime on MAIN, rereading an initial cold null
inside the existing finite publication bound. It awaits the existing Core health
barrier and calls cortexRuntime once. It does not invoke Application.onCreate,
recovery or scheduling initialization. Actual app bootstrap may initialize its
own Room/DataStore/WorkManager state; that is separate from driver-issued work.

## Separate one-shot phases

The kept class is `network.orion.app.ai.OrionCortexSingleAttemptBridge`. Its only
framework reflection entries are static Bundle `prepare(Context)`,
`execute(Context)`, `observe(Context)` and `release(Context)`. Context is the only
argument: no model/path/workload/prompt/JSON/configuration/limit/candidate ID is
accepted. One private bounded state holds Driver(existingOwner) and at most one
opaque candidate. Atomic phase claims reject duplicate/concurrent/incorrect
ordering; phase response counters are frozen before the next manual phase is
published as eligible. No lock is held across MAIN waits, original run or manual
observation.

prepare calls core127 prepare once. With the current factory's empty plans it
returns the real `PREPARE_REFUSED / NO_QUALIFIED_PROFILE`, with Driver method
counters prepare1/execute0/read0/release0. The runner stops there: no request,
token/receipt, execute, query, release or synthetic runtime/native terminal is
manufactured. The optional partial field is absent.

A future genuinely selected trusted configuration can execute once. Selection
is not an admission permit: core127 and the original owner retain all guards.
The callback is one fixed function discarding its List without inspecting or
retaining text. A diagnostic fallback envelope and callback are allocated before
the original driver call. Its result reference is retained directly; only
failure-contained diagnostic rendering follows normal return. Original thrown/
cancelled objects leave execute unchanged, with only a primitive phase marker.
The runner unwraps InvocationTargetException and rethrows the same cause at its
boundary, then sanitizes without raw class/message/path/output. InterruptedException
restores the runner thread's interrupt state.

observe is a different reflection call, permitted only after normal
Execution.Invoked. It invokes readPartial at most once. Execute, its catch and
cleanup never read metrics or project a receipt. DriverNotAttempted and thrown
execution do not trigger observe. release is a separate technical operation
after completed execution/observation; it never closes shared owners, unloads,
rebinds, cancels or retries work. Concurrent observe/release cannot publish
completion or release an active candidate. Core127 independently enforces owned
candidate and finished-token checks.

The bridge uses the existing readiness 12s technical MAIN/bootstrap bound,
without new evidence-clock reads, domain deadline reset, workers or attempt loops.
The runner uses its framework instrumentation thread, with no Executor/thread
of its own. A coroutine timeout cannot establish that a blocked Binder/provider
operation terminated. Timeout/error output stays unavailable and never claims
native return, release or FINAL. Future actual request deadlines come only from
the existing owner clock and reviewed explicit template127.

## Closed output and framing

The runner accepts **no instrumentation arguments**. The test package/FQN is:

```text
network.orion.qualification.test/network.orion.app.compatibility.OrionCortexSingleAttemptInstrumentation
```

It invokes the kept methods using framework Context/Bundle only; it reflects no
private field, target DTO, obfuscated class, owner constructor or native API.
The ordinary qualification runner's default three probes and other modes remain
unchanged. This mode does not run their MAIN JNI probe.

Each phase Bundle has `orionSingleAttemptStatus` and optionally
`orionSingleAttemptPartial`. Status is closed JSON schema1/kind
`CORTEX_SINGLE_ATTEMPT_STATUS`. Its exact fields are schemaVersion, kind,
qualification, phase, failureCode, refusal, environment, environmentSource,
counterScope, prepareCalls, executeCalls, readCalls, releaseCalls,
ownerCallInvoked, partialPresent, sourceAuthenticated, physicalVerified,
profileApproved, productionInferenceApproved, runtimeEvidenceV1Complete,
fullF5GateClosed, fullSoakGateClosed and f6Authorized.

counterScope is `DRIVER_METHOD_CALLS`; each count is 0 or 1 and counts a method of
Driver127, not a bridge entry, native admission or generation. ownerCallInvoked
is true only for Execution.Invoked, false for DriverNotAttempted, otherwise null.
In particular, thrown execution may already have called the owner: null does not
prove no work. An invoked owner can itself return Unavailable without inference.
Refusal is an actual closed core127 enum name or null. Wrapper failures have a
separate closed failureCode and do not invent a domain result.

Environment is only bounded observed Build signals: ANDROID_EMULATOR,
PHYSICAL_DEVICE or UNVERIFIED, with `environmentSource=BUILD_SIGNALS_ONLY`.
It is neither a caller declaration nor physical authentication. Every approval/
authentication/gate flag is false; qualification is NOT_QUALIFIED. No IDs, hashes,
PID, boot/epoch, timestamps, labels, model/path/content or exception descriptions
leave the bridge. Native/slot/finality states are available only inside an actual
partial125, never inferred from execute or unload.

The final result preserves the **original observe status/partial pair**. Technical
release status is a third optional String `orionSingleAttemptReleaseStatus`.
It is a separate control header whose partialPresent is false; it must never
replace the OBSERVED header or be paired with the older partial. The partial125
String is retained without reencoding. A prepare refusal has only its prepare
header, with no partial or release header. No intermediate sendStatus frames or
retargeted copies are emitted.

Failures use distinct kind `CORTEX_SINGLE_ATTEMPT_RUNNER_FAILURE`, with schema1,
qualification NOT_QUALIFIED, phase RUNNER_FAILED, fixed failureCode,
environment UNVERIFIED, ownerCallInvoked null and the same eight false flags.
No Driver counters/refusal/receipt are fabricated. A completed technical release
may accompany failure under its separate control key; no observation/query
happens while an original error is propagating. Raw error strings never leave
the runner.

Final Bundle keys are the status String, optional partial String, optional
release-status String and fixed stream. Successful program observation returns
Activity.RESULT_OK (`INSTRUMENTATION_CODE: -1`), never qualification PASS.
Failure returns RESULT_CANCELED (`0`). Streams are exactly:

```text
DRIVER_NOT_ATTEMPTED / NOT_QUALIFIED
SINGLE_ATTEMPT_PARTIAL / NOT_QUALIFIED
SINGLE_ATTEMPT_UNAVAILABLE / NOT_QUALIFIED
```

Each stream has one leading/trailing newline. The first is for actual prepare
refusal or DriverNotAttempted; the second is a normal Invoked/manual program,
even if its partial remains unavailable; the last is runner failure.

Each header is at most 1024 UTF-8 bytes and partial125 at most 8192 UTF-8 bytes.
The final **textual** sum of keys/values, including separate release status and
stream, is at most 10KiB. Parcel Strings are UTF-16; this is not a 10KiB Parcel claim.
After functional return the runner independently checks actual Parcel.writeBundle
size at most 24KiB, below the architectural 256KiB IPC ceiling. Bound failure never
clips a partial or changes an original run result; publication becomes a small
sanitized runner failure. No new IPC is introduced.

## Validation and limits

Two default Android tests are supplied for the real existing FoundationRuntime:
same owner identity/no observer and absent bridge in the normal target. Root can
freeze this default Debug test APK, then run it against a normal Debug target
built with both properties true. Ordinary Release must also remain source false.
These checks concern installed behavior, not only BuildConfig expressions.

Ten HOST source-boundary controls check variant scoping, singleton composition,
no factory/configuration/work/clock/sink additions, identity/cold wait, phase
separation, original-error unwrapping, framing and the actual normal-artifact
assertions. They are not Gradle evaluation or Android evidence. The author ran
only these HOST checks and light diff/link checks; no Kotlin/Gradle/Docker/ADB or
device execution. Core127/partial125 mechanical positives remain separate; no
fake profile or workload is inserted into this R8 program.

Root owns canonical compilation/lint/normal/qualification/R8 artifacts and the
owned-emulator current-negative run, with source/app/test hashes and before/after
process evidence. Current expected result is prepare1, no other Driver calls,
NO_QUALIFIED_PROFILE, no partial, no driver-issued bind/model/inference. App
bootstrap effects are recorded separately. No source/hash in this minimized
output authenticates its installed producer. S21/real trusted configuration,
calibration, operational hardware capture and actual long-duration programs
remain separate. Nothing grants a profile, F4/F5/soak closure or F6 authority.
