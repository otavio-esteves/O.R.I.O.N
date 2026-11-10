# ORION-F5-MODEL-001 — Add the MAIN-owned model registry

OBJECTIVE: Introduce versioned ModelRegistryEntity metadata and explicit optimistic
state transitions without granting loading authority or changing business records.

FILES ALLOWED: database registry entity/DAO/schema/migrations, application version
and migration compatibility tests, focused new model registry instrumentation tests.

CONTRACTS: Architecture §§20–21; Master Plan §11.6; MAIN owns Room. INV-001,
INV-019, INV-033. A READY record describes verified import, not hardware approval.

TESTS REQUIRED: Non-destructive 5→6 and 1→6 migration; opaque legacy records
preserved; registry initially empty; duplicate immutable identity/path rejection;
version conflicts; only IMPORTED→VALIDATING→READY; failure/quarantine paths.

ACCEPTANCE: schema 6 exported, explicit migration registered, previous schemas
unchanged, no destructive fallback. Registry transitions are guarded by status and
version and READY requires complete format/hash/size validation metadata.

OUT OF SCOPE: Import UI/ingress, actual file validation/import, JNI, model loading,
qualified profiles, physical evidence or F5 gate closure.

## Consolidated validation

Canonical checks, scoped emulator results and retained failed attempts are recorded
in [F5 Runtime Gate](../qualification/F5-RuntimeGate.md) and its
[evidence record](../qualification/f5-runtime-evidence.json). That report supersedes
implementation-time validation-pending notes without changing this work package's
scope. Final review corrections/reruns, physical qualification and production
resource profiles retain the explicit status recorded there; no per-issue test
count or phase closure is inferred.
