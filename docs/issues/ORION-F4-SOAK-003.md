# ORION-F4-SOAK-003 — Passive maintenance and queue history

OBJECTIVE: Collect the typed MAIN maintenance log protocol without starting,
querying Room, killing or reviving the app. Preserve sampled queue/UNKNOWN totals,
actual heartbeat-origin passes, pruning-batch counts, database sizes and explicit
sequence/transport/cadence gaps for later review.

FILES ALLOWED: scripts/android/record-maintenance-history.py and focused host
contracts/schema fixture; optional integration in the controlled soak recorder;
recovery journal/host contracts for an observed ten-digit timeout and the separate
explicit-absence edge case;
related qualification documentation/evidence.

CONTRACTS INVOLVED: ORION-F4-DIAG-001 log schema v1; ADR-0001; sampled evidence
versus domain authority; boot-scoped monotonic ordering and duplicate tolerance.

INVARIANTS INVOLVED: INV-001/006/011/015/016/029/030/032/035/036.

TESTS REQUIRED: Strict schema/size/type/identity bounds and privacy; duplicate
delivery, sequence gaps/conflicts, process/boot changes and wall rollback;
unavailable logs versus zero counts; interrupted collection and immutable artifact
identity. Docker host contracts; actual short emulator collection on matching APK.

ACCEPTANCE CRITERIA: Host ADB is read-only and UID/tag scoped; only whitelisted
technical records are persisted, never raw logcat/errors or user content. Time is
host monotonic; domain samples retain Android boot/monotonic identity. Missing data
is an explicit gap. Baseline events are distinct from first-seen subsequent events.
Collector completion is review-required, never heartbeat autonomy, uninterrupted
coverage, successful pruning, full-soak approval or phase readiness.

OUT OF SCOPE: Adding runtime IPC/persistence, synthetic domain mutations, forcing
jobs/permissions, device reboot, a physical 24-hour run or closing F4/native gates.
