# ORION-F4-SOAK-002 — Recover interrupted qualification cleanup

Parent: #64. Predecessor: ORION-F4-QUAL-002.

OBJECTIVE: Preserve a host-side recovery journal before every device mutation in
the controlled soak recorder. After transport/host interruption, allow a separate
cleanup attempt against the same device and installed APK pair, without preparing
or confirming any new action. Preserve the original run's outcome and evidence.

FILES ALLOWED: scripts/android/verify-f4-soak-runtime.py; a focused recovery helper
and host contract tests under scripts/android/; explicit cleanup modes and focused
tests in the existing scheduled-reminder and soak-state instrumentation probes;
related qualification documentation.

CONTRACTS INVOLVED: Architecture V3.2 scheduler/recovery and confirmation;
Master Plan §10.11 and §31; ADR-0001; synthetic fixture ownership and evidence identity.

INVARIANTS INVOLVED: INV-001/004/005/006/011/014/015/018/021/022/023/029/030/032/035.
Only test/host tooling changes; no production authority, process, IPC or schema change.

TESTS REQUIRED: Host termination/transport loss with a persisted pre-mutation
journal; rejection of wrong device/APKs, malformed IDs/policies and incomplete
journals before mutation; scoped idempotent cleanup including a partially prepared
fixture; restoration attempts continue after individual failures; original failed
evidence remains unchanged and cleanup cannot promote any qualification gate.
Run host contracts in Docker, relevant Android build/lint and architecture checks.
Any physical execution requires a separately available authorized dedicated target.

ACCEPTANCE CRITERIA: The journal is atomically replaced and flushed before device
mutations, records hashed device and exact artifact identity, original policies and
each intended fixture, and distinguishes an active recorder from an exited one.
Recovery refuses a live recorder and writes to a new evidence directory. It never
installs, clears app data, creates confirmation, resumes qualification or reports a
soak PASS. Only owned synthetic data is removed; missing/failed cleanup remains
observable. All policy restoration outcomes are checked independently.

OUT OF SCOPE: Full 24-hour soak/profile, production diagnostics, AI/voice, native
release qualification, APK replacement, full database export, architecture changes,
F4 closure, readiness promotion, release tagging or merge without required CI.
