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

## Canonical software validation

Root built source `d82b01a` in Docker: all 154 app JVM contracts passed without
failures/errors/skips (including the 12 focused resource-fact contracts), lintDebug
passed, and Debug/Release plus the Android test APK assembled. The six owner-default
Android fixtures passed on the owned API-35 emulator, including refusal paths that
must create no resource fact or binding. Frozen APKs, hashes, JVM XML, lint and raw
Android/build reports are retained outside the repository under
`O.R.I.O.N-evidence/f5-next-gates-20261114/qualification/resource-facts/`.
This is software evidence: the Android fixture run did not independently capture
installed APK hashes, and no physical resource calibration or profile qualification
is claimed. Required PR CI remains independent and must pass before merge.
