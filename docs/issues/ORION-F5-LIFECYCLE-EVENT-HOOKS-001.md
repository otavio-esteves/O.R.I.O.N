# ORION-F5-LIFECYCLE-EVENT-HOOKS-001 — genuine optional lifecycle event source

Tracking: [#146](https://github.com/otavio-esteves/O.R.I.O.N/issues/146).
Depends on [contract #141](https://github.com/otavio-esteves/O.R.I.O.N/issues/141) and the
unwired clock/recorder foundation #144. This package does not complete that
producer or wire the Client/Owner/app/Android composition.

OBJECTIVE: Record the actual first lifecycle terminal and original ordered chunk
acceptance events, preserving functional clock/validation/encoding/return behavior.

FILES ALLOWED: `ai/api` lifecycle, minimum scalar byte-carrier evolution, one new
final event source and focused JVM tests; this note. No app, core/time, Gradle,
factory, build mode, IPC, profile, formatter or other worktree changes.

CONTRACTS: RuntimeCaptureContract141; final clock144; original byte source140,
facts132 and deadline/chunk semantics. INV-009/011/012/013/019/027/028/032/035/036.

ACCEPTANCE: An absent source stays disabled by default, without a diagnostic
envelope/enum/observation-clock read on that path. The final optional source has
no observer interface, arbitrary callback/provider or dependency on app. Source
mutators are internal to ai/api; snapshots contain only immutable technical
scalars. The returned update, original chunks/List, status and thrown objects
remain original. No additional functional clock/provider, validation, encoding
or RPC is introduced; encoding still happens exactly inside the original guard.

## Explicit activation and witness boundary

Construction only claims one source and retains the request's original
preparation instant. Actual original `checkDeadline` calls supply their existing
instant/classification directly; no extra call obtains a witness. The source
stores only the most recent original values, not the request, model or content.

`OrionInferenceLifecycle.activateEventCapture()` is final and one-shot. It calls
the foundation clock's bind with those already-existing values and performs no
counter/provider/functional-clock read. The future app adapter must call this
method only from the real winning `runOnce` owner; constructors/replays/joins do
not activate automatically. This package cannot authenticate that future owner
association and provides no authority from activation or an opaque object.

If the lifecycle became terminal before activation, the source marks its first
terminal unavailable and refuses activation. It never recaptures the constructor
or an earlier terminal at a later time. After activation, each existing original
witness confirms the observational cache. Unknown/regressed/cross-boot input
invalidates capture without changing the original classification or result.
Neither the cache nor observation moments become domain deadlines/leases.

## Genuine event and publication points

The first-terminal hook follows the original winning assignment in `finish`,
before pending-state cleanup. It receives the exact original status and origin.
A reserved one-shot terminal slot is independent of chunk saturation. If its
time or publication is unavailable, that loss is sticky; later status/cancel/
death/callbacks cannot replace it. Before activation no observation read occurs.

Each actual ordered release stages only sequence/final/failure enums, validated
numeric byte counts and a dedicated observation moment. Pending admission,
duplicates, wrong requests, rejected inputs and late chunks do not become ordered
events. The winning final chunk uses the same exact moment object as its first
terminal; it performs no second read or retimestamping.

One original scalar carrier serves both the old facts and the new source. Its
input is the original two UTF-8 sizes and total, including actual known zero.
It also receives the original pending-removal revalidation. A failed/missing
carrier leaves both parts null, never zero or a reconstructed split. The new
source does not rely on the old collector's 32 retained updates and adds no
encoding or content inspection.

Ordered publication occurs only after successful construction of the original
`OrionInferenceUpdate`; publication requires that constructed object and reads
only its receipt, retaining neither update nor List. A stage is call-local and
holds no content. If original validation/construction throws, its numeric stage
is never published; an outstanding-update count preserves the missing return.
No catch surrounds or translates the original error. Optional bookkeeping errors
are contained and cannot replace the original return/Error/cancellation.

The source retains at most 4097 ordered events and at most nine staged releases
per accept call. All optional locks use nonwaiting tryLock. Capacity, contention,
missing clocks/parts, unpublished stages and failed accounting remain explicit.
Manual snapshots are separate, immutable and never implicitly drained/exported.
Every authentication/profile/production/F5/soak/F6 flag remains false.

## Specific proof and limits

Focused JVM controls cover absent/present/unavailable equivalence; original clock
read sequence and original Throwable identity; one-shot/inactive activation;
unknown boot and first-terminal origins; pending revalidation; actual multibyte,
zero and unavailable parts; duplicates/reorder/conflict/rejection; stage loss
after an actual original pending-validation exception; real optional publication
Error containment; reserved terminal and 4097 events independent of old32 facts.

For capacity controls only, tests reflect the existing internal finite clock
factory and replace its private finite scalar collection with 4099 constant
values. No test provider/factory is exposed in production and core/time is not
modified. These are synthetic mechanical source tests, not real timing, native,
Android execution, model/profile or physical evidence.

The original update constructor builds from an internal local ArrayList and
offers no faithful allocation-failure seam. No production factory/callback or
artificial mirrored constructor test was added to manufacture that failure.
The constructor-before-publication order is verified by source review; dynamic
original-path proof covers the preceding pending-validation failure, not an OOM
inside the constructor itself. Root owns canonical execution and CI; the author
runs only static diff review, with no Gradle/Docker/ADB/CI/push.

RECOVERY / PRIVACY / UPGRADE: All data are bounded ephemeral numeric metadata.
No content, IDs, request/model objects, files, paths or raw errors are retained by
the new source. Process death loses capture without execution resume/retry. No
schema, storage, recovery, lease, native, IPC or authority changes occur.

OUT OF SCOPE: App association/Client/Owner/load/unload hooks, sampler, delivery
consumer, rendering/HOST/controller/Android composition, operational values,
models/templates/profiles, calibration, physical/API/MemoryLimiter/24-hour
execution and F4/F5/soak/F6 closure. Missing hardware blocks those later evidence
claims, not implementation of these passive mechanisms.
