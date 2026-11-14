# ORION-F5-RESOURCE-FACTS-001 — passive MAIN resource facts

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/117
Source base: e7cf08beb166d4b859ab4c90a268edba5007b6c4.

OBJECTIVE: Retain bounded technical projections of the actual snapshot and the
actual already-computed governor/restart decision in the existing MAIN owner.
Absent early evaluations remain absent; facts never become permits or profiles.

FILES ALLOWED: OrionCortexRuntimeOwner.kt optional collector and its existing
resource/restart closures; related app/resources fact/buffer/mapper types;
focused app JVM/Android tests; this work package and F5-ResourceFacts.md.

CONTRACTS INVOLVED: OrionAndroidResourceSnapshot, OrionResourceDecision,
OrionRestartDecision, MAIN ownership, current fail-closed prechecks and clocks.

INVARIANTS INVOLVED: INV-011/013/027/028/032/033/036.

TESTS REQUIRED: Existing snapshot/decision pairing and identical returned
decision; one observer/evaluation with unchanged ordering; no new clock/RPC/bind;
default/no-profile no event; bounded immutable projections and contention/drop/
failure accounting; exceptions and source decisions remain unchanged.

ACCEPTANCE CRITERIA: Optional default-null final collector uses tryLock, no user
callbacks/IO. Copy only bounded sanitized scalars/enums/hash identities and
sanitized exit signals. Preserve missing fields/limitations, with no guessed
PID/epoch/fence/revision/timestamps, new evaluation, profile or admission effect.
Root owns canonical checks and required CI before merge.

OUT OF SCOPE: Client/native/IPC/policy algorithms, configured thresholds/profiles,
durable state, physical completeness/calibration, heavy workload or F5/F6 closure.
ADR-0003 acceptance and any implementation of metrics IPC are separate work.

VALIDATION: Twelve JVM tests and one Android owner test supplied; canonical
execution remains pending root/CI. `git diff --check` passed before review.
No Gradle/Docker/ADB or device execution by this author. Technical scope and
unavailable correlations are documented in
[F5-ResourceFacts](../qualification/F5-ResourceFacts.md).
