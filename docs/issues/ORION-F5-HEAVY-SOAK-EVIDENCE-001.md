# ORION-F5-HEAVY-SOAK-EVIDENCE-001 — Offline post-Cortex soak evidence protocol

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/100

Parent: [#89](https://github.com/otavio-esteves/O.R.I.O.N/issues/89); the Core
stability prerequisite remains [#64](https://github.com/otavio-esteves/O.R.I.O.N/issues/64).

OBJECTIVE: Define a bounded technical plan/journal protocol and pure offline HOST
validator for future post-Cortex heavy-soak evidence. Structural validation never
attests execution, real duration, physical autonomy or qualification.

FILES ALLOWED: This work package; docs/qualification/F5-HeavySoakEvidenceProtocol.md;
new scripts/android/f5_heavy_soak_evidence_protocol.py and
scripts/android/test_f5_heavy_soak_evidence_protocol_harness.py only.

CONTRACTS INVOLVED: Architecture V3.2 §§32–35/118–121; Master Plan §§11.9–11.11/31;
ADR-0001; ORION-F5-QUAL-001; existing F4 soak recovery journal/owned cleanup and
maintenance-history observation limitations. This is a new HOST evidence format,
not app persistence, IPC, recovery semantics or workload authority.

INVARIANTS INVOLVED: INV-001/005/006/011/014/019/027/028/032/036. UNKNOWN action
observations never authorize retry; monotonic spans are meaningful only within
their reported boot/session; resource deaths require bounded cooldown/refusal.

TESTS REQUIRED: Pure HOST fixtures for exact fields/types/bounds, duplicate JSON
keys, identity mismatch, ordering, same-boot monotonic regressions, cross-boot
spans, missing observations, interruption/gaps, resource-exit cooldown/refusal,
owned cleanup and unavailable runner/configuration. Claimed duration/status never
promotes qualification. CLI reads files only and emits minimized typed results.
No builds, ADB, devices or heavy work.

ACCEPTANCE CRITERIA: Closed bounded plan/journal formats bind source/app/test/model
identity and owned fixtures; distinguish unavailable, incomplete and structurally
consistent supplied records. Every result remains UNVERIFIED_INPUT / NOT_QUALIFIED
with physical/profile/F5/soak approval false. No supplied timestamp, elapsed field,
status or boolean proves a real 24-hour run. Missing real runner, trusted admission
configuration and physical window remain explicit. Inputs contain no prompts,
outputs, raw errors, raw device serials or arbitrary commands.

OUT OF SCOPE: Heavy executor/device loop; calibration/profile approval; Android,
shared checklist, CI or preflight changes; new IPC/schema/architecture; running a
soak, dispatching or replaying commands, resuming actions, F5/F6 promotion.

RECOVERY/SECURITY/MIGRATION: Offline parsing only. Journal intent precedes reported
owned changes, interruption and cleanup remain visible, and no journal entry is
an instruction to execute or restore anything. No app migration or business-state
mutation. Unknown fields/errors are rejected without echoing input content.

VALIDATION: 31 synthetic HOST contracts passed, including actual offline CLI
invocations, unchanged input files, full-day fabrication remaining unqualified,
plan-content binding, unavailable dependencies, clock/session/boot boundaries,
resource cooldown/refusal and cleanup/interruption cases. Python AST, documentation
fences and whitespace checks passed. No ADB, device, heavy executor, Docker or
Gradle command was run. Canonical integration checks remain the root's responsibility.

Peer review hardening: reject LOADED/INFERENCING with an absent native runtime,
retain UNLOADED with a resident wrapper, and restrict service states to the actual
getStatus surface. Targeted HOST regressions preserve pre-runtime loading/unloading.

CENTRAL VALIDATION: Root executed `docker compose run --rm host-harness-check`
in canonical Docker at source head cbea3bc: 278 cases passed (247 preceding
contracts plus 31 soak cases). Independent peer source review confirmed native
wrapper/state consistency while preserving valid unloaded/transient states.
All records are synthetic offline inputs; no actual soak or physical observation.

Retained log: `/home/otavio/Projects/O.R.I.O.N-evidence/f5-next-gates-20261114/logs/heavy-soak-protocol-final-host.log`.
Log SHA256: `57694df4b031d3623eb553099ea3efbbc93c11f64e01a41e4bdd5c1a3c04669b`.
