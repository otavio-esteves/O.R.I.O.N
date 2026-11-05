# ORION-F3-TASK-002 — Deterministic CreateTask preparation policy

Issue: <https://github.com/otavio-esteves/O.R.I.O.N/issues/25>.

## OBJECTIVE

Implement the first deterministic policy for already validated task creation.
Static descriptor requirements and fresh MAIN admission/state evidence determine
negative decisions or a requirement for future exact human confirmation. There is
no preparation approval that can execute an action or prove consent.

Architecture V3.2 §§58–61/102 and Master Plan §§9.1–9.2 govern this implementation.
This fills the existing Policy Engine boundary without changing its authority.

## FILES ALLOWED

`core/policy/**`, its required Core API dependencies, this work package and focused
qualification evidence. No app, data, schema, IPC, UI or execution-store changes.

## CONTRACTS INVOLVED

ValidatedCreateTask; static SkillDescriptor; authenticated MAIN ingress ownership;
operational Startup Barrier; fresh capability/permission/presence observations;
versioned policy rules; exact later ActionRequest and confirmation binding.

### API and trusted admission source

`OrionCreateTaskPolicy.evaluate(ValidatedCreateTask)` accepts no source, role,
capability, permission, confirmation, rules version or context supplied by a
client. The constructor accepts a `CreateTaskPolicyContextSource`, wired only by
trusted Core code and **scoped to one authenticated admission**. Its `read()` has
no command or UUID arguments to turn producer identifiers into authentication.
The default source is unavailable and initializes no runtime infrastructure.

The future MAIN adapter must:

- Own and authenticate the real admission and its origin at the Core boundary.
- Capture the binding from that admitted normalized input, rather than recompute
  an assertion of trust from an arbitrary command when policy asks for evidence.
- Supply a fresh bounded observation of process role, origin, actual operational
  barrier, functional capabilities, required permissions and user presence.
- Never infer origin from command/correlation/task IDs, OrionIntent.source, model
  text, UI foreground or presence alone. A request gesture is not confirmation.
- Use coherent Core-owned state and a generation that advances for relevant
  observed transitions, including ABA, without reuse during that source lifetime.
- Preserve DB/barrier lock order during future execution revalidation. Reading an
  operational barrier snapshot must not acquire its admission monitor while the
  SQLite writer is held.

No production source adapter is included here. Positive test doubles exercise the
algorithm, not authentication of actual Android/UI/IPC callers. An admission ID,
Kotlin visibility restriction or input digest is not an authentication credential.

### Input binding

`CreateTaskPolicyInputBinding.from(task)` privately retains a SHA-256 digest over
length-prefixed UTF-8 fields in this order:

```text
orion_task_policy_admission_v1
 task.create
 argumentSchemaVersion
 taskId
 idempotencyKey
 correlationId
 normalizedTarget
 canonicalNormalizedArguments
```

All fields come from validated immutable input and fixed code. This is an
in-memory equality check for the trusted source's exact admitted input. It is not
normalizedPayloadHash, confirmationFingerprint, a signature, receipt or authority.
The digest is not exposed in diagnostic strings. The input is bounded by the
existing CreateTask schema, and its transient digest input copy is cleared.

Unlike material action arguments, this admission binding also includes trace and
replay identities: another key/trace requires the corresponding authenticated
admission. It does not change canonical JSON, targets or the F2 fingerprint format.

### Context snapshots and decisions

Snapshots contain admissionId, binding, nonnegative state revision, process role,
origin and tri-state evidence. Capability and permission maps are defensive,
unmodifiable copies. Missing entries mean unavailable, never satisfied. MAIN,
an exact binding, explicit user request origin, open operational barrier, static
required capabilities/permissions and current user presence are all required.
The v1 descriptor's Android permission set is empty; this grants none and does not
replace the independent `orion_tasks_write` capability.

The engine reads the source once for evaluation and once before a positive result.
The entire snapshots must match, including generation, admission, binding, state
and copied maps. It caches no decision. Differing observations invalidate the
result, even when a producer forgot to change revision. **Two reads are not an
atomic OS snapshot and cannot detect unobserved ABA with a reused generation.**
No safety guarantee is extended beyond evaluation; future execution must revalidate
all applicable authority, capabilities, presence, permissions, barrier, version,
confirmation and ownership inside writer admission.

Results have no user content:

```text
Denied(technical reason)
Unavailable(technical reason)
NeedsConfirmation(policyVersion)
```

The only positive result is NeedsConfirmation, including for legitimate requests.
The fixed rules version is `orion_task_create_policy_v1`, supplied by code, never
producer IDs, title content or ephemeral revision. v1 has no configurable preference
that can omit confirmation. Any relevant future rules/configuration change requires
a new effective version and applicable reconfirmation; state revision is not that
version. Metadata is read directly from the static task.create descriptor, with
no client-supplied descriptor or generic authorization-level ordering.

Expected source unavailability and source exceptions yield negative technical
results without exception text. CancellationException propagates; interruption
restores the thread flag and propagates; fatal Errors are not swallowed. There
is no fallback to success or automatic retry.

### Integration limits

NeedsConfirmation is not ActionExecutionAuthority.policyAllows=true, a permission
grant, proof of a gesture or a cached authorization for another request. There is
no ActionRequest/actionId, confirmation snapshot/fingerprint, persistence, Skill,
capability activation, barrier release, dispatch or external effect in this package.
The later handler must obtain policy for its actual input, prepare/persist the
exact request and acquire separately bound human confirmation when required.

Denied and Unavailable are observations, not Action/Ingress terminal states.
In particular STARTUP_GATED must leave durable ingress behind the operational
barrier; a future handler must not translate it into terminal rejection/discard.

## INVARIANTS INVOLVED

INV-004/009/011/018/021/022/023/031/033/035. Policy inputs cannot grant authority;
LLM/external text remains data; confirmation remains bound to the exact future
ActionRequest; only trusted MAIN ownership supplies context. No raw payload/hash
logging or auxiliary-process storage/bootstrap is introduced.

## TESTS REQUIRED

27 specific JVM cases cover: default unavailable source; valid admission still
requiring confirmation; missing/auxiliary process evidence; untrusted/automated
origin despite presence and matching IDs; mismatched title/task/key/trace; normalized
Unicode equivalence; operational barrier and presence states; absent/false/unknown
capabilities; unrelated capability/permission flags; no-permission metadata still
requiring capability; safe context defaults; sanitized source exceptions; failed
final read; cancellation/interruption/fatal-error propagation; changes to admission,
revision, process, origin, barrier, presence, binding and maps; revocation without
revision increment; no cached decisions; defensive snapshots; static rules version;
instruction-looking title data; unchanged validated input and redacted diagnostics.

Canonical checks:

```bash
docker compose run --rm android-build :core:policy:testDebugUnitTest :core:policy:assemble :core:policy:lint verifyModuleBoundaries testModuleBoundaries --max-workers=2
```

Independent read-only agent reviews cover trust/privacy and concurrency/integration;
primary review and required PR CI validate the final candidate before merge.

## ACCEPTANCE CRITERIA

Evidence missing/unavailable/negative never yields a positive decision. Positive
policy requires exact MAIN admission, current matching observations and future
confirmation; it cannot create a credential or execute. Decisions preserve the
input and contain technical metadata only. Boundaries and existing contracts are
preserved, with no new external libraries or runtime wiring. Focused tests, build,
lint, dependency checks and required PR CI pass before integration.

## PROCESS DEATH / SECURITY / MIGRATION

Policy evaluation is recomputable in memory. Process death loses no new durable
state and resumes no action. No Room schema, ciphertext/confirmation/IPC format,
process topology, permission or exported component changes. Existing F2 ownership,
confirmation, idempotency and recovery remain mandatory and unchanged. There is no
Android/runtime/hardware qualification claim from these JVM tests.

## OUT OF SCOPE

Confirmation preferences; production context/permission/presence adapters;
ActionRequest preparation and human confirmation gestures; ActionExecutionAuthority
implementation; TaskSkill/idempotent task persistence/UI; scheduler/full recovery
F4; Cortex/voice. The user explicitly deferred physical S21 qualification in
[issue #23](https://github.com/otavio-esteves/O.R.I.O.N/issues/23). This package does
not declare TRANSACTION_CORE_READY or DETERMINISTIC_AGENT_READY.

## VALIDATION

The first canonical focused pass completed 241 tasks in 4m49s, with all 27 tests
passing, no failures/errors/skips. Concurrency and security reviews found no
blockers; source comments were clarified to state the two-observation/ABA limit.
The final canonical pass also succeeded: **27 tests**, zero failures/errors/skips;
241 tasks in 3m21s (22 executed, 1 FROM-CACHE, 218 UP-TO-DATE). Debug/release AARs
were generated; lint had no issues; dependency checks and all 36 negative-fixture
assertions passed. No Android runtime/device execution is asserted for this pure
policy package. See [source/artifact evidence](../qualification/f3-task-policy-build-summary.json).
Required PR CI must pass on the final head before integration.
