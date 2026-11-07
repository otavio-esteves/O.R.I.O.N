# ORION-F4-RUNTIME-001 — Deterministic scheduler and recovery candidate

Tracking: [issue #63](https://github.com/otavio-esteves/O.R.I.O.N/issues/63).

OBJECTIVE: Implement an operational candidate for one-shot and finite daily/weekly
reminder scheduling, exact independently confirmed future occurrences, durable event publication and bounded maintenance/
recovery, preserving the existing authority/process/persistence boundaries. Evaluate
F4 closure against actual evidence; physical qualification remains mandatory.

FILES ALLOWED: scheduler/**; data/database/**; data/reminders/**; data/events/**; data/datastore/**; core/model/**; core/health/**; core/exitinfo/**;
core/orchestrator/reminders/** and its required core:model API dependency; core/queries/reminders/**; core/skills/metadata/reminders/**; app/**; skills/reminders/**; feature/reminders/**;
feature/diagnostics/**; gradle/libs.versions.toml; scripts/android/verify-transaction-process-death.py and its contract tests; new scheduled-reminder runtime recorder and its contract tests; related Android/JVM/host tests;
README, toolchain extension documentation, CI host-harness step and F4 issue/qualification documentation.

CONTRACTS INVOLVED: Architecture V3.2 §§38–49 (civil scheduling, generation,
capabilities, workers, boot and recovery), §95 (notification denial is not delivery),
ActionRequest exact confirmation/ownership, durable IngressCoordinator, event outbox,
Master Plan §§2/10 and ADR-0001.

INVARIANTS INVOLVED: INV-001/004/005/006/007/008/011/014/015/016/018/019/021/022/
023/027/029/030/033/035/036. MAIN owns Room, all mutable ingress is durable/gated,
notification execution has committed ownership and occurs outside Room/barrier,
uncertainty receives no automatic retry, exact alarms have observable fallback.

TESTS REQUIRED: Explicit migration4→5 and preserved old ciphertext/keys/consent;
generation and version CAS; stale/early/duplicate callbacks; denied notification
never FIRED; confirmation bound to future occurrence; runtime gate during recovery;
consumer redelivery/retry/dead letters; unique maintenance; auxiliary bootstrap;
Direct Boot protection; Force Stop signal API guards; cancellation/snooze invalidates
old binding; physical/process-death/system evidence as available and explicitly labeled.

ACCEPTANCE CRITERIA: Canonical Docker focused tests then relevant full check/build/
lint pass; actual Android tests on matching artifacts; source/evidence identity
recorded. Only proven F4 gate items may be checked. Pending S21/system/soak evidence
prevents an MVP/24-7/DETERMINISTIC_AGENT_READY readiness claim.

OUT OF SCOPE: AI/voice, unbounded recurring authority or deferred child confirmations,
changes to process topology or primary persistence strategy, automatic promotion of
legacy local-only reminder consent, physical evidence fabricated from emulator,
policy bypass, forced merges or administrator CI bypass.

RECOVERY / SECURITY / UPGRADE: Scheduling artifacts are rebuildable from Room.
New schema uses explicit non-destructive migration. Old reminders retain no future
external-delivery authority. PendingIntents are explicit/immutable and carry only
IDs/generation; payloads and UI confirmation retain sensitivity rules. Recovery
precedes external delivery, and a notification whose outcome is uncertain remains
UNKNOWN/MANUAL_REVIEW. A reboot invalidates monotonic ownership, never proves no
external effect occurred.
