# F4 qualification diagnostics and maintenance samples

Work package: [ORION-F4-DIAG-001](../issues/ORION-F4-DIAG-001.md).

The native qualification status embedded at build time comes from the versioned
native gate file. Missing/unknown metadata fails configuration; the read-only DTO
also treats unknown input as unavailable. This decision is not a runtime load test
of the current device and never grants inference or action authority.

Diagnostics now distinguish a public dedicated Memory Limiter exit reason from
description-based fallback classification, and unavailable exit history from no
observed limiter evidence. Fallback support does not assert that a platform has
enabled a Memory Limiter. No raw exit description or sensitive content is exposed.

Each completed normal maintenance pass emits at most one small JSON record under
`orion_maintenance`, schema 1. The record contains a generated observation UUID,
wall timestamp, boot count, main PID, same-boot elapsed time, per-runtime sequence,
closed origin/outcome fields and twenty numeric aggregate metrics. Queue counts
are read together in a transaction; size is a nearby filesystem observation.
Pruning counters describe the explicit maintenance-policy batch, not every
cleanup performed during recovery. The schema contains no domain IDs, arguments,
titles, ciphertext, descriptions, paths or exception messages.

A worker is labelled `HEARTBEAT` only when its actual WorkManager ID matches the
public unique-heartbeat query and not the reconciliation query. Unavailable or
ambiguous identity is `WORKER_UNCLASSIFIED`; registration itself emits no such
proof. A record proves a completed maintenance pass in that worker, not punctual
scheduling, worker autonomy, global Core readiness or external delivery.

This is optional, sampled log-buffer telemetry. Logging failure cannot change the
maintenance result, authorization, recovery, retries or readiness. The app creates
no new sampling job, wake lock, startup path, IPC endpoint, business persistence or
automatic revival. OS log retention remains bounded; missing/evicted records and
unknown boot identity must remain explicit evidence gaps. The companion host
collector reads this protocol without starting MAIN or opening Room.

Validation: sixty scoped JVM cases passed in canonical Docker, as did debug/test
assembly, app lint and the module validator with its thirty-six contracts. Two
actual API35/x86_64 emulator cases verified the native/memory status UI and that
aggregate reads preserve encrypted business records without exporting their content.
The [validation record](f4-diagnostics-evidence.json) binds source/APK identities
and raw logs. Emulator results do not close physical, native or 24-hour gates.
