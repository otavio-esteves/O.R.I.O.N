# ORION-F4-INGRESS-001 — Resume bounded domain ingress after recovery

OBJECTIVE: Continue durable domain ingress beyond the initial 25-entry recovery
batch without requiring screen interaction.

FILES ALLOWED: app runtime maintenance wiring, focused maintenance helper and tests.

CONTRACTS INVOLVED: IngressCoordinator, Startup Barrier, existing domain admission
and authorization, WorkManager bounded internal continuation.

INVARIANTS INVOLVED: INV-014, INV-023. Existing confirmation, ownership and external
effect rules remain enforced by the unchanged consumers.

TESTS REQUIRED: More than 25 queued entries progress through maintenance; every
domain receives its bounded turn; the actual barrier prevents consumption before
recovery; blocked consumers do not request a retry loop.

ACCEPTANCE CRITERIA: Maintenance visits task, reminder, timer, memory, notification
and reminder-authorization ingress, requesting continuation when any batch fills.
Existing consumers retain authority to reject or defer requests.

OUT OF SCOPE: New authority, presence rules, automatic external retries, schemas,
physical qualification and unrelated refactoring.

## Validation — 2026-11-08

- CONTAINER: canonical image built; toolchainInfo, app Debug unit tests (76,
  including four maintenance cases), lintDebug (0 errors, 14 warnings), debug app
  and instrumentation APK assembly, module validation and its 36 checks passed.
- CONTAINER: all 125 host-harness contracts passed via Compose.
- ANDROID_EMULATOR: API 37/x86_64, Orion_API_37; all 13 OrionTaskKernelTest cases
  passed, including 80 persisted proposals across database/kernel recreation and
  bounded maintenance after the initial recovery batch. No task was created
  without confirmation. This is not an actual process-death or physical-device run.
- No schema, migration, payload format, confirmation or authority changes.
- Physical S21 and full-duration qualification remain separate and open.
