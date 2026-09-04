# AGENTS.md — O.R.I.O.N. Engineering Rules

> **Project:** O.R.I.O.N. — Operational Reasoning & Intelligent Orchestration Network  
> **Scope:** Android native implementation  
> **Engineering baseline:** Architecture V3.2 FINAL CONSOLIDATED + Master Implementation Plan V1.2 FINAL  
> **Primary language:** Kotlin  
> **UI:** Jetpack Compose  
> **Persistence:** Room + SQLite + FTS + DataStore  
> **Local AI:** llama.cpp + GGUF  
> **Principle:** local-first  
> **Initial physical qualification target:** Samsung Galaxy S21 — 8 GB RAM

This file is the operational rulebook for human engineers and coding agents working in this repository.

It exists to turn the closed architecture and the Master Implementation Plan into executable engineering behavior.

It **does not reopen architecture**.

---

## 1. Normative authority and precedence

Normative precedence is scoped, not merely chronological:

1. accepted **ADRs**, but **only** for the exact decisions/clauses they explicitly supersede;
2. **O.R.I.O.N. Android Native Architecture V3.2 FINAL CONSOLIDATED** for all unaffected architectural decisions;
3. **O.R.I.O.N. Master Implementation Plan V1.2 FINAL** for implementation sequencing/gates consistent with the architecture;
4. this `AGENTS.md`;
5. issue/PR-specific instructions.

An ADR is not a blanket waiver of the baseline. Everything it does not explicitly change remains governed by Architecture V3.2 + Master Plan V1.2.

If an issue, prompt, local comment, generated plan, or implementation suggestion conflicts with the applicable higher-level source, **do not implement the conflicting change**.

If a structural change is truly required, stop the structural change and open an ADR first.

### 1.1. What requires an ADR

An ADR is mandatory before changing any of the following:

- process topology;
- process ownership;
- authority boundaries;
- Room/data ownership;
- IPC contract semantics;
- persistence strategy;
- Policy Engine authority;
- Action Outbox semantics;
- recovery model;
- Startup Barrier semantics;
- IngressCoordinator semantics;
- confirmation binding semantics;
- scheduling source-of-truth rules;
- security boundary;
- architectural invariant.

An ADR is **not** required for:

- calibration parameters;
- bug fixes within existing contracts;
- refactoring that preserves architecture;
- test hardening;
- implementation details inside an already-defined boundary;
- a new Skill that obeys the existing Skill contract.

---

## 2. Product identity and naming

Use the official identity everywhere from the first commit:

```text
O.R.I.O.N.
Operational Reasoning & Intelligent Orchestration Network
```

Naming rules:

```text
Product/UI name:       O.R.I.O.N.
Spoken/wake-word name: Orion
Kotlin type prefix:    Orion
Machine prefix:        orion_
```

Examples:

```text
OrionEvent
OrionIntent
OrionSkill
OrionHealth
OrionVoiceInteractionService
orion_heartbeat
orion_recovery
```

Do not introduce legacy product naming into:

- packages;
- classes;
- workers;
- schemas;
- log tags;
- persistent identifiers;
- UI copy;
- service names.

---

## 3. Engineering mission

Build O.R.I.O.N. incrementally in this order of trust:

```text
contracts
→ persistence
→ recovery
→ deterministic functions
→ scheduler
→ Cortex
→ hybrid orchestration
→ intelligent memory
→ Sentinel/proactivity
→ voice
→ System Assistant
→ wake-word qualification
→ adaptive intelligence
```

Do **not** optimize for the most impressive feature first.

Optimize first for:

```text
state correctness
+ deterministic recovery
+ no blind duplicate side effects
+ observable degradation
+ testable invariants
```

The first major engineering success criterion is not “the assistant can chat”.

It is:

```text
kill the process at a critical point
and prove O.R.I.O.N. returns
without corrupting state
or blindly repeating an action.
```

---

## 4. Non-negotiable architectural invariants

All code review, agent work, tests, and refactors must preserve the following.

### INV-001
Only the **main process** opens the primary Room database.

### INV-002
`:ai` never executes Android Skills.

### INV-003
`:voice` never mutates business state directly.

### INV-004
Every relevant mutable action has an `ActionRequest`.

### INV-005
Every uncertain external action can terminate as `UNKNOWN`.

### INV-006
`UNKNOWN` never receives generic automatic retry.

### INV-007
A durable domain event is committed atomically with the state change that created it.

### INV-008
Durable consumers tolerate duplicate delivery.

### INV-009
LLM output never bypasses validation and policy.

### INV-010
`LLM_INFERRED` memory never becomes confirmed automatically.

### INV-011
`SECRET` data never enters logs or raw FTS.

### INV-012
IPC obeys protocol versioning and payload guards.

### INV-013
Cortex process death never takes down the Core.

### INV-014
Recovery runs before pending unsafe external actions resume.

### INV-015
The reminder database is the source of truth; OS scheduling mechanisms are delivery mechanisms.

### INV-016
WorkManager is never used as the source of truth for exact civil-time delivery.

### INV-017
The microphone is never started indiscriminately at boot.

### INV-018
Capabilities are revalidated before sensitive actions.

### INV-019
Critical mutable state uses versioning and/or transactions.

### INV-020
Continuous wake word exists only after its qualification gate passes.

### INV-021
Every confirmation is bound to the exact normalized `ActionRequest` payload.

### INV-022
A confirmed `ActionRequest` cannot materially change without invalidating confirmation.

### INV-023
No mutable ingress executes a side effect while the Startup Barrier blocks the Core.

### INV-024
`:voice` communicates commands to the Core only through versioned IPC.

### INV-025
`OrionCoreGatewayService` is not exported to external apps.

### INV-026
`OrionVoiceInteractionService`, when system-exposed, is protected by `android.permission.BIND_VOICE_INTERACTION` and a minimal surface.

### INV-027
Memory-limiter evidence changes resource policy before the next heavy inference.

### INV-028
Repeated `:ai` memory/resource deaths never create an infinite restart loop.

### INV-029
Force Stop / package-stopped is an explicit boundary of the 24/7 promise.

### INV-030
Background restriction is observable degradation; it is never bypassed.

### INV-031
Every Skill has a coherent `SkillDescriptor` describing authorization, recovery, idempotency, capabilities, and side effects.

### INV-032
Hardware performance qualification never substitutes Android API compatibility qualification.

### INV-033
Auxiliary-process bootstrap never initializes infrastructure owned exclusively by main.

### INV-034
`VoiceInteractionService` remains lightweight; heavyweight System Assistant session work runs in a separate voice-session process.

### INV-035
Generic durable ingress/outbox payloads obey minimization, sensitivity classification, and encryption rules.

### INV-036
A persisted monotonic execution lease is valid only inside the `BootSessionId` that created it.

If a requested change appears to require breaking one of these invariants, **do not code around it**. Create an ADR proposal with evidence.

---

## 5. Work-package contract for every coding task

Before changing code, the engineer/agent must be able to state:

```text
OBJECTIVE
FILES ALLOWED
CONTRACTS INVOLVED
INVARIANTS INVOLVED
TESTS REQUIRED
ACCEPTANCE CRITERIA
OUT OF SCOPE
```

If the issue does not state these explicitly, infer them conservatively from the baseline and keep the change narrow.

Example:

```text
OBJECTIVE:
Implement ActionRequest state-machine validation.

FILES ALLOWED:
core/actions/**
data/actions/**
related tests only

CONTRACTS:
ActionRequest
Action Outbox
RecoveryStrategy

INVARIANTS:
INV-004
INV-005
INV-006
INV-021
INV-022

TESTS:
all valid transitions
all invalid transitions
process-death boundary cases where applicable

ACCEPTANCE:
valid transitions pass
illegal transitions are rejected deterministically

OUT OF SCOPE:
real Skill execution
UI
LLM
voice
```

Do not widen scope merely because adjacent cleanup looks convenient.

---

## 6. Required implementation loop

For every issue:

1. Read the relevant architecture contract.
2. Identify affected invariants.
3. Inspect existing code and tests before editing.
4. Add or update the most specific test that proves the intended behavior.
5. Implement the minimum code required.
6. Run the narrowest applicable tests.
7. Run architecture/dependency checks when boundaries are touched.
8. Run the relevant build/lint/static checks.
9. Review process-death implications for durable state or side effects.
10. Review security/logging implications.
11. Review migration/upgrade implications for durable schema/state.
12. Update diagnostics/health when the feature changes subsystem state.
13. Summarize exactly what changed, what was tested, and any remaining risks.

### 6.1. Small-change rule

Prefer:

```text
one issue
→ one primary responsibility
→ focused tests
→ one reviewable PR
```

Avoid broad changes such as:

```text
"implement recovery"
"refactor architecture"
"clean the core"
```

Break them into independently verifiable work.

### 6.2. Branch and review flow

Normal development follows:

```text
issue
→ dedicated branch
→ implementation
→ relevant tests
→ pull request
→ required CI green
→ merge
```

- An issue is a bounded unit of work and its work-package fields from section 5 are
  the execution contract for humans and coding agents such as Codex.
- Each branch has one primary responsibility. Do not mix unrelated features,
  refactors, documentation, or fixes.
- Keep each pull request small enough for objective review and include the tests
  relevant to its change.
- Passing required CI is a precondition for merge. Direct pushes to `main` are not
  the normal development workflow.
- Architectural changes require explicit justification and an ADR whenever section
  1.1 applies.

Repository contribution templates and the required GitHub `main` protection are
described in `CONTRIBUTING.md`.

---

## 7. Repository and phase discipline

The implementation sequence is authoritative.

### 7.0. Development environment boundary

Follow `docs/adr/ADR-0001-docker-first-host-device.md`:

- Docker is the canonical environment for reproducible build, hardware-independent
  validation, native compilation, packaging, and equivalent future CI tasks.
- The Linux host owns Android Studio, ADB, USB, installation, interactive debugging,
  logcat, profiling, process inspection, and traces.
- A physical Android device is the source of truth for runtime and hardware behavior.
- Do not pass ADB/USB into Docker by default and do not add any runtime dependency on
  Docker to the APK/AAB.
- Evidence must identify its execution environment. Android runtime or hardware
  evidence is definitive only when captured on `PHYSICAL_DEVICE`, unless a normative
  test plan explicitly defines a narrower exception.

### F0 — qualification

Focus on evidence:

- ToolchainProfile;
- BenchmarkProfile;
- CompatibilityProfile;
- LLM/STT measurements;
- API behavior;
- native 16 KiB compatibility;
- memory-limit behavior;
- disposable voice platform spike.

Do not build product architecture inside the F0 spike.

### F1 — foundation

Create:

- module boundaries;
- core contracts;
- `OrionClock` / `BootSessionId`;
- health/readiness skeletons;
- IPC version contract;
- CI/test baseline.

### F2 — transaction core

Prioritize correctness of:

- Room schema;
- optimistic concurrency;
- Domain Event Outbox;
- Action Outbox;
- Ingress durable queue;
- idempotency policy;
- confirmation binding;
- field encryption;
- `DurablePayloadPolicy` for generic persisted envelopes;
- migrations/upgrades;
- retention/pruning.

### F3 — deterministic Skills

Build useful deterministic behavior without AI.

### F4 — scheduler + recovery

Only after this phase may the deterministic Core claim MVP-level resilience.

### F5+

Cortex enters only after the transactional Core, recovery, and minimum ResourceGovernor are functional.

Voice advanced modes enter only after the Core’s IPC/recovery/ingress contracts exist.

Never pull a later phase forward merely to create a demo unless it is an explicitly disposable qualification spike.

---

## 8. Module and dependency rules

Target module shape follows the baseline:

```text
app/

core/
  common/
  model/
  commands/
  queries/
  events/
  ipc/
  orchestrator/
  sentinel/
  context/
  policy/
  actions/
  resources/
  time/
  recovery/
  health/
  security/
  observability/
  ingress/
  exitinfo/
  skills/metadata/

data/
  database/
  datastore/
  repository/
  memory/
  tasks/
  events/
  reminders/
  actions/

ai/
  api/
  service/
  cortex/
  llama/
  prompt/
  contextbudget/
  modelregistry/
  inference/

voice/
  api/
  service/
  wakeword/
  vad/
  stt/
  tts/
  assistant/
  ipc/
  coreclient/

skills/
  api/
  memory/
  tasks/
  reminders/
  device/
  notifications/

scheduler/
  workers/
  alarms/
  reconciliation/
  boot/

feature/
  home/
  chat/
  tasks/
  memories/
  activity/
  diagnostics/
  settings/
```

Dependency rules:

```text
feature
→ core APIs/use cases

skills
→ skill API + permitted repositories

ai service
→ ai API + native wrapper

voice service
→ voice API + stable core IPC DTOs

:ai
↛ data/database

:voice
↛ data/database

core policy
↛ ai implementation

repositories
↛ UI
```

Gradle dependency cycles are prohibited.

Do not fix dependency pressure by making internals public or by introducing a shared “god module”.

---

## 9. Process ownership rules

### 9.0. Multiprocess bootstrap

Assume `Application.onCreate()` and Android/library initializers may execute in any configured app process.

Resolve the process role before initializing process-owned infrastructure:

```text
OrionProcessRole
MAIN
AI
VOICE_CONTROL
VOICE_SESSION
```

Use an explicit `OrionProcessBootstrapper`.

Rules:

```text
MAIN
→ may initialize Room/DataStore/repositories/scheduler/recovery/ingress/Core DI

AI
→ Cortex/JNI/IPC only

VOICE_CONTROL
→ lightweight voice control-plane/IPC only

VOICE_SESSION
→ on-demand audio/session pipeline only
```

Audit automatic initializers and DI graphs. Never allow a shared initializer to open Room, repositories, or Core scheduling infrastructure in `:ai`, `:voice`, or `:voice_session`.

### Main process owns

- Room;
- DataStore;
- repositories;
- command/query/event boundaries;
- Orchestrator;
- Policy Engine;
- Action Engine;
- Skills;
- scheduler/reconciliation;
- recovery;
- ingress;
- health;
- capability registry;
- business state;
- audit authority.

### `:ai` owns only inference runtime

Allowed:

- llama.cpp;
- model loading;
- tokenization;
- prefill/decode;
- streaming;
- cancellation;
- unload;
- inference telemetry.

Forbidden:

- Room;
- repositories;
- contacts/files access;
- alarm scheduling;
- Android Skills;
- authorization decisions;
- business persistence.

`android:process=":ai"` means a separate app process, **not** `isolatedProcess=true`.

Adopting `isolatedProcess=true` requires an ADR and full requalification.

### `:voice` is the lightweight voice control plane

Allowed:

- `OrionVoiceInteractionService` when System Assistant is enabled;
- lightweight KWS/control logic when qualified;
- session coordination;
- `VoiceCoreClient` for control-plane events;
- IPC DTO serialization.

It must remain as lightweight as practical because the selected `VoiceInteractionService` may be kept running by the system.

### `:voice_session` owns heavyweight active-session mechanics

Allowed, on demand:

- `VoiceInteractionSessionService`;
- `VoiceInteractionSession`;
- active audio/session handling;
- VAD/STT;
- TTS/session presentation;
- `VoiceCoreClient`;
- IPC DTO serialization.

Both voice processes are forbidden from:

- Room;
- repositories;
- Policy Engine;
- mutable Skills;
- direct confirmation on behalf of the user;
- treating partial transcript as executable command.

Neither `android:process=":voice"` nor `android:process=":voice_session"` implies `isolatedProcess=true`.

---

## 10. Data ownership and persistence

The main process is the sole data owner.

Required direction:

```text
Room
→ Repository
→ Use Case / Orchestrator
→ selected DTO
→ :ai or :voice
```

Never:

```text
:ai → Room
:voice → Room
:ai → Repository
:voice → Repository
```

### 10.1. Room rules

- Use explicit transactions for logical units.
- No destructive production migration.
- Add migration tests for every schema change.
- Verify post-migration invariants.
- Treat app upgrade as a recovery scenario.
- Do not silently delete a database after migration/corruption failure.

### 10.2. Optimistic concurrency

Critical mutable entities use `version`.

Pattern:

```text
UPDATE ...
WHERE id = ? AND version = ?
```

Zero rows means `CONFLICT`.

Automatic retry is permitted only for local, recomputable operations where retry is semantically safe.

Never turn an external side effect conflict into blind retry.

---

## 11. Command / Event / Query separation

Keep message semantics explicit.

### Command

A request to change state.

Examples:

```text
CreateTask
CompleteTask
CreateReminder
ConfirmAction
CancelAction
```

A command can fail.

### Event

A fact that already occurred.

Examples:

```text
TaskCreated
ReminderFired
ActionSucceeded
```

An event is not a request.

### Query

A read-only request.

Examples:

```text
GetTasks
SearchMemory
GetHealth
```

Queries must not mutate state.

Do not use a generic event bus to erase these semantic boundaries.

---

## 12. Domain Event Outbox rules

Durable event payloads follow `DurablePayloadPolicy`:

- minimize persisted content;
- prefer durable IDs/references over copying user content;
- carry schema/version metadata;
- classify payload sensitivity;
- encrypt SENSITIVE/SECRET payload content with the versioned field-envelope policy when content must be persisted;
- never place raw SECRET data in logs/diagnostics or in an event when a stable reference is sufficient.

When a durable state change and a durable event are one logical operation:

```text
BEGIN TRANSACTION
state mutation
DomainEventOutbox insert
COMMIT
```

Delivery semantics:

```text
AT-LEAST-ONCE
+
IDEMPOTENT CONSUMERS
```

Do not claim distributed exactly-once behavior.

Outbox retry must be bounded and observable.

Support as applicable:

- `attemptCount`;
- `nextAttemptAt`;
- exponential backoff with jitter;
- poison-event detection;
- dead-letter/manual-review state when automatic retry is no longer appropriate.

“At least once” does not mean “retry forever”.

---

## 13. IngressCoordinator and Startup Barrier

Every ingress capable of durable mutation or external side effect must pass through `IngressCoordinator`.

Sources include:

- UI commands;
- AlarmManager;
- BroadcastReceiver;
- notification actions;
- `:voice`;
- permitted app/deep links;
- system callbacks;
- future integrations.

Ingress classes:

```text
READ_ONLY_SAFE
MUTATING_DURABLE
EPHEMERAL
```

While Core readiness is `STARTING` or `RECOVERING`:

- safe reads may proceed only when their minimum state is valid;
- durable mutations are queued/persisted, not externally executed;
- ephemeral ingress is rejected/degraded or kept only in a bounded contract-specific queue.

Durable ingress payloads follow the same minimization/sensitivity policy:

- prefer IDs/references;
- persist only the minimum needed for safe later processing;
- use versioned encryption for SENSITIVE/SECRET content;
- store large temporary payloads in private referenced storage with explicit TTL/cleanup rather than as arbitrary DB/Binder blobs;
- never expose SECRET payloads through logs or diagnostics.

The durable ingress queue does not replace the Action Outbox or Domain Event Outbox.

Their meanings remain distinct:

```text
IngressQueue
= request/fact received but not yet safely processed

ActionOutbox
= authorized mutable effect awaiting execution/reconciliation

DomainEventOutbox
= committed domain fact awaiting publication
```

---

## 14. RecoveryCoordinator rules

Recovery order is normative:

```text
1. minimal logging
2. open DB
3. migrations
4. structural invariants
5. DataStore/config
6. ApplicationExitInfo / previous-exit signals
7. IngressCoordinator = GATED
8. recover Domain Event Outbox
9. recover Action Outbox
10. reconcile reminders
11. reconcile unique workers
12. revalidate capabilities/permissions
13. rebuild ephemeral caches/state
14. initialize ResourceGovernor using current and previous-exit signals
15. calculate OrionHealth
16. publish CORE_READY or CORE_DEGRADED
17. release Startup Barrier
18. IngressCoordinator = OPEN and drain durable ingress
```

Do not move external-effect execution ahead of recovery merely to reduce startup latency.

Cortex and heavy voice initialization are not mandatory Core recovery steps.

---

## 15. ActionRequest and Action Outbox rules

Required states:

```text
PENDING
NEEDS_CONFIRMATION
CONFIRMED
RUNNING
SUCCEEDED
FAILED
CANCELLED
UNKNOWN
MANUAL_REVIEW
```

State transitions must be explicit and validated.

Illegal transitions must fail deterministically.

### 15.1. `UNKNOWN`

`UNKNOWN` means the system cannot determine whether an external side effect happened.

Examples:

- timeout after request transmission;
- process death after external invocation;
- lost callback;
- ambiguous remote response.

When `UNKNOWN`:

- do not retry blindly;
- verify external state when possible;
- otherwise require a safe terminal/manual-review path;
- record audit metadata without sensitive payload leakage.

### 15.2. Execution ownership

A `RUNNING` action must have persistent execution ownership data sufficient for recovery, such as:

```text
executionId
claimedBy
leaseBootSessionId
leaseStartedElapsedRealtime
leaseExpiresElapsedRealtime
leaseWallStartedAt   // audit/diagnostics only
attempt
```

Interpret a monotonic lease only when `leaseBootSessionId == current BootSessionId`.

A reboot invalidates the previous boot's monotonic lease and makes that execution ownership abandoned.

An expired or boot-invalidated lease means the executor may have died.

It does **not** prove the external side effect did not happen.

Recovery must apply the Skill’s declared strategy:

```text
RETRY_SAFE
VERIFY_THEN_RETRY
NO_RETRY
MANUAL_REVIEW
```

---

## 16. Idempotency policy

Every relevant mutation/replay boundary must define `IdempotencyKeyPolicy`.

For each operation, define:

- who creates the key;
- key scope;
- retention lifetime;
- canonical payload bound to the key;
- UNIQUE constraint where applicable;
- replay behavior;
- conflict behavior.

Required semantics:

```text
same idempotencyKey
+ same canonical payload
→ DEDUP / replay-safe result
```

```text
same idempotencyKey
+ different canonical payload
→ IDEMPOTENCY_CONFLICT
```

Never silently replace the first operation with the second.

For external side effects, retain the key for as long as duplicate execution remains a meaningful risk.

---

## 17. Confirmation binding

Confirmation is not represented only by `confirmedAt`.

Before asking for confirmation:

1. normalize Skill;
2. normalize arguments;
3. normalize target;
4. determine authorization level;
5. build canonical representation;
6. calculate `normalizedPayloadHash`;
7. calculate `confirmationFingerprint`;
8. persist the relevant policy version.

Conceptually:

```text
confirmationFingerprint = SHA-256(
  skill
  + canonicalNormalizedArguments
  + normalizedTarget
  + authorizationLevel
  + actionId
  + policyVersion
)
```

After confirmation, material action fields are immutable.

If any material field changes:

- invalidate confirmation;
- preferably create a new `ActionRequest` / `actionId`;
- request confirmation again when policy requires it.

Immediately before `CONFIRMED → RUNNING`:

- recompute hash/fingerprint;
- compare with confirmed values;
- check expiration;
- revalidate capability;
- revalidate permission;
- revalidate policy.

Possible blocking outcomes include:

```text
CONFIRMATION_STALE
CONFIRMATION_MISMATCH
CONFIRMATION_EXPIRED
POLICY_CHANGED_RECONFIRM_REQUIRED
```

Never “repair” a confirmed payload silently.

---

## 18. Skill rules

No Skill may exist without a static/declarative `SkillDescriptor`.

Required shape:

```kotlin
data class SkillDescriptor(
    val name: String,
    val authorizationLevel: AuthorizationLevel,
    val recoveryStrategy: RecoveryStrategy,
    val sideEffectClass: SideEffectClass,
    val idempotencyMode: IdempotencyMode,
    val requiredCapabilities: Set<Capability>,
    val requiredPermissions: Set<String>,
    val requiresUserPresence: Boolean,
    val confirmationPolicy: ConfirmationPolicy,
    val supportsDryRun: Boolean,
    val argumentSchemaVersion: Int,
)
```

Minimum side-effect classes:

```text
READ_ONLY
LOCAL_MUTATION
EXTERNAL_REVERSIBLE
EXTERNAL_IRREVERSIBLE
DESTRUCTIVE
```

Minimum idempotency modes:

```text
NATURAL
KEYED
VERIFY_THEN_RETRY
NONE
```

Rules:

- the LLM cannot select or lower authorization;
- recovery strategy is not decided by the LLM;
- capabilities are revalidated immediately before execution;
- permissions do not replace functional capability checks;
- dry-run support never grants authority for real execution;
- destructive actions never lose mandatory confirmation through ordinary preference.

---

## 19. Time semantics

No domain logic may directly depend on raw calls to:

```text
System.currentTimeMillis()
SystemClock.elapsedRealtime()
```

Use injected time abstractions:

```text
OrionClock
WallClock
MonotonicClock
BootSessionId
```

### Wall clock

Use for:

- civil dates;
- reminders;
- `confirmationExpiresAt`;
- persisted retry schedules;
- audit timestamps.

### Monotonic clock

Use for:

- durations in the same boot;
- timeouts;
- latency/performance;
- ephemeral leases where appropriate.

### BootSessionId

Prevents interpreting a monotonic timestamp from a previous boot as valid.

Test whenever relevant:

- clock rollback;
- clock forward;
- timezone change;
- DST gap;
- DST overlap;
- reboot;
- lease expiration;
- confirmation expiration;
- retry scheduling.

Never use civil time to measure in-process timeouts.

Never persist only monotonic deadlines for work that must survive reboot.

---

## 20. Reminder and scheduler rules

Reminder state belongs to the database.

AlarmManager / WorkManager are schedulers, not the authority.

### Exact alarms

Use an `ExactAlarmCapability` abstraction.

Before scheduling:

- check capability;
- explain special access when needed;
- choose exact or approximate strategy;
- persist the chosen precision/method.

If exact delivery is unavailable:

```text
precision = APPROXIMATE
```

and use a documented fallback.

Never fail silently.

### WorkManager

Use for persistent, deferrable maintenance such as:

- heartbeat;
- cleanup;
- reconciliation;
- safe internal retry;
- non-urgent summaries;
- telemetry compaction.

Do not use WorkManager as an exact-time alarm mechanism.

### Reconciliation

Run reminder reconciliation after relevant events including:

- boot/user unlock when storage permits;
- time/timezone changes;
- app update;
- exact-alarm capability changes;
- startup/recovery;
- return after a previously stopped package can execute again.

---

## 21. Force Stop and background restriction

O.R.I.O.N. does not bypass Android lifecycle restrictions.

### Force Stop / package stopped

While stopped:

- no automatic self-revival is promised;
- do not attempt undocumented workarounds;
- cancelled PendingIntents are treated as lost OS scheduling artifacts;
- recovery starts only after Android permits the package to run again.

On return:

- on API 35+, use `ApplicationStartInfo.wasForceStopped()` as the primary signal that this is the first launch after Force Stop;
- do **not** use `ApplicationExitInfo.REASON_USER_REQUESTED` or another exit reason alone as proof of Force Stop;
- run RecoveryCoordinator;
- reconstruct/reconcile PendingIntents, reminders, workers/jobs, and callbacks from persistent sources of truth;
- revalidate capabilities;
- update health/diagnostics.

On Android 15/API 35+, entering the stopped state cancels the app's PendingIntents, so post-stop reconciliation is mandatory.

### Background restriction

If Android/OEM restricts the app:

- jobs/alarms/FGS may be delayed or blocked;
- reflect degradation in `OrionHealth`;
- expose the condition in diagnostics;
- do not circumvent the restriction.

---

## 22. Cortex / local AI rules

The language model is an inference engine, not the agent’s authority.

### 22.1. Cortex process

Run Cortex in `:ai`.

It must be disposable.

A normal resting state is unloaded.

The Core must remain useful without Cortex.

### 22.2. ResourceGovernor first

No inference starts without `ResourceGovernor` approval.

Minimum decision inputs include:

- battery;
- thermal state;
- process importance;
- model profile;
- context size;
- threads;
- recent memory pressure;
- recent process exits;
- API memory-limit signals when available;
- foreground/background state;
- per-process memory budget;
- aggregate app memory budget.

`:ai` being under its own budget is insufficient if the total application headroom is unsafe.

### 22.3. Memory limiter handling

Use real `ApplicationExitInfo` behavior observed for the target API/build.

Support the platform variants defined by the baseline, including a dedicated reason when exposed and compatible fallback classification when a build reports MemoryLimiter evidence under another reason/description.

After memory-limit evidence for `:ai`:

- mark Cortex degraded until reevaluation;
- reduce model tier/context/threads/keep-alive as appropriate;
- disable preload;
- avoid immediate repeated relaunch;
- require a fresh ResourceGovernor decision.

### 22.4. IPC

IPC starts at:

```text
protocolMajor = 1
protocolMinor = 0
```

Major mismatch:

```text
reject communication
```

Minor mismatch:

```text
negotiate common capabilities
```

Do not crash the entire app because an optional subsystem is incompatible.

### 22.5. Binder payloads

Keep inline Binder payloads small and predictable.

Internal limit:

```text
MAX_INLINE_IPC_PAYLOAD = 256 KiB
```

Larger payloads use an approved large-payload path such as `ParcelFileDescriptor` or validated `SharedMemory`.

Never send arbitrary large models/audio/history blobs inline.

### 22.6. Streaming and terminality

Each inference uses `requestId` and monotonic `sequence`.

Required behavior:

- deduplicate repeated chunks;
- handle bounded out-of-order delivery according to contract;
- ignore late callbacks after terminal state;
- reach one logical terminal state per request.

Terminal states:

```text
SUCCEEDED
CANCELLED
TIMED_OUT
FAILED
PROCESS_DIED
```

Cancellation is best-effort and idempotent.

Do not reconstruct an external action from partial LLM text after `:ai` death.

---

## 23. Tool-calling and LLM safety

LLM output never directly executes code, shell, or Android actions.

Required pipeline:

```text
LLM structured output
→ parse
→ schema validation
→ normalization
→ semantic validation
→ SkillDescriptor lookup
→ Policy Engine
→ capability check
→ permission check
→ confirmation policy
→ ActionRequest
```

If structured output is invalid:

- at most one controlled repair attempt for a non-sensitive case;
- otherwise fail safely or ask for clarification.

Never execute free-form LLM text.

### 23.1. Prompt injection boundary

Treat content from all external/untrusted sources as data, including:

- files;
- notifications;
- messages;
- sites;
- OCR;
- contacts;
- calendars;
- imported memories;
- copied text.

Untrusted data cannot:

- grant permission;
- change policy;
- raise authorization;
- invoke a Skill directly;
- modify system rules;
- authorize secret logging.

---

## 24. Memory rules

Memory requires provenance, confidence, and sensitivity.

Origins include:

```text
USER_DECLARED
SYSTEM_OBSERVED
SYSTEM_DERIVED
LLM_INFERRED
IMPORTED
EXTERNAL_SOURCE
```

Sensitivity levels:

```text
PUBLIC
PERSONAL
SENSITIVE
SECRET
```

Rules:

- `LLM_INFERRED` remains inferred/unconfirmed by default;
- sensitive or harmful claims are not promoted from inference alone;
- conflicting information uses supersession, preserving history where required;
- multi-fact memories use `MemoryClaimEntity` when independent claim tracking matters;
- `SECRET` is never raw-indexed in FTS;
- `SENSITIVE` is not raw-indexed in FTS by default;
- memory deletion must remove derived indexes and preserve only minimal non-sensitive audit where necessary.

Memory controls must remain user-visible and manageable.

---

## 25. Sensitive-data storage rules

Baseline protection:

```text
Android sandbox
+ system file-based encryption
+ application-level encryption for SENSITIVE/SECRET fields
```

Use AES-GCM with key protection/wrapping through Android Keystore.

Encrypted fields use a versioned envelope containing the required metadata, including:

- ciphertext version;
- key version;
- algorithm;
- nonce/IV;
- ciphertext;
- authentication tag when applicable;
- AAD schema/version.

Rules:

- never reuse nonce/IV under the same key;
- bind AAD to appropriate logical context;
- authentication failure means corrupt/unreadable, not plaintext fallback;
- support key versioning and safe rotation;
- handle invalidated/unavailable Keystore keys safely;
- never silently downgrade encrypted data to plaintext.

Automatic cloud backup is off by default for protected O.R.I.O.N. data.

---

## 26. Voice engineering rules

Voice progresses in gates:

```text
1. Push-to-talk
2. Foreground voice mode
3. System Assistant / ROLE_ASSISTANT
4. Qualified wake word
```

The product must remain valid without System Assistant role or continuous wake word.

### 26.1. Voice process split and VoiceCore IPC

Use:

```text
:voice
= lightweight VOICE_CONTROL

:voice_session
= heavyweight on-demand VOICE_SESSION
```

Both may use the stable `VoiceCoreClient` contract as appropriate:

```text
VoiceCoreClient
→ OrionCoreGatewayService
→ IngressCoordinator
```

`OrionCoreGatewayService`:

- `android:exported="false"`;
- explicit internal bind;
- no external business authority;
- no repository exposure.

### 26.2. Partial transcript rule

```text
isFinalTranscript = false
```

can never create a mutable `ActionRequest`.

Only a final transcript or explicitly-defined executable event can become a command.

### 26.3. Core dead / voice alive

When Android permits the main process to be recreated:

```text
:voice or :voice_session
→ bind Core gateway
→ main starts
→ recovery runs
→ ingress waits behind barrier
→ safe execution after readiness
```

If Core cannot be started, degrade the voice session safely.

Never fabricate confirmation because voice callbacks died.

### 26.4. System Assistant exposure

When implemented:

```text
OrionVoiceInteractionService
process=":voice"
android:exported="true"
android:permission="android.permission.BIND_VOICE_INTERACTION"
```

Use only the framework-required intent filter/metadata and keep this service lightweight.

Heavy session work belongs to:

```text
OrionVoiceInteractionSessionService
process=":voice_session"

OrionVoiceInteractionSession
```

All other internal components remain unexported by default unless an official Android contract requires otherwise.

### 26.5. Microphone rules

Do not:

- start the microphone at boot;
- start microphone FGS arbitrarily from background;
- rely on undocumented OEM behavior;
- use continuous heavy STT as wake-word detection.

Wake-word pipeline, if qualified, uses lightweight KWS first.

---

## 27. Android component security

Default:

```text
android:exported="false"
```

Export only when an official Android contract requires discovery/binding.

For exported components:

- use system permission protection when available;
- minimize intent filters;
- validate action/extras;
- treat external payload as untrusted;
- never expose repositories/internal Binder APIs;
- never accept business authority from caller-supplied fields.

Internal intents should be explicit.

PendingIntent policy:

- `FLAG_IMMUTABLE` by default;
- deterministic request codes;
- explicit Intent;
- minimal extras;
- mutable PendingIntent only when an API truly requires it, with documented justification.

---

## 28. Native/JNI rules

Native code uses:

```text
NDK
+ CMake
+ minimal JNI
```

Rules:

- explicit native ownership;
- RAII in C++;
- Kotlin never manipulates raw native pointers outside the wrapper;
- JNI callbacks never retain Activity references;
- thread attach/detach is controlled;
- native exceptions do not cross JNI un-translated;
- native failure must not contaminate the main process.

If native runtime enters an undefined state, killing/recreating `:ai` is preferable to corrupting main.

Every native change must consider:

- 16 KiB page-size compatibility;
- packaging/alignment;
- release R8/JNI keep rules;
- native symbols and symbolication;
- ABI compatibility.

Do not mark a native integration complete only because debug builds load successfully.

---

## 29. Observability and diagnostics

Logs are structured and privacy-minimized.

At minimum use:

```text
correlationId
component
event
severity
timestamp
```

Never log `SECRET`.

Avoid sensitive content by default.

### 29.1. Health

Subsystem health should use explicit states such as:

```text
HEALTHY
DEGRADED
UNAVAILABLE
RECOVERING
FAILED
```

Background restriction, memory pressure, lost capabilities, and protocol mismatch must be observable as degraded/unavailable subsystem state instead of becoming unexplained global failure.

### 29.2. Diagnostics screen

Keep diagnostics sufficient to explain, when implemented:

- CoreReadiness;
- OrionHealth;
- last recovery;
- DB status/schema;
- Action/Event/Ingress backlog;
- UNKNOWN actions;
- reminders/workers;
- exact-alarm capability;
- notification capability;
- background restriction;
- assistant role;
- voice/Cortex/IPC state;
- model/hash;
- memory/resource profile;
- last process exit;
- memory-limiter evidence;
- compatibility-profile status.

Diagnostics must not expose secret payloads.

---

## 30. Retention and bounded-growth rule

A 24/7 agent must not rely on unbounded local growth.

Define and test retention/pruning for:

- audit data;
- technical logs;
- processed Domain Event Outbox rows;
- terminal ActionRequests;
- terminal Ingress envelopes;
- local telemetry;
- temporary artifacts;
- model-import temporary files.

Retention policy must specify, as applicable:

- TTL;
- count/size limits;
- incremental pruning;
- preservation for manual review/audit;
- compaction.

No infinite logs, infinite retry tables, or orphan temporary files.

---

## 31. Testing policy

Tests are architecture, not polish.

### 31.1. Unit tests

Cover at minimum when the relevant subsystem exists:

- Intent Router;
- command handlers;
- Policy Engine;
- authorization;
- proactivity score;
- memory ranking/provenance/claims;
- Context Budget;
- time calculations/DST;
- reminder strategy;
- ResourceGovernor;
- idempotency;
- Action transitions;
- UNKNOWN recovery;
- IPC dedup/sequence/terminality;
- health aggregation;
- confirmation fingerprint/expiry/immutability;
- SkillDescriptor resolution;
- ingress gating/dedup;
- ApplicationExitInfo/memory-limiter classification;
- VoiceCore terminality.

### 31.2. Database tests

Cover:

- migrations;
- transactions;
- optimistic concurrency;
- Action/Event/Ingress recovery;
- memory supersession;
- FTS consistency;
- encrypted field behavior;
- corruption strategy;
- backup-disabled verification.

### 31.3. Process-death tests

For every durable state or side effect, ask:

```text
what if the process dies exactly here?
```

Test boundaries such as:

- before commit;
- after commit;
- before side effect;
- after side effect;
- before confirmation persistence;
- after confirmation;
- during callback;
- during IPC;
- during reconciliation;
- during `:voice` control-plane death;
- during `:voice_session` death;
- across reboot with a persisted execution lease.

Acceptable outcomes are only:

```text
safe recovery
idempotent retry
VERIFY_THEN_RETRY
UNKNOWN
MANUAL_REVIEW
```

Never accept:

```text
"probably executed"
```

### 31.4. System tests

As applicable, test:

- reboot;
- locked boot;
- Doze;
- battery saver;
- background restriction;
- notification denial;
- microphone revocation;
- exact-alarm loss/gain;
- timezone/time changes;
- app update;
- low storage;
- memory pressure;
- assistant-role gain/loss;
- Force Stop and allowed return, including `ApplicationStartInfo.wasForceStopped()` on API 35+;
- API 37 memory-limit behavior;
- API-specific voice/background-audio behavior.

### 31.5. Security tests

Fail safely when:

- LLM requests unauthorized Skill;
- prompt injection tries to change policy;
- invalid arguments arrive;
- destructive action lacks confirmation;
- external data tries to grant authority;
- invalid Binder caller attacks an internal service;
- PendingIntent spoofing is attempted;
- duplicate retry occurs;
- inferred memory tries to auto-confirm;
- secret data attempts to enter logs;
- GGUF is malformed;
- capability disappears during a flow;
- confirmed payload is altered.

### 31.6. Soak tests

Milestone guidance:

```text
after MVP Core:         24h
after Cortex:           24h + memory stress
after Sentinel/Voice:   72h
before stable release:  several days
```

Monitor:

- crash/ANR;
- battery;
- RAM/thermal;
- DB/log growth;
- Action/Event/Ingress backlog;
- UNKNOWN accumulation;
- idempotency conflicts;
- reminder loss;
- restart/recovery loops.

---

## 32. Build and verification behavior

Use the repository’s Gradle wrapper and inspect available tasks instead of inventing task names.

For a normal code change, run the narrowest applicable checks first.

Before PR/milestone closure, ensure the repository-equivalent of:

```text
assemble
unit tests
lint/static checks
```

For release-sensitive/native/IPC changes, also verify the relevant release build and instrumentation/system suites.

If conventional Android Gradle tasks exist, typical commands may include:

```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew assembleRelease
```

But do not assume these are sufficient or available without checking the repository.

When a check fails:

- fix the root cause if in scope;
- do not disable the check to make CI green unless the baseline explicitly permits it;
- do not weaken a test that is correctly exposing an invariant violation.

---

## 33. Definition of Done

An issue is not DONE unless all applicable items are true:

```text
[ ] implementation complete
[ ] unit tests added/updated
[ ] instrumentation/system tests added when applicable
[ ] error handling implemented
[ ] logging is structured and safe
[ ] Health/Diagnostics updated when relevant
[ ] no forbidden dependency introduced
[ ] no architectural invariant broken
[ ] documentation/comments updated where contract clarity requires it
[ ] migration added when schema changed
[ ] process-death behavior considered for durable state/effects
[ ] time semantics use injected clocks where timeout/lease/expiry/scheduling exists
[ ] idempotency policy defined for mutation/replay/external side effects
[ ] upgrade behavior considered when durable state/schema changed
[ ] retention/pruning considered for continuous-growth data
[ ] native compatibility considered for NDK/JNI changes
[ ] IPC/Intent security boundary reviewed when touched
[ ] multiprocess bootstrap reviewed when process/component initialization changed
[ ] durable generic payload minimization/sensitivity/encryption reviewed when persisted envelopes changed
[ ] monotonic persisted lease is BootSessionId-bound when applicable
[ ] relevant tests/build/lint/static checks green
```

Do not mark a task done because a happy-path demo works.

---

## 34. PR / agent completion report

Every completed coding task should report:

```text
SUMMARY
- what changed

CONTRACTS / INVARIANTS
- which contracts were implemented or preserved
- INV-xxx list

TESTS
- exact checks executed
- result

PROCESS-DEATH / RECOVERY IMPACT
- what happens if a process dies in the modified flow

SECURITY / PRIVACY IMPACT
- exported components, IPC, permissions, logs, sensitive data

MIGRATION / UPGRADE IMPACT
- schema/state compatibility, if any

OUT OF SCOPE
- intentionally untouched adjacent work

RISKS / FOLLOW-UPS
- only real remaining risks, not speculative redesigns
```

Do not claim a test was run if it was not run.

Do not claim architecture compliance if a known invariant is still failing.

---

## 35. Prohibited engineering shortcuts

Do not use any of the following as the primary implementation path:

- arbitrary shell execution;
- LLM-generated commands executed directly;
- Room access from `:ai` or `:voice`;
- microphone startup at boot;
- infinite wake locks;
- bypassing Doze/background restrictions;
- bypassing Force Stop/package-stopped;
- plaintext secrets;
- raw audio persistence by default;
- blind retry after ambiguous external timeout;
- treating LLM inference as confirmed fact;
- unbounded logs;
- immortal-process assumptions;
- large arbitrary Binder blobs;
- unnecessary exported components;
- silent destructive migration;
- interpreting stale `RUNNING` as “not executed” after crash;
- using `AgentState` as persistent truth;
- silently mutating a confirmed payload;
- mutable ingress bypassing Startup Barrier;
- `:voice` directly executing business state;
- repeated automatic `:ai` relaunch after memory-limit kill;
- direct domain use of wall/monotonic system clocks instead of the time abstraction;
- treating debug-native success as release/native compatibility proof.

---

## 36. Current starting point

Unless the repository already shows these tasks as completed, the baseline starting sequence is:

```text
ORION-F0-000
Repository bootstrap + Gradle wrapper + ToolchainProfile
+ native compatibility preparation

ORION-F0-001
Benchmark harness + BenchmarkProfile

ORION-FND-001
Module skeleton + app bootstrap + CI baseline
+ OrionProcessRole/OrionProcessBootstrapper
+ initial native compatibility checks

ORION-FND-002
Base modules

ORION-FND-003
core:model

ORION-FND-004
Command/Event/Query contracts

ORION-FND-005
OrionClock + BootSessionId + time semantics

ORION-FND-006
CoreReadiness + OrionHealth

ORION-DATA-001
Room schema V1
```

Do not skip ahead to chat UI, llama.cpp integration, or wake word before the required gates.

---

## 37. Final engineering rule

Use AI when necessary.

Use deterministic rules when sufficient.

Persist before depending on memory state.

Recover before executing uncertain effects.

Validate before policy.

Policy before authority.

Outbox before external mutation.

ResourceGovernor before heavy inference.

Fast Path before Cognitive Path.

Push-to-talk before continuous wake word.

Process-death testing before claiming 24/7 resilience.

Invariants before features.

```text
O.R.I.O.N. is not a process that must live forever.

O.R.I.O.N. is persistent state
+ invariants
+ policy
+ recovery
+ controlled execution
+ reconstructible subsystems.
```

When in doubt, choose the implementation that keeps that statement true.
