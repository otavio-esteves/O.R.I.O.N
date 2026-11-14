# ORION-F5-METRICS-ADR-001 — Proposed generation-metrics IPC decision

Tracking: [#112](https://github.com/otavio-esteves/O.R.I.O.N/issues/112)
Proposal: [ADR-0003](../adr/ADR-0003-optional-cortex-generation-metrics.md)

OBJECTIVE: Prepare a reviewable Proposed ADR for optional content-free native
generation metrics across the existing Cortex boundary. This work does not accept
the decision, implement IPC or approve a production profile.

FILES ALLOWED: docs/adr/ADR-0003-optional-cortex-generation-metrics.md; this file.
Documentation only; no Kotlin/AIDL/native/build/policy/configuration changes.

CONTRACTS INVOLVED: Architecture §§22/23/32/33/35/99; Master Plan benchmark and
§11.11 F5 gate; current closed AIDL envelopes; accepted ADR-0001 and the exact
issued-history scope of ADR-0002. AGENTS §1.1 requires an ADR before changing
IPC semantics; §1 gives accepted ADRs scoped normative precedence. Existing
authority remains in force while this ADR is Proposed and awaits acceptance.

INVARIANTS INVOLVED: INV-002/009/011/012/013/027/028/032/035/036.

TESTS REQUIRED: Peer source-to-proposal review, exact scoped precedence,
Markdown reference and whitespace checks. No runtime result is implied. Required
CI precedes any eventual merge; initial publication is a draft for decision review.

ACCEPTANCE CRITERIA: Proposed status; exact affected wire/version clauses and
unchanged authority; optional capability/minor negotiation without breaking old
closed envelopes; bounded API, request/endpoint/boot identities, defined counts
and clocks, partial/early-terminal/native/worker completion distinctions; bounded
retention and fail-closed unavailable/death behavior. No content leakage, caller
identity assertions as authentication, admission or retry authority from metrics.
Alternatives and future implementation/evidence obligations remain explicit.

OUT OF SCOPE: ADR acceptance, implementation, approved profiles/configuration,
heavy runner, physical/performance qualification, F5/F6 or soak closure. The
native-local proposal in issue #111 is independent: neither package approves or
mandates the other's ABI, code layout or implementation.

DELIVERY: Two documentation files only. Source baseline
d2b784e67f0645df3257d890be128ad9771ba99d. The ADR proposes an append-only optional
extension instead of altering existing callback/status/capability envelopes.
No actual measurements, accepted decision or runtime/build results are supplied.
