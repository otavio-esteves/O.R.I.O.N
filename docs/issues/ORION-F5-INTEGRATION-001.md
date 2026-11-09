# ORION-F5-INTEGRATION-001 — Guarded local inference foundation

OBJECTIVE: Complete the software prerequisites for local inference without claiming
the unavailable S21 or the post-MVP 24-hour stability qualification.

FILES ALLOWED: Four bounded packages: core/resources and MAIN resource adapters;
data/database + data/modelregistry for the declared v5→6 registry migration;
ai/api, ai/cortex and ai/service for the existing Cortex IPC contract; ai/llama for
the pinned native wrapper. Parent integration owns app bootstrap/runtime/build,
module inventory, CI/source preparation and related tests/reports only.

CONTRACTS: Architecture V3.2 §§19–35, multiprocess bootstrap and model import;
Master Plan V1.2 §11; ADR-0001. Existing business authority is unaffected.

INVARIANTS: INV-001/002/004/006/009/011/012/013/014/018/019/023/027/028/032/033/035/036.

TESTS REQUIRED: Fail-closed observations and qualification; boot-bound restart
limits; malformed/truncated GGUF, hash/size/space failures and interrupted import;
schema preservation; fresh resource guards; protocol/schema/size/UID checks;
request deduplication, streaming, cancellation, timeout, late callbacks and process
death; JNI ownership and UTF-8 boundaries; debug/release/R8 native packaging.
Run independent tests in canonical Docker, then applicable emulator integration.

ACCEPTANCE: Production inference has no permissive default or fabricated qualified
model plan. Only MAIN owns registry/Room. AI receives selected DTOs/read-only model
descriptors and never Skills. Every request has one logical terminal outcome;
ambiguous/dead inference cannot create an external action. Partial models never
become READY. All executed checks and remaining physical evidence are explicit.

OUT OF SCOPE: Physical S21 measurements, real-duration soak, enabling unqualified
models, F5 physical exit approval, voice, hybrid tool calling, memory inference,
Sentinel, release/readiness promotion, merge or publication without required CI.

RECOVERY/SECURITY: Persist registry state before depending on private files; recover
imports before opening inference access. Keep callbacks/permits ephemeral, enforce
same-UID unexported IPC, minimize diagnostics and suppress native content logs.
