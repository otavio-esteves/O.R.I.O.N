# ORION-QUAL-HOST-TIME-001 — Suspend-aware HOST qualification time

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/116

## OBJECTIVE

Make HOST observation duration/cadence include Linux suspend and prevent a late
controlled process death outside the original F4 workload window. Preserve partial
evidence and the existing separately scoped cleanup/restoration.

## FILES ALLOWED

- `scripts/android/record-soak-observation.py`
- `scripts/android/record-maintenance-history.py`
- `scripts/android/verify-f4-soak-runtime.py`
- New `scripts/android/qualification_host_clock.py` and its focused harness
- Their three existing focused HOST harnesses
- This package and `docs/qualification/F4-SoakObservation.md`,
  `docs/qualification/F4-MaintenanceHistory.md`

## CONTRACTS INVOLVED

F4 passive observation/workload/recovery contracts; ADR-0001 HOST boundary;
AGENTS time/boot identity and honest evidence. Linux `CLOCK_BOOTTIME` includes
suspend; missing/invalid/changed-boot time is unavailable, never replaced silently.
Existing JSON fields/results remain; new HOST timing and nanosecond fields are
explicit. Injected clocks are synthetic/unverified, never physical approval.

## INVARIANTS INVOLVED

INV-019, INV-029, INV-030, INV-032, INV-036.

## TESTS REQUIRED

Synthetic suspend during sleep/read; slow final read and endpoint gap; unavailable,
backward, nonfinite and changed-boot clock; exact window boundary; fresh pre-effect
checks after persistence/PID reads; interrupted preparation preserves cleanup;
legacy injected-clock compatibility and no false physical/24-hour claim. Pure HOST
tests locally; canonical Docker HOST harness is centralized by the root agent.

## ACCEPTANCE CRITERIA

Clock/source/session/hashed boot metadata are explicit. Gaps during and between
samples, and gaps to the end check, prevent completion. Endpoint hash work cannot
inflate collected duration. No mutation catches up after its original window.
Clock failures retain incomplete evidence; owned cleanup still runs. All phase and
soak gates stay false. Historical evidence is not relabeled.

## OUT OF SCOPE

Android/IPC/policy/profile changes; hardware or real suspend; running ADB, Gradle
or Docker here; actual 24-hour qualification; automatic resume; heavy workload;
changing F4 recovery journal or heavy HOST journal v1 persistence/clock semantics.

## LOCAL VALIDATION / LIMITS

Test-only regression commit `1026dc8` reproduces one assertion failure (slow final
read incorrectly complete) and one missing-field error (synthetic identity).
After the fix, direct Linux HOST Python runs passed 65 focused contracts: 9 clock,
30 passive observer, 10 maintenance history and 16 workload. Thirteen existing F4
recovery, 32 F5 preflight and 29 F5 baseline HOST contracts also passed (139 total).
AST parsing of all eight changed/new Python files and `git diff --check` passed.
Canonical Docker validation is pending the root agent; no Docker, Gradle, ADB,
real suspend, Android/device work or physical qualification was performed here.

Source review found the F5 preflight imports the observer only for stable transport
and parsers; its complete tracked-file source freeze already covers the new helper.
Other consumers are the maintenance collector, workload subprocesses and focused
harnesses. No heavy journal v1 or F4 recovery journal format was changed. A partial
sample can remain only in memory on clock failure; completed flushed rows remain
retained and the report stays incomplete. Existing abrupt-HOST-death limitations
and explicit owned recovery still apply. Nothing resumes work automatically.

## Canonical validation

Root executed the unchanged Python sources in the pinned Docker HOST harness.
The regression-only `1026dc8` ran 350 contracts with the expected one failure and
one error. The fixed source `aa5c150` ran all **368 contracts successfully**
(Docker exit 0; no device). Evidence is retained outside the repository at
`O.R.I.O.N-evidence/f5-next-gates-20261114/qualification/host-time/validation.json`;
the negative raw log is retained, and the positive summary comes from the recorded
tool stdout rather than a saved raw log. This does not qualify actual suspend,
Android behavior or a 24-hour window. Required PR CI remains a separate merge gate.
