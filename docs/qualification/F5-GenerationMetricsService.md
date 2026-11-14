# F5 — request-local AI generation-metrics adapter

Scope: [issue119](https://github.com/otavio-esteves/O.R.I.O.N/issues/119),
[accepted ADR-0003](../adr/ADR-0003-optional-cortex-generation-metrics.md) and
[service work package](../issues/ORION-F5-GENERATION-METRICS-SERVICE-001.md).
This note covers the AI service adapter; the closed API/codec/AIDL and explicit
llama receipt port are coordinated dependencies, not implemented in this package.

The library build property `orionCortexGenerationMetrics` is strictly `true` or
`false`, default `false`. Enabling it also requires
`-PorionQualificationRelease=true`, whose application build uses the separate
qualification identity. This opt-in neither installs a qualified model/resource
profile nor permits inference. The existing admission, deadline, callback,
cancellation, native watchdog and cleanup paths remain authoritative. With the
property disabled the potential supported metric mask is zero, no native capture
is created and the original generation method remains in use. A supporting
service still implements the optional wire operation; discovery does not promise
that a source exists for a particular request.

Only an actually admitted issued inference can own a recorder. Its bounds are
that inference's `maxTokens` and the context stored alongside the actual successful
model load. Later unload clears the service's context with the loaded model ID;
already-owned recorder bounds do not change. Load requests, cancellation tombstones
and unused reservations do not fabricate inference snapshots. Legacy non-issued
requests keep their previous behavior and do not gain a metrics recorder.

Each retained inference entry owns at most one immutable cached snapshot. There
is no second request map/history, persistence, output or prompt retention added.
Discovery and query enforce the existing private caller UID. Query uses the
closed codec and the existing history lookup, returns retained entries before
the retirement watermark and never calls `checkDeadline`, reads a clock, creates
a runtime, invokes generation, loads, resets cancellation, polls or starts work.
Unused or foreign valid IDs return `NOT_FOUND`; admitted non-inference entries
return `INVALID_QUERY`; retired entries remain `RETIRED`. Syntax/type/schema
errors retain their separate closed status. These statuses authorize no retry.

The native adapter creates a fresh one-shot service capture on the same existing
runtime and generation call. Its current source provides return-time facts, so
the initial snapshot is `NOT_STARTED` and a wrapper attempt becomes `UNKNOWN`,
not `RUNNING`. Only a usable receipt from the original call can publish
`JNI_RETURNED`, its actual native finish, partial-work/stage facts and supported
count/duration fields. Metric keys map explicitly from the service-facing llama
enum into the wire enum. The snapshot validates the actual admitted bounds.
No native internal array, holder, handle or arbitrary observer callback is exposed
by the adapter. Token-limit finish remains distinct from logical `SUCCEEDED`.
Normal callbacks returning false are not claims of accepted/delivered output.

A positive invocation identity is issued from a process-global monotonic counter
only for an actual receipt. It is never recycled; contention/exhaustion makes
telemetry unavailable. Neither an ID nor a PID is identity across process death.
There are no new time reads anywhere in the adapter: all snapshots explicitly use
`timeState=UNAVAILABLE`, with no boot/capture/wrapper timestamps. In particular,
the caller's deadline is never relabeled as a service capture time. Source API-wall
nanoseconds retain `STEADY_CLOCK_NS` semantics, not total backend compute, elapsed
freshness, TTFT, throughput or a qualification result.

Logical terminal is recorded at the original `finish` assignment, or at the
existing callback-failure assignment. It can precede receipt publication. The
request's recorder field is volatile; initial publication also reconciles a
terminal racing the recorder's allocation. Recorder updates do not acquire the
service lock while holding the request monitor; queries read an immutable volatile
snapshot. Repeating unchanged terminal facts and cached reads does not advance
the sequence or manufacture fresh measurements.

`JNI_RETURNED` leaves the worker slot `OWNED` and snapshot `UPDATING`. A release
marker occurs only after the original watcher/death cleanup, active-slot cleanup
and `watchdog.finishWork` have returned. Under the original history lock it then
checks that the same request is still retained; a retired/replaced entry is
discarded and never reinserted. Finality additionally requires an actual logical
terminal and observed JNI return, or proof that no wrapper attempt occurred.
A worker that ended before a wrapper attempt can publish `NOT_EXECUTED` with
absent counts, never synthetic zero counts. An attempted wrapper without a usable
receipt becomes `UNAVAILABLE` after cleanup. No native/backend synchronization
guarantee follows from the release marker.

Collector allocation, projection, validation and publication failures are isolated
from inference. The original native call's return/throw outcome remains unchanged;
the diagnostic `finally` does not clear or replace native/callback exceptions.
Faulted/exhausted recorder state stays unavailable and cannot later resurrect.
Sequence overflow cannot wrap. The fixed recorder retains no exception text,
user content or arbitrary source labels. No new production callback or background
sampler is added. Opt-in allocation/locking overhead still requires target-device
measurement; it has not been physically calibrated.

Sixteen pure JVM tests are supplied for cached reads, state separation, typed
port projection, admitted bounds, partial/absent facts, sequence/invocation limits,
disabled/fault paths and original-history retirement. Native receipts in these
tests are mechanical reflection fixtures; they do not execute JNI or prove a real
generation. Two Android tests are supplied for real Binder discovery/query,
unchanged legacy capabilities, malformed/non-inference/unused/retired IDs and no
runtime initialization, without a model or inference. No tests/builds or device
operations were executed by this author. Root/CI must validate the combined API,
AIDL, port and service, including native/Android/R8 compatibility.

The S21 remains absent. This package supplies no physical provenance, calibrated
budget, qualified profile, memory completeness, MemoryLimiter stimulus, sustained
workload, F5 closure or F6 authorization. Default production plans remain empty.
