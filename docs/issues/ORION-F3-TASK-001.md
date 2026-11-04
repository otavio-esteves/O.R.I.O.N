# ORION-F3-TASK-001 — Prepare validated CreateTask contracts and metadata

GitHub issue: <https://github.com/otavio-esteves/O.R.I.O.N/issues/22>.

## OBJECTIVE

Start F3 with a typed request, bounded single-line title validation, immutable
normalized arguments, canonical schema-v1 JSON and static `task.create` metadata.
Preparation supplies input to future policy. It performs no domain mutation and
does not establish authorization, confirmation or successful task creation.

Baseline: Architecture V3.2 §§9, 58–59, 102; Master Implementation Plan V1.2
§§9.1–9.2. Reviewed F2 integration is a prerequisite for merging this work.

## FILES ALLOWED

- `core/commands/src/main/**/commands/tasks/**` and corresponding unit tests.
- `core/skills/metadata/src/main/**/metadata/tasks/**` and corresponding unit tests.
- Strictly necessary Gradle dependencies for those modules; none are added.
- This work package and its validation evidence.

Application runtime, persistence, UI and the existing execution contracts are
outside this change. Implementation takes place on `feat/orion-f3-task-contracts`.

## CONTRACTS INVOLVED

`CreateTask(taskId, title, idempotencyKey, correlationId)` implements
`Command<CreateTaskResult>`. Its IDs are immutable UUIDs. The producer retains the
task ID and idempotency key across retries; correlation can identify a new trace.
They convey no execution ownership. The future Core creates/binds its separate
ActionRequest identity. No client authority, recovery, policy, permission,
capability or confirmation field is accepted.

`CreateTaskResult` declares only `UNAVAILABLE` in this increment. There is no
dispatcher/handler implementation and no `Created` result. The pure
`OrionCreateTaskPreparer.prepare` method returns the separate `CreateTaskPreparation`
type: `Ready(ValidatedCreateTask)` or `Rejected(CreateTaskRejection)`. `Ready` means
validated input for policy, never that an action or task was persisted/executed.

`ValidatedCreateTask` exposes immutable normalized content, original UUIDs,
`argumentSchemaVersion = 1`, `normalizedTarget = task/<taskId>` and
`canonicalNormalizedArguments`. Its constructor is internal and it has no public
copy operation. These restrictions prevent accidental invalid values; the value
is not an authority credential.

### Title admission and normalization

Both the raw input and normalized title are limited to **1024 UTF-8 bytes**.
Before trim, normalization, UTF-8 encoding or canonical-string allocation:

1. Reject raw UTF-16 length above 1024 in O(1), without copying the string.
2. Scan the bounded input without allocations, rejecting unpaired surrogates and
   counting UTF-8 bytes with an early exit at the limit.
3. Reject C0/C1 controls and U+2028/U+2029. Tabs, newlines and NUL are invalid even
   at the edges; trimming does not silently turn multiline text into a title.

Only then apply Kotlin `trim()` to other edge whitespace and normalize to NFC.
Reject an empty trimmed title, and recheck the normalized scalar/byte limit:
NFC can expand U+0344 into two combining marks. Preserve case, compatibility
characters and interior whitespace. Oversized raw input is rejected even if
trimming or normalization could make it smaller. A raw length rejection takes
precedence over inspecting a malformed suffix; subsequent validation exits at the
first invalid scalar, forbidden control or exceeded byte bound encountered.

Typed failures: `BLANK_TITLE`, `TITLE_TOO_LARGE`, `INVALID_UNICODE` and
`INVALID_TITLE_CHARACTER`. Rejections and diagnostic strings never contain the
title, canonical arguments or arbitrary exception text.

### Canonical arguments schema v1

UTF-8 JSON with exactly this order and no optional whitespace:

```json
{"taskId":"00000000-0000-0000-0000-000000000001","title":"Entregar relatório"}
```

UUIDs use their canonical lower-case representation. Title quotes become `\"`
and reverse solidus becomes `\\`; other accepted Unicode, including supplementary
scalars, remains literal. All JSON control characters are rejected by the title
schema before encoding. The task ID is material. Idempotency and correlation IDs
are preserved separately, so trace changes do not change material payloads.
Neither an ActionRequest hash nor a confirmation fingerprint is invented here:
future policy supplies its version and trusted descriptor before binding.

Golden vectors in the unit tests independently specify exact JSON for fixed IDs,
Portuguese text, quotes, reverse solidus and emoji. Decomposed `e` + U+0301 and
precomposed `é` normalize identically; changing the title or task ID changes the
material, while changing the replay/trace metadata does not.

### Static descriptor

`OrionCreateTaskDescriptor.value` declares:

```text
name                   task.create
argumentSchemaVersion  1
authorizationLevel     SAFE_WRITE
sideEffectClass        LOCAL_MUTATION
idempotencyMode         KEYED
recoveryStrategy       MANUAL_REVIEW
requiredCapabilities   {orion_tasks_write}
requiredPermissions    {}
requiresUserPresence   true
confirmationPolicy     POLICY_DEPENDENT
supportsDryRun         false
```

This declaration activates no capability, grants no permission and implements no
Skill. The conservative presence requirement is for the first user-driven flow.
`MANUAL_REVIEW` remains explicit until an operation-specific adapter proves
idempotent creation; the current task store cannot yet claim `RETRY_SAFE` replay.
Preparation is validation, not a declared Skill dry-run facility.

## INVARIANTS INVOLVED

INV-004/009/011/018/021/022/031/035. Validation cannot bypass policy or execution
gates, lower descriptor authorization, fabricate confirmation, expose payloads in
logs or start a mutable side effect. No dependency on Room, UI, AI or voice is added.

## TESTS REQUIRED

- Typed command/operation result separate from preparation, stable UUIDs and schema.
- Empty/Unicode-whitespace titles; pre-normalization/raw admission guard.
- Exact UTF-8 byte limits for ASCII, two-byte, three-byte and supplementary scalars.
- Malformed UTF-16 rejection without replacement encoding; NFC expansion bounds.
- Deterministic NFC/trim equivalence and preserved case/interior/compatibility text.
- Independent exact JSON vectors, quoting, reverse solidus and supplementary text.
- Every C0/C1 control, U+2028/U+2029, and controls at the edges rejected.
- Material target differences and unchanged canonical material for trace/key changes.
- Redacted diagnostics; coherent static descriptor and immutable capability set.
- Existing `UnavailableActionEngine` tests remain part of the integrated check.

Focused canonical Docker checks, to be run by the integrating agent:

```bash
docker compose run --rm android-build :core:commands:testDebugUnitTest :core:skills:metadata:testDebugUnitTest :core:commands:assemble :core:skills:metadata:assemble :core:commands:lint :core:skills:metadata:lint verifyModuleBoundaries testModuleBoundaries --max-workers=2
```

## ACCEPTANCE CRITERIA

Valid input yields bounded, immutable, repeatable schema-v1 arguments. Invalid
input fails deterministically with technical reasons. No dispatch, persistence,
policy decision, confirmation receipt or execution occurs during preparation.
Descriptor values come only from static Core code. No additional library or
Gradle dependency is introduced. Focused checks and required PR CI must pass;
this work package alone does not close F3/DETERMINISTIC_AGENT_READY.

## OUT OF SCOPE / FOLLOW-UPS

Production Policy Engine, activation/revalidation of capabilities, human
confirmation flow, ActionRequest preparation/persistence, TaskSkill execution,
idempotent task creation receipts, mutable ingress/UI, scheduler/recovery F4,
Cortex and voice are separate bounded packages. A policy/context adapter must
obtain trusted boundary evidence; no permissive dispatcher or authorized boolean
is supplied by this contract.

The source changes have no durable state, schema, migration or process-owned
infrastructure. Process death loses only recomputable in-memory preparation.
Physical S21 testing was explicitly deferred by the user; no physical, hardware,
runtime or 24/7 qualification is claimed.

## VALIDATION STATUS

The integrating parent executed the focused canonical Docker checks: **PASS**,
179 tasks in 3m02s (115 executed, 64 FROM-CACHE). All **21 unit cases** passed,
including 16 new cases; zero failures/errors/skips. Both modules assembled debug
and release AARs, lint reported no issues, and module/dependency checks plus all
36 negative-fixture assertions passed. Two independent agents and the primary
agent reviewed the final source without remaining blockers.

Source and artifact hashes are recorded in
[the build summary](../qualification/f3-task-contracts-build-summary.json). The
local validation started from 19efeeb; the branch was rebased onto reviewed F2
integration d785f4f after PR #21 merged with required CI green. All five Kotlin
source/test hashes and relevant dependency inputs stayed unchanged through the
rebase; required CI validates the final integrated PR head. Physical
qualification remains explicitly deferred in [issue #23](https://github.com/otavio-esteves/O.R.I.O.N/issues/23).
