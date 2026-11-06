# ORION-F3-MEMORY-001 — Owned user-declared memory runtime

GitHub issue: <https://github.com/otavio-esteves/O.R.I.O.N/issues/52>.

OBJECTIVE: Implement the basic deterministic Memory scope of F3 with native
create/list/forget flow through verified MAIN ingress, policy, exact human
confirmation and owned transactional execution. Architecture §§62–74, 102, 128;
Master Plan §§9, 9.1, 9.6–9.7 apply.

FILES ALLOWED: Core memory commands/queries/policy/orchestrator/static metadata;
app/memories admission/wire/action/runner/kernel/gateway and tests; data/memory
owned adapters/query plus narrow DAO accessor/query additions; skills/memory;
feature/memories; minimal Gradle/settings and this work package. MAIN runtime and
navigation wiring are integrated separately by the primary agent.

CONTRACTS INVOLVED: Immutable validated single-statement memory content, bounded
UTF-8/NFC canonical JSON; fixed USER_DECLARED origin and PERSONAL sensitivity;
real user confirmation before userConfirmed becomes true; ActionRequest hash and
policy-version binding; actual RUNNING owner and boot-bound lease; verified
scope-specific encrypted durable ingress; one StartupBarrier; all local memory,
claim deletion, action/audit/event/ingress writes atomically committed. Query is
bounded and read-only with explicit unreadable/key-unavailable content. The UI
cannot select provenance, confidence, confirmation, recovery or authority.

INVARIANTS INVOLVED: INV-001/004/007/008/009/010/011/014/018/019/021/022/023/031/
033/035/036. No inferred memory can be promoted through this user-declared API.
Deletion removes encrypted content and claims through existing F2 storage rules;
no FTS/derived index exists in this slice. Historical technical events remain
minimal references, not content copies.

TESTS REQUIRED: Unicode/byte bounds and canonical vectors; fixed provenance;
policy/context/lifecycle mismatch and unavailable evidence; live original receipt
expiry and tampering; owned execution and idempotency conflict; create/forget
atomic event/action/ingress rollback; read-only bounded queries; unreadable content;
UI create/confirm/list/forget/recreation. Docker checks and Android runtime evidence
are run by the integrating primary agent; no unexecuted test is claimed.

ACCEPTANCE CRITERIA: Users explicitly create and forget personal single-statement
memories without AI; every mutation traverses durable ingress and exact confirmation;
reads do not mutate queues/state; process death cannot commit a partial local effect.
No caller-supplied inferred origin or automatic confirmation is accepted.

Privacy maintenance erases content-bearing memory.create action/ingress ciphertext
only after those copies are terminal (normal cancellation first for matching live
creation drafts), preserving technical key/hash/IDs/audit. RUNNING/UNKNOWN/manual
or unreadable copies block forgetting conservatively and roll back every write.
A permanent MEMORY_FORGOTTEN audit reference prevents reuse of deleted memory IDs;
active memory records plus these references have a 10,000-slot admission bound.
Exact old-key create replay returns FORGOTTEN from retained hash/confirmation/audit
proof without persisting a new content copy; changed payload/keys conflict. The
tombstone check and enqueue share the same writer transaction. Logical erasure
never claims physical/forensic erasure of SQLite/WAL/flash blocks.

OUT OF SCOPE: F7 FTS/retrieval/ranking/intelligent consolidation, inference/import,
claims editing, supersession UI, sensitivity selection, automatic memories,
notifications, scheduler/F4, AI/voice, schema migration, permissions and new process
or exported surface. Fixed PERSONAL content is still encrypted, never raw-indexed,
and all content-bearing diagnostics are redacted. Physical S21 qualification
remains deferred; this package alone declares no phase or 24/7 gate.

INTEGRATION STATUS: MAIN recovery/barrier/admission/UI wiring is integrated and
validated in the [complete F3 candidate](../qualification/f3-integrated-evidence.json).
Canonical Docker CI, 559 Debug JVM cases, 243 app and 4 security Android cases passed.
Memory kernel/query/UI coverage verifies encryption, fixed provenance, erasure and
old/fresh-key replay guards. [Actual SIGKILL](../qualification/f3-integrated-memory-death-api35.json)
inside owned forgetting restores every copy, then explicit replay erases safely
without resurrection. Physical S21 and phase-readiness gates remain open.
