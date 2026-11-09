# ORION-F5-WATCHDOG-001 — Terminate an unresponsive disposable native process

OBJECTIVE: Close Architecture §29's unresponsive-native cancellation gap without
giving MAIN an arbitrary-PID kill API or automatically relaunching Cortex.

FILES ALLOWED: ai/service watchdog/helper tests/service wiring and test-APK probe;
this work package. No client/Core/registry/JNI changes.

CONTRACTS INVOLVED: disposable :ai native ownership, bounded best-effort cancellation,
unload/destruction, OrionClock/BootSessionId, one logical terminal state.

INVARIANTS INVOLVED: INV-002, INV-013, INV-027, INV-028, INV-033.

TESTS REQUIRED: single escalation, duplicate arms preserve earliest deadline,
completion cancels the fence, stale callbacks cannot affect new work, idle/normal
unload never kills, boot-invalid clocks fail closed, destruction while busy retains
the watchdog. A test-APK-only synthetic stuck work probe may establish self-kill and
MAIN survival; it is not actual unresponsive llama or physical qualification.

ACCEPTANCE CRITERIA: cancellations/timeouts/callback death/unload/destruction arm
independent five-second monotonic fences before potentially blocked service/request
locks. Only native worker finally/cleanup completion clears work tickets. Queued
cleanup is already busy. Destruction cannot cancel its own outstanding watchdog.
Escalation calls Process.killProcess(Process.myPid()) only after checking :ai role.

OUT OF SCOPE: automatic rebind/relaunch, MAIN PID management, new IPC methods,
hardware calibration, mutable Skills, actual native fault injection or physical gate.

The watchdog owns bounded atomic ticket/fence state and an independent lazy one-shot
scheduler. There is no recurring polling while unloaded/unarmed. Each armed ticket
gets one future; duplicate cancellation cannot renew it. Completion cancels that
future, including cancellation racing scheduler registration. Closing waits logically
for quiet rather than waiting on the native executor or shutting the watchdog down.
Unknown boot/regression while armed cannot justify indefinite native residency and
escalates only this already-validated disposable process. Five seconds is a control
implementation parameter, not a model/hardware performance qualification.

No raw native pointer, prompt, model content or business state enters this helper.
Process death loses only ephemeral native resources; MAIN owns request terminality
and durable state, and subsequent binding requires a new explicit request and fresh
resource/restart decisions. JVM fixtures use injected clocks/schedulers/kill callbacks.
Validation is consolidated in the F5 Runtime Gate linked below.

The ai/service test APK includes a private :ai probe with synthetic ownership that
never becomes quiet. Its test explicitly unbinds before the grace expires, checks
onDestroy and the self-termination callback marker, observes actual Binder death
and confirms the separate instrumentation MAIN PID survives. It does not inject
an actual llama hang or run inside the product's main application. Native C++
active-runtime ownership already prevents a second native runtime while a prior
stuck runtime survives. An explicit close/new owner during the grace can fail and
requires a fresh request after termination; no automatic retry/relaunch is added.

## Consolidated validation

Canonical checks, scoped emulator results and retained failed attempts are recorded
in [F5 Runtime Gate](../qualification/F5-RuntimeGate.md) and its
[evidence record](../qualification/f5-runtime-evidence.json). That report supersedes
implementation-time validation-pending notes without changing this work package's
scope. Final review corrections/reruns, physical qualification and production
resource profiles retain the explicit status recorded there; no per-issue test
count or phase closure is inferred.
