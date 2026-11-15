# F5 — passive resource facts

Issue: [117](https://github.com/otavio-esteves/O.R.I.O.N/issues/117).
Work package: [ORION-F5-RESOURCE-FACTS-001](../issues/ORION-F5-RESOURCE-FACTS-001.md).

`OrionCortexRuntimeOwner` accepts an optional internal
`OrionCortexResourceFactBuffer`, disabled by default. Its existing resource and
restart closures retain the actual `OrionAndroidResourceSnapshot`, evaluate the
existing governor or restart guard once, and offer a detached projection before
returning the same decision object. Resource evaluation remains within its
existing `resourceMonitor`; the restart guard's receiver is resolved before
observation, preserving the original evaluation order. No other owner path
publishes facts. In particular, default `NO_QUALIFIED_PROFILE`, missing timing,
Core-not-ready and the read-only pre-resource query produce no synthetic resource
decision.

This is a connected source for future MAIN diagnostics, not a receipt validator,
exporter or automatic capture activation. Production bootstrap does not install
a collector. There is no IPC, persistence, policy mutation, new permission or
workload in this change. An internal caller may supply the owned buffer and drain
it; there is no injected callback or arbitrary sink.

Each fact contains the source snapshot's available battery, thermal, process
state, requested limits, MAIN PSS, AI PSS, aggregate/system memory, limitations
and sanitized exit signals. Governor facts copy the reported block, allowance,
memory risk, headroom and selected numeric plan. Restart facts copy the reported
block, eligibility, death counts, degradation, preload flag and candidate numeric
plan. Eligibility to reevaluate resources is not permission to restart or load.
Missing snapshot, history and numeric observations remain unavailable. A missing
snapshot with a real denial is distinct from an early path with no evaluation
and therefore no event.

Model identity is retained only when the source ID is a nonzero lowercase
64-character SHA-256; other IDs become unavailable. Arbitrary model IDs and tier
strings are omitted. Numeric budgets and limits are values supplied to the
actual decision, not device calibration or approved profiles. Exit signals retain
only the existing reason, wall timestamp, classification and limiter-evidence
flag; no Android description, trace, exception text, prompt, path or user payload
is copied.

`sampledAt` is copied without refreshing it. Snapshot time is the observer's
existing collection-start reference, not collection completion or decision time.
The decision's optional `sampledAt` and AI exit-history reference retain their
existing meanings. Exit wall timestamps remain wall timestamps. The hook reads
no clock, platform API, memory telemetry, Binder status or health endpoint. These
DTOs do not expose a decision-evaluation timestamp, AI PID, connection epoch,
request identity, policy revision or cooldown fence; the hook supplies none.
Buffer order is insertion order only and does not establish those correlations.

The final buffer permits 1–64 facts (default 16). Producers use `tryLock`; full
or contended storage drops the new fact without projection or retry. Each exit
list copies at most 64 entries plus one truncation sentinel, separately from the
observer's platform-history limitation. Copied collections are immutable and do
not retain the source containers. A failed projection drops that fact; it never
replaces the already-computed decision or retries evaluation. Original observation
or evaluation exceptions still escape before recording is attempted.

A contended drain consumes nothing and reports `drainContended`. Loss counters
are cumulative lower bounds and are not reset by draining. Counter updates make
one CAS attempt; contention or saturation makes `lossAccountingComplete` false.
Projection failure also makes it false. A batch is not an atomic snapshot of
concurrently changing counters. Completeness here concerns local buffer loss
accounting only, never process-memory completeness, history completeness or
evidence provenance. The hook may be absent, not yet reached or lossy; an empty
batch establishes none of those conditions by itself.

Twelve focused JVM tests are supplied for real governor/guard result projection,
same-object return, no added clocks, unavailability, sanitization, immutable
bounded copies, disabled capture, projection faults and contention/concurrency.
One Android owner test is supplied for early refusals and the read-only query,
using existing negative-control fixtures that cannot bind or execute a workload.
These fixtures are not qualified plans. The author did not run Gradle, Docker,
ADB or a device; canonical execution belongs to the coordinating agent and CI.

This hook does not establish physical memory completeness, MemoryLimiter
behavior, calibration, a qualified profile, hardware provenance, F5/F6 closure or
any admission gate. The S21 and the corresponding real qualification procedure
remain required for those claims.
