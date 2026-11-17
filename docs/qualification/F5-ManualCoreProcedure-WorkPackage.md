# Finite manual Core procedure — #163 HOST

OBJECTIVE: Provide the concrete HOST entry for a finite reviewed sequence of original Core requests on the same Main/Owner. A cold first request does not require preexisting AI PSS; the original Core still performs every readiness, restart, governor and capability check. After an original PROCESS_DIED terminal, only actual paired instance-death proof and technical quiescence can permit another explicitly planned request.

FILES ALLOWED: New manual-procedure HOST protocol/journal/adapters/CLI and focused harnesses, shared closed sidecar parser, the minimum v2 checkpoint/budget validator extension and procedure notes. APP163 belongs to its separate author. No app edits, v1 changes, product services/processes/IPC/Room schema, Governor/policy/Owner replacement, calibration values or arbitrary shell/profile CLI.

CONTRACTS / INVARIANTS: Architecture V3.2 Core authority, ADR-0001, existing qualified instrumentation and actual Owner path, assembler158, invocation journal/ACK160, paired exit/original-policy source163. INV-005/006/009/011/012/013/018/019/027/028/029/030/032/033/035/036. This is additive preparation, never CoreGrant/admission or a qualification gate.

TESTS: Actual temporary fsync/CAS/O_TMPFILE publication, ACK loss and substitution, constructor interruption, one persistent worker, cold entry, confirmed versus unpaired/foreign death, new distinct planned request and original refusal, irreversible transport/identity uncertainty, source timing, cooldown/dedup, no overlapping instrumentation, no catch-up, bounded cleanup and original interruption identity. Actual public ASM positive/negative paths use independently supplied model-size metadata; failed imports are not validation.

ACCEPTANCE: An exact new-only directory and durable ACK precede each selected original Core call. Only explicit finite OBSERVE/WORK steps execute. Requests after confirmed death are new attempts, never replay of an UNKNOWN invocation. Missing PSS remains null. Missing/ambiguous proof remains pending. Known Core refusal/failure terminates the procedure without inferring policy from error strings. Unknown intent/result/identity/transmission never resumes. Owned cleanup is separate and must be proven safe. Soak160's executor, observation gates and coverage rules remain unchanged.

OUT OF SCOPE: Source calibration/model/profile approval, actual limiter stimulus implementation, physical evidence, 24h/normal-background autonomy, F4/F5/F6 closure, shared Owner/history/model/business cleanup or recovery reset.

## Concrete entry and plan

`scripts/android/f5_manual_core_procedure.py` composes the existing `FixedAdb`, target/source/APK fences, fixed runner, real assembler and pinned publisher. It does not accept a command, model choice, prompt, token/thread values, profile grant, retry or resume option. Set each variable from the frozen reviewed composition; none is an operating-value default:

```sh
python3 -B scripts/android/f5_manual_core_procedure.py \
  --adb "$ORION_F5_ADB" --serial "$ORION_F5_SERIAL" --target "$ORION_F5_TARGET" \
  --source-root "$ORION_F5_SOURCE_ROOT" --source-manifest "$ORION_F5_SOURCE_MANIFEST" \
  --app-apk "$ORION_F5_APP_APK" --test-apk "$ORION_F5_TEST_APK" \
  --plan "$ORION_F5_MANUAL_REVIEWED_PLAN" --evidence-dir "$ORION_F5_NEW_EVIDENCE_DIR" \
  --manual-core-procedure --instrumented-foreground
```

The closed plan has `schemaVersion=2`, `kind=ORION_F5_MANUAL_CORE_PROCEDURE_PLAN`, runId, ownedFixtureId, identity, target, limits and steps. Identity/target retain 160's exact metadata fields, including independently supplied nullable modelSha256/modelSizeBytes and configurationSha256. These references do not select or authorize a model. There are no defaults.

Limits are procedureDurationMillis, transportTimeoutMillis, minimumAttemptIntervalMillis, resourceExitCooldownMillis, maximumResourceDeaths and maximumStartLatenessMillis. Each ordered step has exactly offsetMillis and operation=OBSERVE|WORK. At most 128 steps are accepted, with an explicit finite duration, strictly increasing offsets and request cadence. The tighter worst-case byte/node budgets can reject smaller plans before OPEN. OPEN is a control operation before the first offset; cleanup is a separate bounded owned phase after halt or expiry. No BOOTSTRAP is generated: the first planned WORK invokes the real Core from its ordinary cold state.

Every WORK receives a distinct technical UUID and a new original Core request. Each intent binds plan/run/fixture/operation/step/stamp/attempt through an integrity SHA, which is not an admission decision. One persistent worker handles requests; no second worker replaces a blocked or interrupted one. During WORK, an OBSERVE step can issue only fixed read-only OS queries and record a WORK_INFLIGHT gap. It does not invoke a second instrumentation. Steps missed by time/cadence/availability are not caught up. A refused technical slot may be followed only by later explicit steps; no request is inserted or retried.

The last HOST clock/ownership fence runs after potentially slow read-only device preflight, immediately before the fixed instrumentation transport. It is not a Core permit. Main executes the original Owner/governor checks after the call enters Main. Native/runtime/resource facts come from APP163, not a HOST reconstruction.

## Known death versus UNKNOWN

The checkpoint top-level keys are identical to 160. New technical execution state DEAD_AWAITING_EVIDENCE accompanies the retained original PROCESS_DIED terminal. Its settlement is UNPROVEN, and it cannot permit WORK or cleanup. Planned OBSERVE operations remain read-only and may inspect actual bounded exit/proc evidence.

`deathSettlement` exists only inside the versioned policyFacts sidecar. A confirmed object has exactly schemaVersion=1, originalAttemptIdSha256, originalTerminal=PROCESS_DIED, incidentIdentitySha256, observedElapsedMillis and bootCount. The diagnostic observation time is not an invented exact time of death or policy evaluation. Confirmation returns executionState=IDLE and settlement=ORIGINAL_PROCESS_DEATH_CONFIRMED. It must refer to the exact earlier original WORK_RESULT acknowledged by this journal, the same Main session/boot and the paired incident. An old incident cannot settle a different attempt.

The incident identity is 160's canonical SHA of PID, fixed qualified AI process name, reason and wall timestamp; classification changes and repeated history reads do not make a new death or renew cooldown. The source keeps UID/start ticks/private endpoint/turn references inside Main. A missing/truncated/unpaired/proc-denied/reused-PID/discontinuous-clock source cannot confirm the old instance ended. Host bounds count actual paired AI deaths conservatively without relabeling UNCLASSIFIED as MemoryLimiter evidence. Cooldown starts conservatively at the first actual confirmation observation, checked in both HOST and device monotonic domains; Original Core decisions remain the authority before a later inference.

Transport timeout, lost ACK/result publication, malformed framing and changed identity are terminal UNKNOWN. Later policy or exit observations never reverse HOST UNKNOWN, even if the app subsequently becomes idle. The journal cannot be reopened as an executable controller. No Owner, graph, endpoint or history replacement clears this state. A logical timeout or missing PSS does not prove native termination. Known Core refusal/failure is retained as reported and stops the procedure; only original policy facts can describe a Governor/Restart refusal.

## Original policy sidecar and bounds

policyFacts is null or a closed schema1 ORION_ORIGINAL_RESOURCE_POLICY_FACTS object with state/gap, droppedAtLeast, contendedAtLeast, recordingFailuresAtLeast, lossAccountingComplete, drainContended, facts, exitObservation and deathSettlement. Fact batches copy original Governor/Restart decisions and their original input sample times. Local batchIndex is not a global revision. No HOST allowance, health, deadline or policy fence is synthesized.

exitObservation has exactly gap, paired, newIncident, descriptionEvidence and dedicatedReason. The sanitized description category is null or OTHER_ANON_SWAP_MARKER/NO_MATCH/UNAVAILABLE, never raw text. dedicatedReason is null or the actual public symbol value observed on that build, at least 17; it is not inferred from SDK level. The original-policy publication is bounded to 8 KiB; overflow is an explicit unavailable batch, not a retained prefix or clipped facts.

For the real sidecar, the additive v2 checkpoint ceiling is 16 KiB and event ceiling 20 KiB. The journal remains 20 MiB and MAX_ENTRIES remains 4096. Before OPEN both protocols reserve worst-case entries, bytes, JSON objects and lexical nodes. The reservation is `8+3*workCount+2*observeCount` entries, 16 KiB plus that count times 20 KiB, 64 plus count times 160 objects, and 4096 plus count times 2048 nodes. Parsing remains bounded to 50,000 objects/500,000 nodes/depth12. Artifact reservation also includes each planned request's bounded runtime/status/report files; manual total reservation is at most 768 MiB. No Binder limit or v1 format changes.

This larger event shape makes some previously representable dense plans invalid before OPEN. The synthetic 24h grammar fixture now uses explicit sparse 15-minute offsets to remain inside worst-case bounds. That interval is a test value, not a production cadence, physical sample or real soak. Soak160's complete-observation gate and executor are unchanged, including refusal on non-IDLE/incomplete PSS.

## Future owned limiter procedure

Build/install the reviewed qualified artifacts and start/unlock/recover Main explicitly outside the procedure. Freeze the exact source/APKs/model/configuration/target and real `--no-restart` support. Use a reviewed finite plan that obtains the source's required original live anchors before the independently authorized owned stimulus. The manual entry accepts no stimulus command or limit. Apply/restore any override only through the separately reviewed API37 acquisition procedure and preserve its ownership evidence.

Keep the same Main/Owner through initial Core work, live observations, actual death, pending evidence, paired confirmation and later explicit Core requests. If confirmation is unavailable at a later planned request, retain the refused slot and facts; only still-planned observations/requests may follow. Stop at the first original Core refusal/failure, at limits or at the finite end. Preserve UNKNOWN/pending failures without restart or replay. Cleanup only this run's proven idle diagnostic buffers/markers; it never closes shared Owner, deletes models/business/history, clears UNKNOWN or alters Core resource policy.

Instrumentation affects importance, CPU, lifetime and battery. The report always declares NOT_QUALIFIED and all ten approval/gate flags false. No continuous coverage, real 24h, native model admission, autonomy or physical qualification is inferred from a completed manual schedule.

VALIDATION: Canonical Python-only Docker focused run passed 31 tests in 2.753s, zero failures/errors/skips (`/tmp/orion-f5-manual-final-code-20261116.log`), with all ten code/test hashes frozen before/after. The unchanged soak regression snapshot passed 54 tests in 6.326s (`/tmp/orion-f5-manual-soak-regression-20261116.log`). Actual ASM positive validation and size/content negatives are included. A controlled RED reproduced the acknowledged-refusal/worker-HALT race before its fix; earlier REDs preserve missing-manual-module and sidecar rejection separately. APP integration/Android runtime and physical qualification are pending centralized validation. This evidence is DOCKER/HOST with synthetic Core/device/clock fixtures.
