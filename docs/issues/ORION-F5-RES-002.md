# ORION-F5-RES-002 — Bounded Cortex memory-death restart guard

OBJECTIVE: Prevent successive Cortex memory deaths from permitting an unlimited
cycle of cooldown and relaunch at the same resource tier.

FILES ALLOWED: core/resources source/tests and this work package only.

CONTRACTS INVOLVED: ResourceGovernor, qualified resource plans, AI-only sanitized
exit history, OrionClock/BootSessionId; Architecture §§33–35.1 and Master Plan §11.10.

INVARIANTS INVOLVED: INV-013, INV-027, INV-028, INV-032, INV-033.

TESTS REQUIRED: repeated deaths exhaust qualified fallback plans; duplicated or
shrinking histories cannot reset degradation or renew cooldown; wall changes cannot
shorten cooldown; reboot invalidates the old monotonic fence; absent/stale/unknown
or oversized history fails closed; reconstruction from history restores degradation.

ACCEPTANCE CRITERIA: Each distinct memory exit advances one qualified lower-memory
plan. Cooldown must elapse before eligibility for a new resource evaluation. Memory
evidence disables preload and keep-alive. Exhaustion blocks further attempts. No
decision starts/restarts a process, loads a model or replaces a fresh governor check.

OUT OF SCOPE: Android observation adapter, runtime wiring, model/profile calibration,
process launch, inference, physical/API qualification, persistence/schema changes.

## Contract and recovery

The MAIN adapter supplies **only :ai exits**, with a fresh boot-bound sample and
complete available recent history on every construction/recovery. An unavailable
history is null, never an invented empty list. A history sample is evidence, not
authorization. Distinct evidence is sanitized `ProcessExitSignal` identity; no OS
description or user content is retained. The 64-entry cap bounds both copying and
deduplication. Within an instance, history shrinkage never removes remembered
memory deaths. Reconstruction derives degradation from the supplied history and
restarts the cooldown conservatively; there is no automatic reset/relaunch path.

Fallback plans are caller-supplied qualified configuration, with decreasing model
memory reservation and nonincreasing context/threads/tokens/keep-alive. No real-model
plan, cooldown calibration or hardware threshold is supplied here. A returned plan
is only a candidate for a separate fresh `ResourceGovernor` evaluation. The guard
has no process, Android, database, Skill, logging or external-effect dependencies.

Validation is consolidated in the F5 Runtime Gate linked below. This package alone
does not close the F5 runtime restart-loop or physical qualification gates.

## Consolidated validation

Canonical checks, scoped emulator results and retained failed attempts are recorded
in [F5 Runtime Gate](../qualification/F5-RuntimeGate.md) and its
[evidence record](../qualification/f5-runtime-evidence.json). That report supersedes
implementation-time validation-pending notes without changing this work package's
scope. Final review corrections/reruns, physical qualification and production
resource profiles retain the explicit status recorded there; no per-issue test
count or phase closure is inferred.
