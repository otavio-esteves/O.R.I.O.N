# One trusted owner attempt: core API preparation

[Work package](../issues/ORION-F5-SINGLE-ATTEMPT-CORE-001.md), issue #127.

This package supplies no operational values, executable driver component, build
flag, factory composition, model, qualified profile or hardware result. The
production factory is unchanged: its plans are empty and its new optional
`singleAttemptTemplate` remains null. No caller/CLI/JSON decoder can populate this
configuration. Template construction alone never establishes qualification.

## Existing-owner API

An internal consumer may construct `OrionCortexSingleAttemptDriver(existingOwner)`.
It creates no Owner, Client, importer, Room database, binding or worker. One driver
permits one `prepare()` and at most one candidate. There is no candidate collection,
history, automatic retry, alternate model selection or loop.

- `suspend prepare(): Selection` returns `Selected(candidate)` or a closed refusal.
- `suspend execute(candidate, originalOnChunks): Execution` returns
  `DriverNotAttempted(reason)` or `Invoked(originalResult)`. The latter contains the
  exact original Owner result reference. Original thrown/cancelled objects escape.
- `readPartial(candidate)` is an explicit later manual call, using the existing
  issue123 reader and issue125 projector. It returns a bounded retained PARTIAL
  projection or null. It is never called by execute, cleanup or error propagation.
- `release(candidate)` explicitly removes only a completed technical observation.

Candidate construction is private. Access to its template/token is fenced by an
opaque driver secret and the exact existing Owner identity. Consumption checks
foreign ownership before CAS, including after prior use. No model ID, workload,
registry row/path/label, request ID or observer token is exposed as a candidate
property or echoed by its string representation. Internal gated implementation
methods are not new admission or external APIs.

## Trusted template and selection

`OrionCortexAttemptTemplate` is a final internal class, not a data class with a
content-bearing toString. A future reviewed trusted composition must explicitly
supply the chosen context/threads/tokens, request duration, temperature, priority
and fixed technical workload prompt/context. None has an operational default in
this package. The model reference must match exactly one plan already held by the
Owner. Maxima, keepAlive, freshness and restart cooldown never choose request
parameters. Protocol-bound duration zero remains immediately expired, not a useful
workload or admission. Structural bounds are not calibrated operating values.

Selection applies existing `preResourceReadiness` predicates first. Empty plans
return NO_QUALIFIED_PROFILE before any additional registry read, ID/deadline/request
creation, inventory or binding. This is not a claim that application bootstrap
created no technical Room/Keystore state. Observer and template availability are
explicit prerequisites. Only the selected template's registry row is read; it must
already be READY and match the existing `profileMatchesReadyModel` check. No scan,
import, recovery, file copy, fallback or enrollment occurs during selection.
The selected row is not retained by the candidate.

SELECTED is candidature only, not a resource/restart permit or an admitted turn.
Execution consumes the candidate once, rechecks early Owner readiness, reserves the
real observer entry, then creates the actual request deadline/ID with the existing
Owner clock/ledger. A missing boot, clock failure or preparation failure cannot
manufacture evidence time or a run result. Unknown boot is refused. No observation
or resource decision is refreshed by this new preparation code.

`runObserved` is called once with the unchanged callback function. Its existing
inventory, registry/file hash, request history, deadline, Core, ResourceGovernor,
restart, load/inference/unload/cancellation and endpoint guards remain unchanged.
Selection may become stale; it never waives these original checks. An original
Unavailable result after invocation is an actual Owner result, unlike a pre-run
DRIVER_NOT_ATTEMPTED refusal. An original retained result is never a new execution.

## Closed technical refusal codes

These are internal technical result codes, not IPC methods or admission flags.
Unknown values must never be interpreted as selection or execution success.

| Code | Meaning |
| ---: | --- |
| 0 | SELECTED, never a permit |
| 1 | OWNER_UNAVAILABLE (including a foreign driver/owner candidate) |
| 2 | CLOSED |
| 3 | NO_QUALIFIED_PROFILE |
| 4 | RESOURCE_CONFIGURATION_UNAVAILABLE |
| 5 | CORE_NOT_READY |
| 6 | OBSERVER_UNAVAILABLE |
| 7 | TEMPLATE_UNAVAILABLE |
| 8 | TEMPLATE_INVALID |
| 9 | MODEL_NOT_READY |
| 10 | PROFILE_MISMATCH |
| 11 | ALREADY_CONSUMED |
| 12 | SELECTION_UNAVAILABLE |

A selection/execute refusal before invocation has no fabricated original result,
cycle receipt, terminal, JNI state, stream or qualified model claim. A pre-run
cancellation/interruption retains its original identity; a cancelled preparation
is still consumed and does not gain retry authority. A failure of the Core predicate
in execute's pre-run readiness check also propagates before invocation, without
creating a run receipt; the original run itself remains outside preparation catches.

## Return isolation and reservation disposal

The Invoked envelope is allocated before calling the original run. After run
returns, execute only stores the original result and marks a primitive completion
flag; its finally performs no manual read, projection, RPC, clock or worker action.
The original onChunks function/List and exceptions are not wrapped or translated.
The driver adds no observation clock to the original functional path.

Before invocation only, failed request preparation may discard the exact reserved
entry with the approved minimal issue123 extension: `discardUnclaimed` uses
tryLock and requires exact owner, unclaimed, unfinished and not lost. It creates no
returned/threw/result or terminal record, and does not alter the old release API.
A token whose claim lost contention could accompany actual uncaptured work, so
`lost` categorically forbids discard. Contention can prevent disposal and leave a
bounded PENDING/lost slot. That is evidence capacity degradation, not ongoing work,
a recoverable promise, or permission to retry. No running/claimed/finished entry
is discarded by this API. No disposal occurs after runCallIssued becomes true.

## Manual phase, boundaries and validation

Before the original run finishes/throws, readPartial returns null without invoking
the reader or projector. Later it may use only the already-owned/fenced endpoint
through issue123/119: no bind, resource refresh, extra clock, health call, polling
or retry. Identity loss, supersession, unsupported optional methods and partial or
lost capture remain unavailable/incomplete. Existing reader caching bounds the
manual metrics phase; repeated projection is not a new current-state assertion.
Manual Binder latency cannot delay the already-returned original call; this core
package adds no new outer timeout or hidden background query.

Every projection remains ORION_MAIN_CYCLE_PARTIAL/NOT_QUALIFIED. It cannot establish
source authentication, complete RuntimeEvidence v1, physical execution, calibrated
profiles, sustained workload, throughput, 24 hours, F5/soak closure or F6 authority.
The future separate qualification composition must supply one observer to the
existing singleton Owner, refuse an unobserved existing Owner rather than replace
it, and preserve disabled normal variants. That composition is not implemented here.

Provided tests cover pure template/UTF-8/closed-code/claim races and observer disposal,
and actual Owner negative/selection controls using synthetic metadata/clock without
an executable model. Android tests prove real original Unavailable/Error/cancel
identity, concurrent consumption and separate manual reads, with inventory/bind
set to reject. They do not pass heavy admission or claim callback/native/model
qualification. Callback forwarding is direct in source; existing issue123 Client
controls cover original callback/List/Error behavior. No new arbitrary callback
injection seam or production reflection is added. One Android assertion uses
test-only token reflection to compare the original result object in the real receipt.

Process death loses only ephemeral candidate/observer state. There is no persisted
attempt, auto-resume, model cleanup or blind replay. Reboot/clock/endpoint checks in
the original execution remain authoritative. No schema or migration changes occur;
no new exported component, permission, log, payload persistence or sensitive output
is introduced. No Gradle/Docker/ADB/device execution was performed by the author;
root owns canonical validation. Hardware and a reviewed future trusted template
remain prerequisites for positive physical execution.
