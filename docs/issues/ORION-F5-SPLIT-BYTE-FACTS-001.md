# ORION-F5-SPLIT-BYTE-FACTS-001 — original validated text/tool bytes

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/140.
Base: aeddbc357b2ac23b3b8aaa98e835eae047aa0661.

OBJECTIVE: Preserve the separate text/tool UTF-8 counts already computed by the
original content guard in the optional passive lifecycle facts, including the
original pending-removal revalidation. Preserve the original sum and behavior.

FILES ALLOWED: ai/api OrionInferenceContract.kt, OrionInferenceLifecycle.kt,
OrionInferenceLifecycleFacts.kt and focused JVM tests; this bounded work package.
Related existing Android cycle-client tests only if necessary. No other worktree,
wire/AIDL/service/native, formatter125/HOST130, owner/factory, profile or schema.

CONTRACTS INVOLVED: Original boundedContentBytes character/UTF-8 checks and
synchronized lifecycle ordering/terminality; passive source132; RuntimeEvidence
v1 textDeltaBytes/toolDeltaBytes source fields. ADR0001/0002/0003 are unchanged.

INVARIANTS INVOLVED: INV-009/011/012/013/019/027/028/032/035/036.

TESTS REQUIRED: Observed/absent result, status, update/List/chunk and Throwable
identity and clock count/order; null/empty/multibyte/mixed text/tool; rejection
before/after validation; buffered admission, pending revalidation, ordered
release, duplicate/conflict/late input; diagnostic faults/loss and immutable
snapshots. Existing lifecycle regressions remain unchanged. Root exclusively
executes Docker/Gradle/ADB/CI; author supplies source tests and static diff checks.

ACCEPTANCE CRITERIA: Exactly the existing text/tool encodings, in their existing
order; no extra encoding/content validation/Clock/RPC/resource evaluation. Absent
observation allocates no diagnostic carrier. Present observation uses one final,
call-local bounded scalar carrier, with isolated allocation/bookkeeping. Metadata
retains the original total and both consistent validated parts, or nullable parts
when unavailable; missing facts are never zero-filled. Publication follows original
update construction. Buffered admission, ordered release and delivery remain
separate, and deadline samples do not become invented arrival/event times.

OUT OF SCOPE: Complete RuntimeEvidence v1/export, precise timing, model/template/
profile selection, hardware/MemoryLimiter/soak execution, F4/F5/F6 closure,
architectural/IPC/admission changes, or new operational parameters.

IMPLEMENTATION: One optional final scalar carrier is allocated only after the
original pre-validation rejects, only when observation is present. The guard
retains its original text encoding, tool encoding and sum, in order, outside any
diagnostic catch. The carrier receives those already-computed counts after the
original result is determined. It is reused for the original pending-removal
revalidation; submitted counts are retained separately as a primitive packed Long.
Metadata decoding never reads content or repeats encoding. Missing/failed parts
remain both null even when the original total is known. A null/empty field has
known zero bytes only after successful original validation.

RECOVERY / PRIVACY / UPGRADE: These remain ephemeral bounded MAIN facts. No content,
request/model identifiers, hashes, raw errors or new clock samples are retained.
Death discards observations without retry/replay or resource-policy changes.
No persistence, schema/migration, process ownership or authority boundary changes.
No ADR is required for this local preservation of already-computed scalar facts.

VALIDATION: Ten focused JVM regressions supplied; original lifecycle regression
suites unchanged. Author executed git diff --check only. Compilation and all
Docker/Gradle/Android/CI validation remain pending root-controlled execution.
No native, performance, hardware, physical/API, soak or gate claim is supplied.

CENTRALIZED VALIDATION (2026-11-16 UTC, source 5fd8124): canonical Docker passed
ai/api JVM 58 (including 10 new cases) and app JVM 216, 36 XML suites with zero
failures/errors/skips; app compileDebugAndroidTestKotlin, module verification/tests
and ai/api/app lintDebug also passed in 11m45. Cache reuse is explicit in the log.
No APK/runtime/physical inference or full RuntimeEvidence v1 is claimed by this
scoped run. Required remote CI remains pending before integration. Root read-only
review found no blocker in the guard/metadata propagation; formatter125, wire and
profile/factory boundaries are unchanged.
