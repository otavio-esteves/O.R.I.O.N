# ORION-F3-DEVICE-001 — Read-only device information and technical activity

GitHub issue: <https://github.com/otavio-esteves/O.R.I.O.N/issues/47>.

OBJECTIVE: Complete the F3 read-only device/time/battery slice and a bounded native
Activity history screen. Both flows expose selected DTOs without mutation.
Baseline: Architecture V3.2 command/query ownership and SkillDescriptor contracts;
Master Plan §§9.1, 9.5–9.6.

FILES ALLOWED: core/queries device/activity APIs and tests; core/skills/metadata
read-only descriptors/tests; skills/device; feature/device; feature/activity;
app/device Android adapters; data/events read-only history adapter; relevant
Gradle/module declarations and tests. MAIN runtime/navigation wiring is integrated
by the primary agent in this package, including Home shortcuts and navigation/recreation regression.

CONTRACTS INVOLVED: Query, OrionClock, SkillDescriptor, selected immutable DTOs,
MAIN-only Android/source adapters and bounded stable history cursors. Battery is
sampled using sticky ACTION_BATTERY_CHANGED; unknown/malformed data stays unknown.
Civil time comes from injected OrionClock. The UI refreshes explicitly and on
recovery publication; no persistent observer or background execution is installed.
Activity reads technical codes/status/time only, never encrypted content, targets,
arguments, errors, correlation metadata or audit payloads. Pagination uses a stable
(timestamp, kind-prefixed ID) cursor, bounded to 100 records and deterministic order.

INVARIANTS INVOLVED: INV-001/003/009/011/019/031/033/035. Queries cannot drain queues,
acknowledge events, mutate state or authorize a Skill. Descriptors are static
SAFE_READ/READ_ONLY/NATURAL/RETRY_SAFE, with no permission, confirmation, user
presence or execution side effect. No new architectural authority is introduced.

TESTS REQUIRED: malformed/unavailable battery values; bounded percentage arithmetic;
charging/plugged distinctions; injected time; immutable data; static descriptors;
stable history pagination across equal timestamps; unavailable storage; no raw
content or error exposure; meaningful Compose read-only refresh/history cases.
Canonical Docker build/unit/lint/module gates are run by the integrating agent.

ACCEPTANCE CRITERIA: user can inspect permitted battery/charging/time/device state
and refresh bounded technical history; unknown states are explicit; feature modules
have only Core ports/DTO dependencies; queries leave database counts/versions and
outbox/ingress statuses unchanged. No test is claimed until it is actually run.

OUT OF SCOPE: mutable Skills, reminder/timer execution, event delivery/acknowledgment,
notifications, memory CRUD, F4 scheduling/recovery, AI/voice, schema changes and S21
hardware qualification. Process death discards only recomputable read snapshots;
no migrations, permissions, exported components or process-topology changes occur.

VALIDATION: Canonical Docker checks passed in4m7s (903 tasks), including23 JVM cases (9 new), zero failures/errors/skips, changed feature/data lint and debug/testAPK assembly. Module boundaries passed. Host/API35 x86_64 emulator passed8 actualAndroid cases for source/history/screens and navigation/recreation. Evidence with source/APK hashes: [device/activity](../qualification/f3-device-activity-evidence.json). Required remoteCI must pass before merge; no physical or fullF3/F4 qualification.
