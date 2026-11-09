# ORION-F5-RES-003 — MAIN Android resource observation boundary

OBJECTIVE: Supply fresh Android resource evidence without interpreting unavailable
multi-process/native memory as zero or enabling inference.

FILES ALLOWED: app resources source/tests and this issue only.

CONTRACTS INVOLVED: ResourceGovernor observation, Cortex restart guard AI-only exit
history, qualified caller model plan, OrionClock/BootSessionId.

INVARIANTS INVOLVED: INV-001, INV-027, INV-028, INV-032, INV-033.

TESTS REQUIRED: complete native-inclusive process aggregation; missing process or
native evidence; inconsistent inventory; stale/different-boot samples; overflow;
bounded input; fresh MAIN sample inclusion; absent AI is unknown, never zero.

ACCEPTANCE CRITERIA: MAIN-only adapter reads battery, thermal, power saver,
foreground/importance, system memory/pressure and sanitized exits. Local PSS uses
Debug.getMemoryInfo. Cross-process totals require fresh complete caller telemetry;
unknown totals remain null and therefore deny minimum-governor permission. AI-only
exit history is explicit and unavailable/truncated history is observable.

OUT OF SCOPE: runtime wiring, actual IPC telemetry, model qualification, Android
memory-limiter thresholds, inference, persistence/schema, physical/API gate closure.

## API limitation

[ActivityManager.getProcessMemoryInfo](https://developer.android.com/reference/android/app/ActivityManager#getProcessMemoryInfo(int[]))
is rate-limited on Android Q+ and can return cached samples without a timestamp.
It cannot establish fresh cross-process memory. The adapter deliberately requires
an injected complete inventory and timestamped native-inclusive process readings
from the future MAIN/IPC telemetry owner. Missing telemetry yields null AI/aggregate
memory and an explicit limitation. No Java-only heap sum is used. PSS counts native,
managed and proportional shared memory once per process; it is best-effort evidence,
not a hard allocation guarantee or a platform Memory Limiter accounting claim.

No process is launched and no Room, policy, consent, Skill or governor authority is
introduced. Exit descriptions are inspected only by the existing sanitizer and are
never returned or logged. Platform query failures become missing evidence, while
cancellation/interruption propagate. No OS polling loop or listener is installed.

Validation is consolidated in the F5 Runtime Gate linked below. The default absent
telemetry source keeps inference blocked; no hardware capability is claimed.

## Consolidated validation

Canonical checks, scoped emulator results and retained failed attempts are recorded
in [F5 Runtime Gate](../qualification/F5-RuntimeGate.md) and its
[evidence record](../qualification/f5-runtime-evidence.json). That report supersedes
implementation-time validation-pending notes without changing this work package's
scope. Final review corrections/reruns, physical qualification and production
resource profiles retain the explicit status recorded there; no per-issue test
count or phase closure is inferred.
