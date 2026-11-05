# ORION-F3-REMINDER-001 — Prepare validated one-shot CreateReminder contracts

GitHub issue: <https://github.com/otavio-esteves/O.R.I.O.N/issues/43>.

## OBJECTIVE

Start the reminder slice of F3 with a typed CreateReminder request, pure bounded
validation, immutable normalized schema-v1 arguments, and static reminder.create
metadata. Baseline: Architecture V3.2 §§9, 38–40, 58–59, 102; Master Plan §§9.1,
9.3. Preparation is input to policy, never authorization or successful creation.

## FILES ALLOWED

- core/commands reminder contracts/preparer and their unit tests.
- core/skills/metadata reminder descriptor and its unit tests.
- This package and its focused qualification evidence.

Branch: feat/orion-f3-reminder-contracts. No dependency change is required.

## CONTRACTS INVOLVED

CreateReminder carries reminderId, title, scheduledAt (Instant), zoneId (ZoneId),
idempotencyKey and correlationId. Producers retain the reminder ID/key on replay.
The operational result is UNAVAILABLE until a separately reviewed runtime exists.
Ready means validated input, with no dispatcher, persistence or ActionRequest.

Raw and normalized titles each have a 1024-byte UTF-8 bound. Reject oversized
UTF-16 length before allocations, malformed surrogate pairs, C0/C1 controls and
Unicode line/paragraph separators. Trim edge whitespace and normalize NFC only;
recheck expansion. Preserve case, interior whitespace and compatibility characters.

This one-shot contract accepts an already-resolved instant and explicit zone;
it does not resolve LocalDateTime, DST ambiguity, recurrence or device defaults.
Require exact millisecond precision and a representable signed epoch-millisecond
value; reject instead of truncating/overflowing. Past instants are not rejected by
this clock-free preparer: later policy must revalidate time through OrionClock.
No exact-delivery promise, scheduler method or precision capability is inferred.

Canonical schema-v1 JSON has fixed order: reminderId, title, scheduledAtEpochMillis,
zoneId. UUIDs are lower case, epoch millis a signed decimal integer; quote and
reverse solidus are escaped. Explicit ZoneId.id is preserved (aliases are not
coalesced). Target is reminder/<reminderId>. Trace/key metadata remains outside
the canonical material. Future policy binds that material to the ActionRequest
and confirmation fingerprint; no hash or authority is fabricated here.

Static descriptor: reminder.create, SAFE_WRITE, LOCAL_MUTATION, KEYED,
MANUAL_REVIEW, {orion_reminders_write}, no Android permissions, user presence,
POLICY_DEPENDENT confirmation, supportsDryRun=false, argumentSchemaVersion=1.
This is a declaration only, activating no capability or execution path.

## INVARIANTS INVOLVED

INV-004/009/011/015/018/019/021/022/031/035. Existing authority and persistence
boundaries remain unchanged. No ADR is required for this contract implementation.

## TESTS REQUIRED

Typed request/result separation; Unicode/raw/normalized byte bounds; exact JSON
vectors; millisecond precision, range and negative epoch checks; explicit zones
and DST-boundary resolved instants; material changes versus trace/key stability;
redacted diagnostics; immutable descriptor capabilities and conservative metadata.

Canonical Docker checks:

```bash
docker compose run --rm android-build :core:commands:testDebugUnitTest :core:skills:metadata:testDebugUnitTest :core:commands:assemble :core:skills:metadata:assemble :core:commands:lint :core:skills:metadata:lint verifyModuleBoundaries testModuleBoundaries --max-workers=2
```

## ACCEPTANCE CRITERIA

Validated values are immutable and produce deterministic bounded material;
invalid input fails with technical reasons and no title leakage. Focused tests,
assembly, lint and dependency checks pass. Required CI must pass before merge.
This package does not close F3 or declare DETERMINISTIC_AGENT_READY.

## OUT OF SCOPE

CancelReminder/SnoozeReminder, policy/admission, ActionRequest persistence,
owned Skill execution, reminder reads/UI, new schemas, OS scheduling, F4 recovery,
local-time/DST resolution, recurrence, Cortex and voice are separate packages.

Process death loses recomputable preparation only; no durable state or effect is
introduced. No migration, permission, exported component or process change. All
content-bearing diagnostics must remain redacted. Physical S21 qualification
remains deferred in issue #23; no Android runtime/hardware qualification is claimed.

## VALIDATION STATUS

Canonical Docker checks passed: **50 JVM cases**, including **23 new reminder
cases**, with zero failures/errors/skips; debug/release AAR assembly and lint for
both modules passed. Module boundaries and all 36 negative-fixture assertions
passed. Final run: 179 tasks, 1m10s (44 executed, 2 FROM-CACHE, 133 UP-TO-DATE).
Independent contract/source/test review found no blockers.

Source/artifact identities and suite counts are recorded in
[the build summary](../qualification/f3-reminder-contracts-build-summary.json).
Required remote CI must pass at the final PR head before merge. No device tests
were run: this increment adds no Android runtime or durable-state behavior.
