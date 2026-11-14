# ORION-F5-LIMITER-EVIDENCE-001 — offline limiter correlation protocol

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/98

OBJECTIVE: Specify a sanitized technical receipt and validate internal correlation
of AI exit encoding with subsequent MAIN policy refusal. File input is always
UNVERIFIED_INPUT / NOT_QUALIFIED, never evidence authentication or gate approval.

FILES ALLOWED: This work package; docs/qualification/F5-LimiterEvidenceProtocol.md;
scripts/android/f5_limiter_evidence_protocol.py;
scripts/android/test_f5_limiter_evidence_protocol_harness.py (new files only).

CONTRACTS INVOLVED: Architecture §35.1 dedicated public reason or REASON_OTHER
plus the exact AnonSwap marker; OrionProcessExitClassifier, ProcessExitSignal,
OrionMinimumResourceGovernor and OrionCortexRestartGuard; BootSessionId semantics.

INVARIANTS INVOLVED: INV-011, INV-013, INV-027, INV-028, INV-032, INV-036.

TESTS REQUIRED: Strict bounded JSON, duplicate/nonfinite/type/unknown-field
rejection; identity, PID, boot, time and event-digest correlation; both supported
limiter encodings; ordinary kill/global low-memory/native crash insufficient;
policy transition, cooldown/refusal and unsupported resume/boot cases; CLI output
never promotes supplied environment, provenance, qualification or approval.

ACCEPTANCE CRITERIA: Pure HOST tests pass; malformed input yields fixed sanitized
errors without echoing content; consistent synthetic receipts remain explicitly
unverified. Document absent real producer, controlled stimulus, admission linkage,
trusted artifact/build mapping and physical execution. No new Android behavior.

OUT OF SCOPE: ADB, devices, allocation/stress/injection, native work, Android/IPC
changes, production profiles/budgets, lower-tier approval, CI/checklist changes,
physical qualification, MemoryLimiter verification, F5/soak gate closure.

VALIDATION: HOST Linux/Python, 30 focused unittest cases passed via
`python3 -m unittest discover -s scripts/android -p test_f5_limiter_evidence_protocol_harness.py -v`.
Python AST parse, local Markdown links and `git diff --check` passed. No Docker,
Gradle, ADB, device or stress execution. HOST fixtures are synthetic protocol
examples, not runtime measurements. Real producer/stimulus, trusted paired-window
and admission linkage, reviewed plans and physical execution remain absent.

Peer review found a cross-boot fence comparison in a later policy observation.
The follow-up keeps schema/identity checks but skips fence equality and elapsed
arithmetic once a changed boot is detected, returning UNSUPPORTED_BOOT_CHANGE.
The regression changes both the later epoch's fence and cooldown duration;
same-boot duplicate-fence renewal remains rejected.
