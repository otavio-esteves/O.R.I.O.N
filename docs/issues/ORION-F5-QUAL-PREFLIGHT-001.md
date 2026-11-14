# ORION-F5-QUAL-PREFLIGHT-001 — Read-only physical qualification preflight

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/93

OBJECTIVE: Validate selected source/artifact/model provenance and observable target
identity, then emit a blocked physical execution plan. Never run an emulator-only
fixture, approve a profile or mutate a device to manufacture qualification.

FILES ALLOWED: scripts/android/prepare-f5-physical-qualification.py;
scripts/android/test_f5_physical_preflight_harness.py; this work package;
docs/qualification/F5-QualificationChecklist.md.

CONTRACTS INVOLVED: ORION-F5-QUAL-001/#89; Architecture V3.2 §§25–35/118/119;
Master Plan §§11.9–11.11/31; ADR-0001. Memory sampling guidance is prepared in
ORION-F5-MEMORY-QUAL-001; its review remains independent of this preflight.

INVARIANTS INVOLVED: INV-009/011/012/013/014/027/028/032/033/036.

TESTS REQUIRED: Standard-library HOST contracts with fake ADB, including ambiguous,
offline and emulator targets; APK/source/model mismatch; unsafe/split APK paths;
synthetic model classification; unknown/claimed qualification fields; interrupted
observations; immutable output directory; no mutation/instrumentation command.
The root integration repeats this file in canonical host-harness-check.

ACCEPTANCE CRITERIA: Explicit serial and target role are mandatory. The source tree
must match an exact tracked-file manifest and commit; local and installed APK/model
hashes must agree. The report distinguishes verified technical integrity from
physical/profile approval. Every successful preflight still reports BLOCKED with
NOT_IMPLEMENTED physical cases, qualification NOT_EVALUATED and all phase/profile
approval fields false. Invalid inputs report REJECTED without raw device errors or
personal data. The tool never installs, executes a test, changes settings or starts
a workload. Observable physical signals are not presented as tamper-proof attestation.

OUT OF SCOPE: New physical instrumentation or load authority; model downloads;
running a target, benchmark or soak; profile approval; F5/F6/release promotion;
product/runtime, schema, IPC, process or recovery changes.

RECOVERY/SECURITY/MIGRATION: HOST reads only explicitly selected technical identity
and hashes. Fresh output directory and failure reports preserve incomplete checks.
No user content, exit descriptions, traces or raw command errors are recorded.
No app state, permissions, DB or migration is changed.

VALIDATION: 32 standard-library HOST contracts passed without a device, including
an actual CLI invocation with fake ADB/Git executables and an owned source freeze.
The fixtures require no Git package in the pinned Python host-contract container;
the actual HOST recorder still validates the real Git checkout and explicit HEAD.
Python AST, documented local links/CLI flags and whitespace checks passed. The
memory-review link is supplied by companion commit `ada2323`. No Docker, Android
runtime, physical or soak execution was performed by this package; root integration
must run the canonical host-harness-check with this test module included.

Integrated validation: `docker compose run --rm host-harness-check` passed all
159 cases (127 preceding contracts plus 32 preflight contracts) in the canonical
container. Source-review/link checks and exact script/log hashes are recorded in
[f5-physical-preflight-evidence.json](../qualification/f5-physical-preflight-evidence.json).
No device, physical case, production profile or soak was executed or approved.
