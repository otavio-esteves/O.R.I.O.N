# Physical MemoryLimiter acquisition procedure

Tracking: [issue #161](https://github.com/otavio-esteves/O.R.I.O.N/issues/161).
This is a reviewed procedure for future execution, not an executed incident,
calibrated limit, approved profile or F5 result. No device command was run to
prepare it. Architecture §35.1, the actual Core authority and the physical gates
remain unchanged. The [offline correlation protocol](F5-LimiterEvidenceProtocol.md)
checks consistency only; its input cannot authenticate an incident.

## Platform facts and build-specific decision

The current [ApplicationExitInfo reference](https://developer.android.com/reference/android/app/ApplicationExitInfo#REASON_MEMORY_LIMITER)
places the dedicated constant in version 37.2. Record its actual availability and
value on the selected build; SDK_INT 37 alone is insufficient. The
[Android 17 behavior documentation](https://developer.android.com/about/versions/17/behavior-changes-all?hl=en)
also describes REASON_OTHER with the exact `MemoryLimiter:AnonSwap` marker, and a
PID-scoped manual override with a `none` restoration operation.

There is a material syntax ambiguity to resolve on the target: that Developer
page describes the numeric manual limit in MB, while the reviewed
[AOSP android17-release procedure](https://android.googlesource.com/platform/frameworks/base/+/android17-release/services/core/java/com/android/server/am/MemoryLimiter.md)
describes a percentage. Do not translate between them, copy an example number or
guess from the command name. Freeze the target's build/QPR identity, matching
shell implementation and actual help/status evidence. If the syntax, unit or
ownership of an existing override cannot be established, record UNSUPPORTED and
stop before a write. Neither document authorizes changing system-wide limits.

## Prerequisites

1. Allocate an authorized physical API37 target. Bind the exact source commit,
   complete tracked-source manifest, produced and installed app/test APK hashes,
   signing/variant, model digest/size and producer implementation. Use the
   [read-only preflight](../../scripts/android/prepare-f5-physical-qualification.py)
   for its supported scope. A physical label, matching hash or successful parser
   does not approve the target/model/profile.
2. Record actual build/QPR/full SDK information when available, extension values,
   ABI, page size and declared PHYSICAL_DEVICE environment. Missing full SDK or
   dedicated-symbol information stays unavailable; do not derive it from an
   unrelated extension number. Verify that the limiter is enabled, monitoring the
   intended process and not being ignored. A command which has no effect does
   not qualify the feature. Do not force-enable a missing platform feature.
3. Complete the separate model/resource coverage and calibration review before
   requesting normal heavy work. The stimulus does not approve a model, alter
   the Governor, install a fallback or authorize a larger allocation. An already
   admitted owned AI process may be the target; no arbitrary memory allocator is
   introduced to make an exit happen.
4. Fix the explicit acquisition window, observation cadence, transport watchdog,
   expected signal delay, reviewed manual limit/unit, cooldown/death bound and
   abort/cleanup schedule. None has a default in this procedure. A representable
   numeric field or format ceiling is not an operating value.
5. Use an actual trusted collector for live process identity, paired time,
   sanitized ApplicationExitInfo and original Core policy facts. The current
   product's ProcessExitSignal alone lacks the process-instance/window/identity
   receipt. The [instrumented workload work](https://github.com/otavio-esteves/O.R.I.O.N/issues/160)
   must expose actual facts or explicit unavailability. Do not fill missing
   revision, boot, timing, configuration or admission fields to satisfy v1.

## One incident

1. Start a new owned HOST evidence directory and incident identifier. Preserve
   interrupted earlier attempts. Verify source/local/installed identities again.
   Observe the existing MAIN graph, MAIN PID and current private AI endpoint; do
   not rebind or launch a substitute to populate a measurement.
2. Pair fresh wall and monotonic observations around the live AI receipt. Record
   actual process name/UID, PID, boot identity and kernel process-start witness.
   Require the exact qualification AI process and its app UID; distinguish it
   from MAIN and every other process. A PID alone cannot survive reuse. Observe
   the pre-incident actual Core state and any already-issued operation. Missing,
   stale, cross-boot or discontinuous identity/time refuses the stimulus.
3. Collect the fixed read-only platform observations for the allocated target:

   ```bash
   adb -s "$ORION_LIMITER_SERIAL" shell am help
   adb -s "$ORION_LIMITER_SERIAL" shell am memory-limiter status
   ```

   Bind the reviewed command syntax/unit and original owned override state to
   this incident. Sanitize retained observations; do not save an unrestricted
   logcat, descriptions, heap dump or other application's data as its receipt.
4. Persist and fsync the stimulus intent before its one effect. It binds the
   exact process-instance witness and approved plan reference, original state,
   numeric limit/unit and deadline. Lost or uncertain intent ACK means no call.
   Recheck the same instance immediately before issuance. There is no automatic
   retry after an uncertain call, even if the local ADB client terminated.
5. Only after the target-specific syntax/unit review, issue this fixed command
   shape once; the variables are explicit reviewed inputs, not supplied values:

   ```bash
   adb -s "$ORION_LIMITER_SERIAL" shell am memory-limiter manual "$ORION_LIMITER_AI_PID" "$ORION_LIMITER_REVIEWED_LIMIT"
   ```

   Do not target MAIN, a UID, another PID or a replacement process. Do not use
   `ignore`, `max`, vendor XML edits, system_server restarts, root workarounds or
   an unqualified load. Return code zero proves neither a limit breach nor death.
6. Observe within the declared finite window without issuing another heavy
   operation. Preserve live/exit/missing observations and transport uncertainty.
   A state transition can alter the platform's override behavior: recapture
   actual status rather than assuming the override remains applied. Timeout or
   a local process ending never proves that the remote operation stopped.
7. Obtain a new actual ApplicationExitInfo record through MAIN, correlated with
   the exact live witness and paired window. Preserve numeric reason and actual
   exposed dedicated-symbol information. Match the legacy marker transiently,
   emit only the closed sanitized category and discard the description/trace.
   Distinguish global low memory, SIGKILL, JVM/native crash, permission changes
   and unknown causes. Do not relabel a watchdog self-kill as MemoryLimiter.
8. Reject stale history, reused PIDs, missing records, reboot or wall-time jumps
   as unsupported/insufficient correlation. ApplicationExitInfo supplies no exit
   BootSessionId. Current boot identity and an exit wall timestamp cannot repair
   that missing association. Re-reading one historical record is one incident,
   not several resource deaths.

## Actual Core response

Capture the surviving MAIN graph and the original Core restart/resource decision
associated with the new exit. The evidence must demonstrate degradation,
preload/keep-alive behavior, bounded backoff/fallback and the actual refusal
before a new native dispatch. A HOST selection, configuration digest, hypothetical
policy evaluation or successful shell command is not Core authorization.

An explicitly selected ordinary Core request may be needed to exercise its real
guards. Submit it through the existing Owner path; it still performs readiness,
restart, policy and fresh Governor checks. A request is not an issued heavy
operation. Require source evidence of refusal/no native dispatch, not a HOST
boolean. Missing policy revision/cooldown/configuration facts remain unavailable;
an incomplete technical sidecar must not be converted to a complete v1 receipt.

Review one genuinely new incident per repeated-death trial. Later attempts require
their own reviewed selection, actual current identity and Core checks; they never
replay UNKNOWN work. Prove the configured death bound eventually refuses further
heavy work. Any subsequent lower-tier admission requires separately measured,
approved fallback profiles and fresh complete resource evidence. This procedure
does not supply those profiles or authorize resumption.

## Owned cleanup and stop paths

Cleanup is a separate finite action and is permitted after the acquisition window
expires only for the proven owned override. It never resumes work or clears Core
history. Recheck boot, UID/name, PID and kernel start witness before a write.

| Observed condition | Required disposition |
| --- | --- |
| Same live instance; original state was normal policy; our override is proven owned; no uncertain override call | Record cleanup intent, then issue `am memory-limiter manual <same-pid> none` once and verify actual status |
| Same instance with a pre-existing override | Restore only its exact recorded original value/unit through the reviewed syntax, or stop for manual review if ownership/restoration is unproven |
| Original instance died | Record dead-instance cleanup disposition; do not act on its reused PID or a newly started AI |
| Main/boot/process identity changed, active or uncertain execution, stale/ambiguous override state | No destructive cleanup; preserve UNKNOWN/manual-review evidence |
| Cleanup timed out or its result is missing | Preserve cleanup uncertainty; no automatic second write, no global reset |

Never use `ignore none` as cleanup: it changes global state and can undo someone
else's setting. Do not uninstall/clear app data, kill/restart processes, unload a
foreign turn, delete a model or reset restart counters to finish the test.

## Review and qualification boundary

The retained minimized packet binds the candidate/target, selected unit/syntax,
live witnesses, durable stimulus intent/result, paired exit correlation, actual
policy/refusal observations, explicit gaps and cleanup. Store no raw description,
user content, opaque session token or arbitrary stderr. Keep instrumented
foreground measurements distinct from passive/background/autonomy evidence.

Review every missing or contradictory fact as unproven. The unchanged
[offline validator](../../scripts/android/f5_limiter_evidence_protocol.py) can
check a representable v1 input, but a zero exit remains UNVERIFIED_INPUT /
NOT_QUALIFIED. Procedure completion supplies no physical incident, calibrated
budget, real 24-hour duration, F4/F5 approval or F6 authorization. Required target
execution and the [F5 exit review](F5-RuntimeGate.md) remain open.
