# ORION-F5-DEATH-001 — Actual import filesystem/registry death boundaries

OBJECTIVE: Extend the existing host SIGKILL recorder with exact import checkpoints
after verified private-file commit and after IMPORTED registry commit, before READY.

FILES ALLOWED: one model import process-death Android fixture, existing transaction
death recorder and its focused host contracts; this issue.

CONTRACTS INVOLVED: private model publication, registry CAS/recovery, actual
run-as/SIGKILL/new-PID recorder evidence. Neither checkpoint is an open transaction.

INVARIANTS INVOLVED: INV-001, INV-013, INV-019, INV-033.

TESTS REQUIRED: exact scenario/method/proof selection; ordinary-suite cancellation
at both checkpoints; host actual prepare/SIGKILL/verify in a different MAIN process.

ACCEPTANCE CRITERIA: file-only import never auto-adopts READY and its orphan cleans;
committed IMPORTED row survives, remains non-READY, then exact selected validation
recovers without reopening source. Structure/new PID verified; fixture-only cleanup.

OUT OF SCOPE: model inference, calibrated resources, physical qualification, new
generic recorder frameworks, production changes, Force Stop/root fallback.

The tiny valid F32 GGUF is synthetic. Default suite mode uses cancellation at the
same checkpoint and cannot substitute actual recorder SIGKILL evidence. Host mode
fsyncs the positive PID marker and waits at most45seconds for explicit run-as kill.
Validation is consolidated in the F5 Runtime Gate linked below.

## Consolidated validation

Canonical checks, scoped emulator results and retained failed attempts are recorded
in [F5 Runtime Gate](../qualification/F5-RuntimeGate.md) and its
[evidence record](../qualification/f5-runtime-evidence.json). That report supersedes
implementation-time validation-pending notes without changing this work package's
scope. Final review corrections/reruns, physical qualification and production
resource profiles retain the explicit status recorded there; no per-issue test
count or phase closure is inferred.
