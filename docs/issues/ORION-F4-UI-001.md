# ORION-F4-UI-001 — Accurate Compose recreation qualification

OBJECTIVE: Qualify task/reminder recreation with the exact original durable pending
request and explicit user confirmation. Diagnose the read/presentation mismatch
before changing implementation; never prepare or confirm a substitute request.

FILES ALLOWED: the two task/reminder Android UI tests and focused qualification
reports. Temporary count/error-only screen probes are investigative and must be
removed before commit. No production behavior or dependency-version changes.

CONTRACTS INVOLVED: existing read-only pending/query presentation, Startup Barrier,
activity-scoped admissions, exact confirmation binding and unchanged Action Outbox.

INVARIANTS INVOLVED: INV-001/004/006/011/014/018/019/021/022/023/035.

TESTS REQUIRED: Preserve the exact fixture-scoped selectors, enabled assertions,
single clicks, 10-second bounds and absence of a committed task/reminder before
confirmation. Retain failures; compare legacy and v2 test setups against the same
application APK. Run the two affected cases, all 325 default Android cases and
canonical Docker check/build/lint/static checks. No skips or weaker assertions.

ACCEPTANCE CRITERIA: The supported Compose v2 environment observes the recreated
screen consistently while preserving every original assertion and user gesture.
Both focused cases and the complete expected manifest pass. Technical probes are
absent from committed production sources and the measured final application DEX.

DIAGNOSTIC FINDING: Temporary count-only probes observed the recreated gateway
read one pending request and the screen compose one pending item while the legacy
test searched an older semantic state. On the identical application APK, the v2
focused pair passed and a subsequent legacy control pair failed. The exact upstream
framework cause remains unproven; no application-state correction is inferred.

OUT OF SCOPE: Production implementation changes, architecture/IPC/process/schema,
action recovery, authorization, alarms, native ABI policy, AI/voice or F4/soak approval.
