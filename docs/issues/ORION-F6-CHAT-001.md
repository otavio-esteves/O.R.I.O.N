# ORION-F6-CHAT-001 — Reliable Portuguese chat admission and evaluation

Local work package, based on `79ae400`. User explicitly requests implementation,
regressions, local validation and evidence; no remote issue/PR, push, merge or
publication is authorized. Branch: `fix/f6-chat-reliability`.

Authority: Architecture V3.2 §§15–18, 58–61, 75–76; Master Plan §12; ADR-0001
(Docker build / HOST Android execution), ADR-0004 (F6 software only). ADR-0002/3
remain unchanged. F7's entry plan is consulted only to preserve its boundaries.

## Package 1 — Admission and input

OBJECTIVE: return explicit local send admission; retain rejected/busy/closed
drafts; share a pure raw-input policy with the authoritative router.

FILES ALLOWED: `core/orchestrator` chat/routing and their tests, `feature/chat`,
chat Android tests, this contract and the directly related validation report.

CONTRACTS / INVARIANTS: 4096 UTF-8 bytes; valid Unicode; no control characters
or line separators; validate before trim/normalization. INV-004/009/011/018–023/035.
Accepted means local admission, never completed inference or durable execution.
Draft/session content remains ephemeral; no mutation retry or content deduplication.

TESTS / ACCEPTANCE: reproduce old loss and missing admission result; specific
errors, correction/resend, no rejected history/processing, concurrent send guard,
closed/busy results, exact byte boundaries, Unicode/control cases and Compose
editor alignment. Run focused JVM and actual Compose tests when available.

OUT OF SCOPE: storage, schema, IPC, policy/confirmation authority and F7.

## Package 2 — Closed query aliases and Portuguese corpus

OBJECTIVE: modest whole-expression aliases for existing task-list/battery queries;
at least 40 versioned model-free routing/authority cases and simulated parser checks.

FILES ALLOWED: router, orchestrator tests/resources, related chat tests and report.

CONTRACTS / INVARIANTS: INV-004/009/018/021–023/031. Validate raw input first;
document only bounded case/space/terminal punctuation normalization. Task arguments
remain literal. External/partial input and identity semantics stay unchanged.

TESTS / ACCEPTANCE: full alias/negative/locale matrix, zero Cortex configuration
and generation for fast paths, existing human confirmation, corpus expected
arguments/clarification/prohibited operations and honest model-dependency metadata.
Mocks do not establish model semantic quality.

OUT OF SCOPE: new write grammar, filters, Skills, models or benchmark platform.

## Package 3 — CI build and model-free Android execution

OBJECTIVE: explicitly build the minified hybrid qualification variant and execute
only the selected no-model chat UI classes on a task-owned HOST emulator.

FILES ALLOWED: `.github/workflows/ci.yml`, directly related Android scripts/tests,
qualification build wiring only if required by a demonstrated dependency, report.

CONTRACTS / INVARIANTS: ADR-0001; INV-001/009/011–014/027–028/032–033/035–036.
Keep isolated qualification identity, R8, mutually exclusive modes and absent
production profiles. No branch protection or remote changes.

TESTS / ACCEPTANCE: exact selected test identities/counts, zero skips/failures,
fail-closed infrastructure/result validation, bounded timeout, sanitized reports,
owned cleanup; runner fixture regressions and canonical builds. Real GGUF stays
opt-in and excluded from mandatory CI. Record actual local execution or its blocker.

OUT OF SCOPE: physical qualification, heavy CI model downloads and new keep/dontwarn
shortcuts. Historical evidence remains unchanged.

## Delivery

Focused local commits keep these responsibilities reviewable. Final integration
checks cover dependencies, lint, normal Debug/Release, hybrid qualification and
available Android execution. Record commands, environments, failures, skips and
pending work in a new report; do not promote readiness or declare remote CI green.
