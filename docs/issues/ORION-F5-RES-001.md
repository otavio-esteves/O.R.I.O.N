# ORION-F5-RES-001 — Minimum resource decision before Cortex

OBJECTIVE: Implement a deterministic ResourceGovernor policy before any Cortex
service/model load exists, preserving the current unavailable runtime fallback.

FILES ALLOWED: core/resources policy/DTOs and tests; its core/time API dependency;
focused F5 qualification report. No app/service/IPC/database/native changes.

CONTRACTS: Architecture §§33–35; Master Plan §11.9; functional entry decision in
ORION-F4-AUDIT-001. INV-002/013/027/028/032. Resource approval never grants Skill or
business authorization and never starts/restarts a process.

TESTS REQUIRED: Explicit trusted model/limit binding; unknown/malformed/stale inputs;
boot change/monotonic regression; foreground/importance, battery/thermal/power saver;
recent memory pressure/exit and clock rollback; per-process plus aggregate plus
system headroom; arithmetic extremes; requested context/thread/token bounds;
fresh decision per call, defensive copies, provider failure/cancellation. Run
focused Docker tests, boundary validation and canonical check/build/lint.

ACCEPTANCE: All original ResourceGovernor methods are implemented without cached
permission. One atomic evaluation yields the matching model/limits and fresh
boot-bound observation; callers cannot infer approval from a headroom estimate.
Limits/thresholds are supplied by trusted qualified plans, never hardcoded as
hardware qualification. Missing data or profile denies loading/inference. A memory
signal changes the decision/risk and leaves execution limits/keep-alive zero.

OUT OF SCOPE: Android resource adapter, app wiring, qualified real-model profiles,
Cortex/JNI/IPC, inference, model registry/import, process relaunch/circuit breaker,
actual memory/performance/16KiB qualification, F8 adaptive governor or F5 exit approval.

## Consolidated validation

Canonical checks, scoped emulator results and retained failed attempts are recorded
in [F5 Runtime Gate](../qualification/F5-RuntimeGate.md) and its
[evidence record](../qualification/f5-runtime-evidence.json). That report supersedes
implementation-time validation-pending notes without changing this work package's
scope. Final review corrections/reruns, physical qualification and production
resource profiles retain the explicit status recorded there; no per-issue test
count or phase closure is inferred.
