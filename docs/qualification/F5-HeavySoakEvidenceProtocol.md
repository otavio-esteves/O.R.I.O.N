# Offline post-Cortex heavy-soak evidence protocol v1

Work package: [ORION-F5-HEAVY-SOAK-EVIDENCE-001](../issues/ORION-F5-HEAVY-SOAK-EVIDENCE-001.md).
Parent [#89](https://github.com/otavio-esteves/O.R.I.O.N/issues/89) and the physical
Core stability prerequisite #64 remain open. This is a technical input format and
pure offline validator. No executor, device loop or workload is supplied.

The Galaxy S21 and physical API37 target are unavailable. There is no physical
result, trusted measurement configuration or approved model/resource profile in
this package. A real runner, reviewed admission configuration, complete workload
protocol and allocated physical window remain prerequisites to actual execution.
The plan's numbers are proposed bounds, not calibrated operational settings or
ResourceGovernor permission. No plan is injected into production qualified plans.

Architecture §§32–35 requires governor-first execution and bounded resource-death
handling. Master Plan §§11.9–11.11 and §31 requires post-Cortex 24 hours plus memory
stress, separately from the Core profile's tasks/reminders/heartbeat/reboot/death.
The F4 recovery journal supplies the intent-before-mutation/owned-cleanup pattern;
this format changes neither that journal nor app recovery, persistence or IPC.

## Validation result and limits

```bash
python3 -B scripts/android/f5_heavy_soak_evidence_protocol.py \
  --plan /path/to/selected-plan.json \
  --journal /path/to/selected-journal.json
```

Omit `--journal` to validate a plan with evidence explicitly unavailable. The tool
only reads those regular local files and prints a minimized JSON report. It has
no ADB, subprocess, network, command dispatch, cleanup execution or output-file
operation. It never imports an executor or resumes a recorded operation.

Every report returns `result=UNVERIFIED_INPUT`, `qualification=NOT_QUALIFIED` and
`environment=HOST_OFFLINE`. `structuralStatus` is `VALID` or `REJECTED`; exit 0
means structural acceptance, including unavailable/incomplete records. Exit 2
means rejected input. It never means a soak, workload or profile passed.

For accepted inputs, `evidenceState` distinguishes:

- `UNAVAILABLE`: no journal or missing runner/configuration/target references.
- `INCOMPLETE`: supplied records expose missing work, duration, observations,
  cleanup, interruptions, gaps or discontinuous clocks.
- `CONSISTENT_REPORTED_RECORDS`: supplied fields satisfy the structural checks.
  Their origin, measurements, execution, chronology and completeness remain unverified.

`physicalEvidenceVerified`, `real24HourDurationProven`, `autonomyProven`,
`workloadQualified`, `profileQualified`, `productionInferenceEnabled`,
`fullF5GateClosed`, `fullSoakGateClosed`
and `commandsDispatched` are always false. Even a perfectly coherent fabricated
24-hour journal receives those same values. Hash references are not signatures,
verified artifacts, qualified configuration or evidence that a runner exists.

Inputs are limited to 2 MiB each and 4,096 journal entries. Unknown/missing fields,
duplicate JSON keys, nonfinite values, wrong types, booleans used as integers,
unsafe/nonregular files and invalid bounds are rejected. Errors use fixed codes
without echoing JSON, file paths or raw exceptions. The report includes only typed
issues, counts and a reported span; it does not copy input identities or observations.

## Plan

The exact top-level fields are:

| Field | Contract |
| --- | --- |
| `schemaVersion` | Integer 1 |
| `kind` | `POST_CORTEX_HEAVY_SOAK_PLAN` |
| `planId` | Canonical nonzero UUID |
| `identity` | Exact object described below |
| `requestedEnvironment` | `PHYSICAL_DEVICE`; a request, not an observed environment |
| `deviceIdentitySha256` | Lowercase SHA256 or null when no target is allocated |
| `runnerSha256` | Lowercase SHA256 reference or null when unavailable |
| `trustedConfigurationSha256` | Lowercase SHA256 reference or null when unavailable |
| `limits` | Exact object described below |
| `ownedFixtureIds` | 1–32 unique canonical nonzero UUIDs |

`identity` has exactly `sourceCommit` (40 lowercase hex digits),
`sourceManifestSha256`, `appSha256`, `testSha256` and `modelSha256` (64 lowercase
hex digits each). There are no model paths, prompts, generated output, device
serials, raw errors, commands, approval flags or arbitrary descriptive strings.
The app/test/model/source binding must be verified independently by the eventual
runner and reviewed evidence procedure; this offline tool checks its shape only.

`limits` contains exactly:

| Field | Accepted proposed range |
| --- | --- |
| `targetDurationMillis` | 86,400,000–604,800,000 (24 hours–7 days) |
| `maximumAttempts` | 1–256, including refused attempts |
| `minimumAttemptIntervalMillis` | 1,000–3,600,000 between reported admitted intents |
| `resourceExitCooldownMillis` | 1,000–86,400,000 |
| `maximumResourceDeaths` | 1–16; reaching the bound requires refusal |
| `maximumObservationGapMillis` | 1,000–3,600,000 |

All limits are integers. These parser ranges bound input processing and proposed
journals; they do not establish safe stress intensity, sampling fidelity, battery
behavior or device budgets. In particular, 24 supplied elapsed hours prove no real
elapsed duration, and an observation gap limit proves no continuous coverage.
The actual workload frequency, model/context/threads/tokens, accounting coverage,
stop thresholds and release criteria still need a reviewed execution protocol.

## Journal binding and clock domains

The exact journal fields are `schemaVersion=1`,
`kind=POST_CORTEX_HEAVY_SOAK_JOURNAL`, `planId`, `planSha256`, `identity` and
`entries`. The ID and identity must equal the selected plan. `planSha256` binds the
whole normalized plan, including limits, references and ownership, using SHA256
of ASCII JSON with sorted keys, `ensure_ascii=True` and separators `(',', ':')`.
The module's pure `plan_digest(plan)` computes it. Changing a plan requires a new
binding; retaining an ID alone cannot reinterpret an existing journal.

Each entry has exactly `sequence`, `hostSessionId`, `hostElapsedMillis`,
`bootCount`, `deviceElapsedMillis`, `kind` and `data`. Sequence starts at zero and
is consecutive. Session IDs are canonical nonzero UUIDs. Boot count is an integer
0–2,147,483,647; elapsed values are integers 0–9,223,372,036,854,775,807.

HOST elapsed values are nondecreasing only within their reported session. A new
session can reset elapsed time and permanently marks the journal discontinuous;
an old session cannot return. The validator never sums separate session spans.
Device elapsed values are nondecreasing only inside the same reported boot.
An increased boot count permits a device clock reset; old and new device elapsed
values are never subtracted. A lower boot count is rejected. A pending attempt
crossing reboot remains unresolved, never a resumed execution.

`reportedHostSpanMillis` is a subtraction of supplied first/last HOST values only
when all entries share one session; otherwise it is null. A device reboot can be
represented inside that HOST span, but neither the span nor the session UUID
attests continuous real observation. No wall-clock timestamp or caller boolean
is accepted as duration proof.

## Closed entry vocabulary

`RUN_BEGIN` with empty `data` is the first entry. `RUN_END` with exactly
`outcome=FINISHED|INTERRUPTED` is the last if present. A claimed finished run can
still be incomplete; missing end is explicit and never inferred successful.

| Kind | Exact `data` fields and ordering |
| --- | --- |
| `FIXTURE_INTENT` | `fixtureId`; owned and unique, recorded before reported preparation |
| `FIXTURE_READY` | `fixtureId`; preceding intent required |
| `WORK_INTENT` | `attemptId`, `fixtureId`, `admissionDecisionSha256`; unique attempt UUID, ready owned fixture, at most one unresolved attempt |
| `WORK_RESULT` | `attemptId`, `outcome`, `reason`; matching intent, one reported terminal, same device boot |
| `RESOURCE_EXIT` | `attemptId`, `classification`; alternative terminal for matching intent, same device boot |
| `OBSERVATION` | Exact resource/queue fields below |
| `OBSERVATION_GAP` | `reason=TRANSPORT\|CADENCE\|METRIC_UNAVAILABLE\|DEVICE_REBOOT\|HOST_INTERRUPTED` |
| `INTERRUPTION` | `reason=HOST_INTERRUPTED\|DEVICE_DISCONNECTED\|PROCESS_DIED\|UNKNOWN` |
| `CLEANUP_INTENT` | `fixtureId`; previously intended owned fixture, no unresolved work |
| `CLEANUP_RESULT` | `fixtureId`, `outcome=REMOVED\|ABSENT\|FAILED\|UNAVAILABLE`; matching cleanup intent |

`admissionDecisionSha256` is a SHA256 reference or null. A non-refused attempt
requires non-null runner/configuration/target and admission-decision references.
Their presence does not establish trust, freshness or actual governor approval;
the future runner must obtain fresh approval before load/inference. The validator
cannot verify a decision from its hash and never grants one.

Work outcomes are `SUCCEEDED`, `CANCELLED`, `TIMED_OUT`, `FAILED` or `REFUSED`.
Non-refused outcomes require `reason=NONE`. Refusal reasons are
`RESOURCE_COOLDOWN`, `RESOURCE_LIMIT`, `RESOURCE_UNAVAILABLE`, `RUNNER_UNAVAILABLE`,
`CONFIGURATION_UNAVAILABLE` or `ADMISSION_REFUSED`. A set of refusals supplies no
reported heavy work. Reusing an attempt ID or reporting a second terminal is
rejected; this local check is not a cross-run replay ledger.

Resource classifications are `MEMORY_LIMITER`, `LOW_MEMORY`, `EXCESSIVE_RESOURCE`
or `UNCLASSIFIED`. A supplied label is not an observed ApplicationExitInfo signal.
An unclassified exit remains incomplete. Each exit counts toward the proposed
bound. Below that bound, an intent inside the reported same-boot cooldown must
end refused with `RESOURCE_COOLDOWN`. At the bound, subsequent attempts must end
refused with `RESOURCE_LIMIT`. After a device reboot, an old resource fence is
invalid for elapsed subtraction and subsequent work must be refused with
`RESOURCE_UNAVAILABLE` (or `RESOURCE_LIMIT` at the bound).

Version 1 intentionally has no event that approves a fresh post-reboot history or
resets resource deaths. Real recovery/re-evaluation remains outside this format;
a new plan/journal is not permission to forget retained resource evidence. No
entry causes automatic rebind, relaunch, replay or generic retry.

Interruption closes no action or inference as successful. It leaves any pending
attempt unresolved in the report; later progress/cleanup cannot erase that fact
or attach a late terminal to the abandoned attempt. Cleanup may repeat only via a
new explicit cleanup intent. A failed cleanup stays visible after a later successful
repeat. Missing/failed cleanup never authorizes deleting an unidentified fixture.
These are records to review, not executable cleanup instructions.

## Observations and remaining runtime dependencies

Observation data has exactly `mainPid`, `aiPid`, `mainPssBytes`, `aiPssBytes`,
`aggregatePssBytes`, `thermalStatus`, `memoryPressure`, `serviceState`,
`nativeRuntimeLoaded`, `actionPendingCount` and `actionUnknownCount`.

Available resource observations require distinct positive signed-32-bit PIDs,
positive PSS values no greater than 1 TiB with exact aggregation, thermal 0–6,
pressure `NONE|MODERATE|CRITICAL`, a boolean native-runtime value and service state
`UNLOADED|LOADING|LOADED|INFERENCING|UNLOADING`, matching the current service
`getStatus` surface. `LOADED` and `INFERENCING` require `nativeRuntimeLoaded=true`;
the service can only report those states after its runtime exists. These are structural
checks, not resource admission. The heavy lifecycle may leave its native wrapper
resident after model unload: `UNLOADED` with `nativeRuntimeLoaded=true` is valid.
The never-loaded baseline's false-native requirement does not apply here.

Unavailable resources use null for both PIDs, all PSS values, thermal and native
runtime, plus `UNAVAILABLE` for service state and pressure. Partial resource objects
are rejected. Both queue counters are integers 0–1,000,000 or both are null. Null
means unavailable, never zero. UNKNOWN counts are sampled observations only;
changes do not prove action resolution, absence of intermediate growth, autonomy
or safe retry. No action payload, status transition or retry command is accepted.

Missing samples, excessive reported cadence gaps, process-identity changes and
interruptions remain incomplete even after subsequent progress. Polling/observer
coverage cannot be inferred from a coherent sequence. A real cycle still needs
MAIN/AI endpoint ownership, stable identity during load/infer/terminal/unload,
current complete native-inclusive accounting, retained failures and deadlines.
The runner and trusted configuration are not implemented by this module. Actual
MemoryLimiter stimulus, calibrated workload, physical 24-hour window, independent
observations and review remain required before any qualification decision.

HOST fixtures can be exercised without ADB or hardware:

```bash
python3 -B scripts/android/test_f5_heavy_soak_evidence_protocol_harness.py
```

They include fabricated coherent full-day records to prove that structural
acceptance never becomes real-duration, physical, autonomy, workload or gate proof.
