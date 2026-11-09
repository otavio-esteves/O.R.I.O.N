# ORION-F5-RUNTIME-TEST-001 — Explicit emulator Cortex integration

OBJECTIVE: Exercise actual private import, Room registry, MAIN client, Binder,
`:ai`, real llama.cpp streaming and process death with the pinned Qwen test fixture.

FILES ALLOWED: `app/src/androidTest/kotlin/network/orion/app/ai/OrionCortexRuntimeTest.kt`, this issue.

CONTRACTS INVOLVED: F5 import and runtime lifecycle, one terminal per request,
explicit rebind, no replay after process death, fresh resource and restart decisions.

INVARIANTS INVOLVED: INV-001, INV-002, INV-009, INV-011, INV-012, INV-013,
INV-027, INV-028, INV-032, INV-033, INV-036.

TESTS REQUIRED: Actual imported model produces ordered streamed text and succeeds;
service unload is observable; actual same-UID `:ai` SIGKILL after first chunk yields
PROCESS_DIED while MAIN database and task remain intact; replay emits no new output
or bind; a new explicit request rebinds and succeeds.

ACCEPTANCE CRITERIA: All tests run with an explicit private fixture path and
ANDROID_EMULATOR declaration, fresh labelled synthetic governor observations and
restart decisions. A supplied fixture cannot be skipped. Named databases and
isolated model roots are cleaned; no product inference policy is enabled.

OUT OF SCOPE: Physical/performance/API/native-release qualification, production
activation, external action execution, 24-hour stability and phase closure.

Validation is consolidated in the F5 Runtime Gate linked below.

## Consolidated validation

Canonical checks, scoped emulator results and retained failed attempts are recorded
in [F5 Runtime Gate](../qualification/F5-RuntimeGate.md) and its
[evidence record](../qualification/f5-runtime-evidence.json). That report supersedes
implementation-time validation-pending notes without changing this work package's
scope. Final review corrections/reruns, physical qualification and production
resource profiles retain the explicit status recorded there; no per-issue test
count or phase closure is inferred.
