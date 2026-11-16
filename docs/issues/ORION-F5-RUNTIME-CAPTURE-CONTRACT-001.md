# ORION-F5-RUNTIME-CAPTURE-CONTRACT-001 — complete bounded runtime capture contract

Tracking: [#141](https://github.com/otavio-esteves/O.R.I.O.N/issues/141).
Reviewed source: main `aeddbc357b2ac23b3b8aaa98e835eae047aa0661`.
Date: 2026-11-16. This is an implementation contract, not implemented capture or
execution/qualification evidence. Split-byte source #140 is a dependency, not
claimed delivered by this source baseline.

OBJECTIVE: Specify the complete MAIN/HOST RuntimeEvidence v1 producer and bounded
export path, including true sources, missing sources, clocks, identity, all seven
memory phases, loss and uncertainty. Enable software implementation without
inventing a model, profile, template, operating value or physical result.

FILES ALLOWED: This new work package and
docs/qualification/F5-RuntimeCaptureContract.md only. No runtime, wire, policy,
profile, formatter125, RuntimeEvidence v1 or existing runner/recorder changes.

CONTRACTS INVOLVED: Architecture V3.2; Master Plan §§11/31; accepted ADR-0001/0002/
0003 within their exact scope; RuntimeEvidence v1; passive source #132 and
split-byte #140; existing one-shot #127/#128/#130; original Core, ResourceGovernor,
restart, READY registry/file and execution-ownership authority.

INVARIANTS INVOLVED: INV-001/009/011/012/013/014/018/019/027/028/032/033/035/036.

TESTS REQUIRED: Inspect every mandatory v1 field against source and its acquisition
point; distinguish real MAIN event markers, deadline samples, delivery and delayed
polls. Review endpoint/PID/boot/turn fences, all seven memory windows, bounds,
minimization, incomplete/unknown handling and the implementation sequence. Verify
local links, field coverage and documentation-only diff. Future code needs the
specific equivalence, fault, clock, size, schema, source-binding and process-death
controls named in the contract. No Gradle/ADB/CI or runtime test belongs to this
documentary package.

ACCEPTANCE CRITERIA: The linked contract selects an implementable capture design,
maps every mandatory identity/session/candidate/load/inference/chunk/unload/memory
field to its actual source, and lists the new typed sources still to implement.
It does not reconstruct dispatch/terminal/cleanup events from #132 deadline
samples. A dedicated qualified consumer is examined as a delivery seam without
wrapping product callbacks. Finite sampling may miss phases; loss never becomes
zero or a complete record. Bounded inline export respects actual Parcel size and
refuses oversize rather than extending formatter125/runner128/recorder130.
The current factory's plans/timing/template stay empty/null and all real guards
remain authoritative. The design's ADR screen must precede Android wiring; any
new architectural IPC, authority, process/ownership or persistence strategy is
ADR REQUIRED before code, rather than implicitly authorized by this document.

OUT OF SCOPE: Operational values/model/template selection, calibration or physical
allocation; permission/admission bypass; executing inference/stress; implementing
the producer here; changing v1/125/128/130; selecting no-restart instrumentation
or an autonomy transport; F4/F5/soak/F6 closure or production profile approval.

RECOVERY / PRIVACY / UPGRADE: Proposed capture is bounded ephemeral MAIN metadata
and existing HOST-owned evidence files. No business schema, recovery, lease or
retry semantics change. Main death or HOST transport loss is incomplete/unknown,
never permission to resume an inference. No prompt/tool/output content, content
hash, path or raw exception enters the proposed export.

VALIDATION: Source and protocol review only. Local references and documentary
scope are checked before handoff. No software implementation, build, test run,
Android execution, hardware measurement or phase-gate approval is supplied.

Implementation decision and required controls:
[F5-RuntimeCaptureContract.md](../qualification/F5-RuntimeCaptureContract.md).
