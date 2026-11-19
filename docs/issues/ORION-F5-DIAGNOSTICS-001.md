# ORION-F5-DIAGNOSTICS-001 — Isolate and refresh diagnostic observations

OBJECTIVE: Correct the two diagnostic P2 findings from the 2026-11-17 audit:
optional scheduling/queue read failures must not escape into the Activity, and
visible observations must refresh after lifecycle/settings and queue changes.

FILES ALLOWED: `app/src/main/kotlin/network/orion/app/MainActivity.kt`,
`app/src/main/kotlin/network/orion/app/OrionDiagnosticsObservation.kt`, their
focused JVM/Android tests, and this work package.

CONTRACTS INVOLVED: OrionSchedulingDiagnostics, TaskQueueDiagnostics,
OrionHealthReport, Activity lifecycle; AGENTS sections 19/29/31.

INVARIANTS INVOLVED: INV-011/023/030/035. Observation is read-only and never
changes readiness, recovery, scheduling, confirmation or action authority.

TESTS REQUIRED: Each source can fail independently; stale values are cleared;
cancellation and interruption propagate; non-Exception failures propagate;
bounded polling refreshes observations and stops on cancellation; the production
Compose effect follows RESUMED/pause/resume and refresh-key changes without a new
recovery. Run focused Docker JVM/lint/APK compilation, then canonical checks.

ACCEPTANCE CRITERIA: A failed source yields null without losing another available
source. Only the selected, resumed diagnostics screen polls, serially at a bounded
two-second interval, with immediate refresh on resume/settings/recovery. No hidden
ingress drain, side effect, exception text or user payload enters observations.

OUT OF SCOPE: F6, AI/profile activation, Room/DataStore schema, resource-policy or
process/IPC changes; physical/soak qualification. No ADR is required for these
bug fixes inside the existing read-only boundary.

PROCESS-DEATH / SECURITY / MIGRATION: Observation state is ephemeral and recreated
from current Core reads. Failure does not retry an action or open the Startup
Barrier. Technical counts only; no persistence, migrations or logging added.
