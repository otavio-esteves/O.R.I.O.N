# ORION-F5-METRICS-ADR-001 — Accepted generation-metrics IPC decision

Tracking: [#112](https://github.com/otavio-esteves/O.R.I.O.N/issues/112)
Decision: [ADR-0003](../adr/ADR-0003-optional-cortex-generation-metrics.md)

OBJECTIVE: Document the scoped optional content-free native generation metrics
contract across the existing Cortex boundary. Initially delivered as Proposed,
ADR-0003 was explicitly accepted by the user in this conversation on 2026-11-14,
with implementation of the optional IPC authorized. This update records that
decision; it implements no IPC and approves no production profile.

FILES ALLOWED: docs/adr/ADR-0003-optional-cortex-generation-metrics.md; this file.
Documentation only; no Kotlin/AIDL/native/build/policy/configuration changes.

CONTRACTS INVOLVED: Architecture §§22/23/32/33/35/99; Master Plan benchmark and
§11.11 F5 gate; current closed AIDL envelopes; accepted ADR-0001 and the exact
issued-history scope of ADR-0002. AGENTS §1.1 requires an ADR before changing
IPC semantics; §1 gives accepted ADRs scoped normative precedence. Existing
authority remains in force outside the accepted ADR's exact supersession scope.

INVARIANTS INVOLVED: INV-002/009/011/012/013/027/028/032/035/036.

TESTS REQUIRED: Peer source-to-contract review, exact scoped precedence,
Markdown reference and whitespace checks. No runtime result is implied. Required
CI precedes merge. Initial publication was a draft; acceptance is recorded without
claiming implementation, build or qualification evidence.

ACCEPTANCE CRITERIA: Accepted status with the explicit user decision recorded;
exact affected wire/version clauses and
unchanged authority; optional capability/minor negotiation without breaking old
closed envelopes; bounded API, request/endpoint/boot identities, defined counts
and clocks, partial/early-terminal/native/worker completion distinctions; bounded
retention and fail-closed unavailable/death behavior. No content leakage, caller
identity assertions as authentication, admission or retry authority from metrics.
Alternatives and future implementation/evidence obligations remain explicit.

OUT OF SCOPE: IPC implementation, approved profiles/configuration,
heavy runner, physical/performance qualification, F5/F6 or soak closure. The
native-local work in issue #111 is independent: neither package approves or
mandates the other's ABI, code layout or implementation.

DELIVERY: Two documentation files only. Source baseline
d2b784e67f0645df3257d890be128ad9771ba99d. The ADR defines an append-only optional
extension instead of altering existing callback/status/capability envelopes.
The user's acceptance on 2026-11-14 is recorded. No implementation, actual
measurements or runtime/build results are supplied by this documentation package.
F4 #64/F5 #89, trusted qualified configuration/calibration and heavy execution
remain pending; this decision does not declare CORTEX_RUNTIME_READY or open F6.
