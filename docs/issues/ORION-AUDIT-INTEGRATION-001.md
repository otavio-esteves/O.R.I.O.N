# Audit integration — 2026-11-10

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/90

OBJECTIVE: Deliver the resource, retention and Cortex audit corrections through
separate scoped reviews and a protected-main integration check, and retain the
exact validation evidence and remaining qualification boundary.

FILES ALLOWED: docs/qualification/**; docs/issues/ORION-F5-QUAL-001.md; this
work package at docs/issues/ORION-AUDIT-INTEGRATION-001.md; reviewed
component merges only. No additional product implementation changes.

CONTRACTS INVOLVED: existing work packages ORION-AUDIT-RESOURCE-002,
ORION-AUDIT-RETENTION-002 and ORION-AUDIT-CORTEX-003; ADR-0001/0002;
Master Plan F5 exit gate and post-Cortex soak; CONTRIBUTING.md required CI.

INVARIANTS INVOLVED: INV-005/006/009/011/012/013/014/019/027/028/032/033/035/036.

TESTS REQUIRED: preserve canonical Docker checks and installed APK hash binding;
verify integrated API35/4KiB installed upgrade and real Cortex lifecycle;
API37/16KiB full application suite with the pinned model; minified native probes.

ACCEPTANCE CRITERIA: all three component checks and the exact integrated head's
required CI pass, validated product sources match main after merge, evidence is
versioned without APK/model/key/image payloads, and physical gaps remain explicit.

OUT OF SCOPE: physical evidence without devices, production model/resource profile
approval, F5 closure, gated F6 implementation and stable/24-7 release claims.
