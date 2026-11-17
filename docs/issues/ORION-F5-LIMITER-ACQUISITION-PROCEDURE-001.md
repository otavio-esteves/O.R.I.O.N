# ORION-F5-LIMITER-ACQUISITION-PROCEDURE-001

Tracking: [issue #161](https://github.com/otavio-esteves/O.R.I.O.N/issues/161).

OBJECTIVE: Complete the source-bound physical MemoryLimiter acquisition and owned
cleanup procedure, without claiming its execution or selecting operating values.

FILES ALLOWED: This note, the new
[procedure](../qualification/F5-LimiterAcquisitionProcedure.md), and a cross-link in
the existing offline protocol. No runtime or schema change.

CONTRACTS: Architecture §35.1; Master Plan §§11.9–11.11/31; ADR-0001; existing
limiter consistency protocol and original Main/AI ownership, exit and policy
sources. INV-005/006/011/012/018/027/028/029/030/032/033/035/036 apply.

ACCEPTANCE: Exact frozen target/build/QPR/artifacts, actual dedicated reason or
legacy encoding, actual command syntax/unit review, live process-instance and
paired-time correlation, durable intent before one owned AI override, real Core
policy/refusal before native dispatch, explicit gaps and instance-safe cleanup.
Unknown fields, existing overrides and ambiguous units refuse unsafe actions.
No default value, model/profile approval, global limiter change, invented Core
permission or physical/soak/F5/F6 claim.

TESTS/REVIEW: Check official command/symbol references, repository links and the
documentation-only diff. This package executes no ADB, Android, build or soak.
The physical acquisition must be executed and reviewed independently later.

The source review identified a unit mismatch between current Android Developer
and AOSP documentation; the procedure requires evidence from the selected build
instead of choosing either interpretation silently. Dedicated reason support is
recorded from the actual build rather than inferred solely from SDK_INT 37.

RECOVERY/SECURITY/MIGRATION: No new component, process, IPC, business mutation,
logging, durable schema or migration. HOST records are minimized technical
evidence; they grant no execution or retry authority. Interrupted/ambiguous work
is preserved, and cleanup cannot target a replacement instance or shared state.

OUT OF SCOPE: Actual stimulus without an allocated target; calibration, production
activation, architecture waivers, F4/F5 closure and F6.
