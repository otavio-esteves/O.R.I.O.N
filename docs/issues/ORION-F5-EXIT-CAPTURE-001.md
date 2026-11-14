# ORION-F5-EXIT-CAPTURE-001 — qualification-only exit observations

OBJECTIVE: Implement bounded actual Android exit-history/live-endpoint observation
without starting AI, heavy work or policy mutation. Emit CAPTURE_ONLY technical
receipts with explicit unavailable model/policy/source fields; never manufacture
the complete limiter-v1 policy correlation or qualification.

FILES ALLOWED: New app/src/exitEvidenceCaptureTarget/** bridge/keep;
app/src/exitEvidenceCaptureAndroidTest/** framework runner; new
scripts/android/f5_exit_capture_protocol.py and
test_f5_exit_capture_protocol_harness.py; docs/qualification/F5-ExitCapture.md;
this work package. Root owns shared Gradle/CI wiring exclusively.

CONTRACTS INVOLVED: Public ApplicationExitInfo reads, current exit classifier,
AndroidOrionClock/BOOT_COUNT, existing IOrionCortex getProtocolInfo/getStatus/
healthCheck. Architecture §35.1; no new IPC, authority or persistence contract.

INVARIANTS INVOLVED: INV-011, INV-013, INV-027, INV-028, INV-032, INV-033, INV-036.

TESTS REQUIRED: Closed bounded HOST schema/type/duplicate/nonfinite checks; history
limit/truncation and dedup, PID/name/boot/time links, absent/live/dead endpoint,
wall limitations and sanitized classification; source contracts enforcing flags0,
no rebind/autostart/heavy calls, truthful environment and unavailable policy.

ACCEPTANCE CRITERIA: New opt-in runner calls one no-auto-create binding and only
existing read methods; timeout/absence/cleanup fail visibly. Real platform fields
are retained without descriptions/traces. HOST tests pass. Root performs all
canonical builds/device checks separately. Every receipt remains NOT_QUALIFIED,
all authority/gate flags false; offline validation stays UNVERIFIED_INPUT.

OUT OF SCOPE: Gradle/CI/shared docs edits, limiter-v1 changes, load/infer/import/
cancel/unload/run, synthetic policy/governor/fence/revision, stress, native model
work, device execution, qualification/profile approval or gate closure.

VALIDATION: HOST Python: 30 focused parser/correlation/source-contract tests passed
via `python3 -m unittest discover -s scripts/android -p test_f5_exit_capture_protocol_harness.py -v`.
Python AST parse, local Markdown links and diff whitespace checks passed. No
Gradle/Docker/device execution. Android compilation/R8 isolation and actual
instrumentation remain for root's centralized validation. The actual Android
producer is implemented, not yet runtime-validated; trusted source/artifact
admission, model/policy/fence observability and real limiter stimulus remain absent.

Peer review follow-up rejects LOADED/INFERENCING rows unless both native runtime
and validation flags are true. The producer applies the same guard; unstable
separate status/health reads fail visibly. HOST regression covers all boolean
pairs in both states and preserves UNLOADED with a resident wrapper.
