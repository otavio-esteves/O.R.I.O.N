# ORION-F5-WIRING-001 — MAIN-owned lazy Cortex runtime composition

OBJECTIVE: Compose model import/registry, fresh Android resource observations,
same-connection native PSS telemetry, cached governor/restart protection and the
bounded Cortex client without enabling an unqualified model or blocking Core startup.

FILES ALLOWED: new app/ai/OrionCortexRuntimeOwner.kt, focused pure policy tests and
this issue. FoundationRuntime integration remains the parent work package.

CONTRACTS INVOLVED: MAIN data ownership, Startup Barrier, Model Registry READY/open
revalidation, ResourceGovernor/restart protection, protocol 1.0, bounded Cortex queue.

INVARIANTS INVOLVED: INV-001, INV-009, INV-012, INV-013, INV-014, INV-023,
INV-027, INV-028, INV-032, INV-033.

TESTS REQUIRED: exact manifest process inventory including application default;
unknown/voice/failed inventory rejection; bounded inspection; immutable READY hash
binding; full-file memory reservation; qualified context/thread/token bounds.

ACCEPTANCE CRITERIA: no profile means typed UNAVAILABLE before DB/recovery/Binder;
core readiness defaults false and gates storage/recovery/bind; qualified timing is
explicit. A selected pending row is rejected for the current attempt even if a
bounded recovery page later validates it. Every actual open rehashes and validates
the inode through the importer, while service rechecks full SHA256 before loading.
No callback starts a second connection or recreates memory-death history per request.

OUT OF SCOPE: qualified production model/profile/calibration, chat/F6 routing,
Skills, voice, physical qualification, automatic inference/rebind and schema changes.

## Runtime behavior

Trusted plans are immutable SHA256 identities; reservations must cover at least
the registered file bytes. Those checks do not make a plan hardware-qualified.
Timing configuration must be supplied explicitly for any nonempty plan set. Default
empty plans expose NO_QUALIFIED_PROFILE and never construct a client or touch storage.

One owner retains its minimum governor and restart guard. Restart callbacks collect
fresh AI-only Android exit history before bind; resource callbacks collect fresh
request-specific observations. Native PSS comes only from currentMemoryTelemetry
on the current authenticated client Binder, whose epoch and PID are already pinned.
No observation callback binds an auxiliary process. Manifest discovery includes all
declared activities/services/receivers/providers, including disabled components and
the application default; only exact MAIN+AI is currently supported. Debug voice
probe components therefore conservatively block a configured owner as well.

Registry recovery runs on IO on demand, one row per invocation, never as a mandatory
Core startup task. A selected IMPORTED/VALIDATING row is separately reconciled through
the importer's exact-row `recoverSelected(modelId)` port, even after the initial scan
completed; this attempt still returns MODEL_NOT_READY and only a fresh call can use
the newly verified READY row. This preserves bounded initial scan progress without
stranding a later interrupted import. Interrupted-copy cleanup is bounded to 25 entries and unregistered
verified-file reconciliation to one file. Independent progress prevents completion
of the registry pass from stranding outstanding temporary files. Cursors retain
progress until each pass completes; blocked unknown files remain preserved and
may be revisited only on another explicit invocation. Full-file hashing is bounded by the
importer's model size limit but may take time; expiry/cancellation is rechecked before
the client dispatches heavy work. Recovery does not infer READY from filenames.

Core readiness is checked before recovery and again before client construction and
fresh callbacks. Closing cancels/closes the owned client without automatic restart.
Replacing trusted profiles requires a new owner, discarding all ephemeral approvals.
No prompt or user payload is logged/persisted by this owner.

Validation is consolidated in the F5 Runtime Gate linked below; no real production profile
or physical/runtime gate is declared qualified by these policy fixtures.

Parent integration exposes a lazy MAIN runtime port after the existing Startup Barrier,
with creation on IO. Its shipped trusted profile set remains empty. Android gate
regressions prove the default and Core-not-ready paths never access a closed DB,
inventory, or Binder. Recovery of a selected late pending import uses the bounded
coordinator API independently of the initial scan cursor.

## Consolidated validation

Canonical checks, scoped emulator results and retained failed attempts are recorded
in [F5 Runtime Gate](../qualification/F5-RuntimeGate.md) and its
[evidence record](../qualification/f5-runtime-evidence.json). That report supersedes
implementation-time validation-pending notes without changing this work package's
scope. Final review corrections/reruns, physical qualification and production
resource profiles retain the explicit status recorded there; no per-issue test
count or phase closure is inferred.
