# ORION-F6-ROUTER-001 — First deterministic Intent Router slice

OBJECTIVE: Begin F6 with a bounded, pure Portuguese text interpreter for listing
tasks, querying battery information and preparing task creation. Other final user
requests become cognitive
candidates without invoking Cortex. ADR-0004 authorizes development before the
deferred physical qualification; it grants no runtime/profile approval.

FILES ALLOWED: `core/orchestrator/build.gradle.kts`,
`core/orchestrator/src/main/kotlin/network/orion/core/orchestrator/routing/**`,
related JVM tests; this issue, ADR-0004 and current status additions in README,
F5-QualificationChecklist, F5-RuntimeGate and the sequencing note in
ORION-F5-QUAL-001; the integrated validation record
`docs/qualification/F6-SoftwareEntry-20261118.md`. F5 diagnostic changes remain a
separate responsibility and commit.

CONTRACTS INVOLVED: [Architecture V3.2](../../1-orion.md) sections 15–18/58–61;
[Master Plan V1.2](../../2-plano-mestre.md) sections 12.1–12.4; OrionIntent,
immutable Command/Query separation, GetTasks, GetDeviceInformation, CreateTask,
OrionCreateTaskPreparer, static OrionCreateTaskDescriptor/OrionDeviceDescriptors;
AGENTS sections 11/23.

INVARIANTS INVOLVED: INV-004/009/011/018/021/022/023/031/035. Parsing never grants
authorization, enters persistence, calls Skills, produces ActionRequests, confirms
anything or bypasses the existing policy/ingress/gateway pipeline.

INPUT / OUTPUT: A trusted future adapter supplies an input UUID and correlation
UUID, source classification and finality. These fields are metadata, not proof of
user presence. On creation the stable input UUID is both taskId and the existing
CreateTask idempotency key; retry retains it. A new explicit request requires a new
input UUID. Correlation is preserved separately and never becomes action ownership.
Creation uses the existing normalizer/schema and returns ValidatedCreateTask;
listing returns GetTasks and battery uses the existing GetDeviceInformation query
with the static device.battery intent. No result is dispatched. The task gateway still
owns live user admission, encrypted durable ingress, policy and exact confirmation.

GRAMMAR: Case-insensitive `minhas tarefas` / `listar tarefas` / `ver tarefas`;
`bateria` / `consultar bateria`; `criar tarefa: <title>`
with one literal title after the first colon. Title case/content are preserved,
edge trim/NFC/schema bounds come from OrionCreateTaskPreparer. Missing title asks
for clarification. Unknown final user text becomes a cognitive candidate only.
External-content sources, nonfinal input, controls, malformed Unicode and oversized
input cannot become a command candidate. There is no free-form code execution.

TESTS REQUIRED: Recognized read/create routes, supplied identities/correlation,
normalization and title bounds, changed-payload replay binding, missing argument,
unknown/cognitive candidacy, source/finality rejection, UTF-8 limits and malformed
Unicode, content/ID-redacted diagnostics. Docker focused JVM/lint/module boundary
checks, then canonical build/check. No hardware measurement is claimed.

ACCEPTANCE CRITERIA: Pure deterministic results with bounded parsing and typed
arguments, no state or side effects, no AI/profile activation, no runtime or F6
completion claim. Existing policies and schemas are reused. Focused checks pass;
required remote CI is still a precondition to merge.

OUT OF SCOPE: Completing F6; chat UI and runtime dispatch; LLM structured-output
parsing/repair, tool execution, context/memory retrieval, new models/profiles,
voice, persistence, IPC, structural changes, physical/soak gates. These require
their own work packages under Master Plan sections 12.3–12.7. The normative source
files are `1-orion.md` and `2-plano-mestre.md` at the repository root. An earlier
filename-based search missed them; that search result is not a missing-baseline
finding and must not be used to change the architecture.

PROCESS-DEATH / SECURITY / MIGRATION: Only ephemeral interpretation is added.
Cancellation/retry/action ownership remain with the existing Core. Text and IDs
are redacted from toString; no logging, Room table, migration or new process exists.
