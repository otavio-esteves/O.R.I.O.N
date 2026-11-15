# One physical single-attempt HOST capture

[Work package](../issues/ORION-F5-SINGLE-ATTEMPT-HOST-001.md), issue #130.
This recorder executes the qualification-only framework runner #128 exactly once
and captures only minimized, validated technical output. It supplies no workload,
model, template, profile, budget or inference parameters. The current empty/null
trusted configuration produces PREPARE_REFUSED / NO_QUALIFIED_PROFILE, not a cycle.

## Preparation and CLI

Use Linux HOST ADB and an explicit physical serial. Supported target checks are
S21 (Samsung SM-G991 family) or API37 (physical API37); arm64 and supported page size
are mandatory. Emulators, fake labels and ambiguous physical signals are refused
before instrumentation. An offline parser may inspect emulator transcripts as
software evidence, but the recorder cannot execute them or relabel them physical.
No S21 was available or measured during this implementation.

The separate qualification app/test must already be installed and built with the
reviewed #128 source mode. Installation and provisioning are outside this tool.
Freeze all tracked source files and the exact app/test APK hashes using the same
source-manifest contract as the baseline recorder. Artifacts must be exactly
`app` and `test`, with no model entry or CLI switch that weakens the original
three-artifact physical preflight. A manifest has this shape (replace placeholders
and include every tracked file; this abbreviated example is not a valid freeze):

```json
{
  "schemaVersion": 1,
  "sourceCommit": "FULL_REVIEWED_40_HEX_COMMIT",
  "files": {"ALL_TRACKED_FILES": "EACH_EXACT_SHA256"},
  "artifacts": {"app": "EXACT_APP_APK_SHA256", "test": "EXACT_TEST_APK_SHA256"}
}
```

Keep the source tree clean and put the manifest, APKs and a new evidence directory
outside it. Source-manifest binding is not build attestation or authentication.

```sh
python3 scripts/android/record-f5-single-attempt.py \
  --adb /absolute/path/to/adb \
  --serial EXPLICIT_PHYSICAL_SERIAL --target S21 \
  --source-root /absolute/path/to/frozen-source \
  --source-manifest /absolute/path/to/source-manifest.json \
  --app-apk /absolute/path/to/qualification-app.apk \
  --test-apk /absolute/path/to/qualification-test.apk \
  --evidence-dir /absolute/path/to/new-evidence
```

There are no model, permissions, profile, approval, timeout, template or runner
configuration arguments. The fixed endpoint is
`network.orion.qualification.test/network.orion.app.compatibility.OrionCortexSingleAttemptInstrumentation`;
its command has only `am instrument -w -r` and the endpoint, with no `-e` inputs.
The ADB adapter also rejects a second invocation on the same instance. It never
installs, grants, changes settings, pushes/imports files, kills Android processes,
force-stops, retries or performs device cleanup. App/instrumentation bootstrap may
write its own technical DB/Keystore state, so the recorder is not wholly read-only.

## Identity and time

The recorder reuses existing source-identity, physical-identity, installed-hash and
Android boot/uptime-window helpers without modifying them. Local source and app/test
hashes and installed app/test hashes are checked before and after; every ADB query
uses the explicit same serial. Only hashes/closed target metadata are persisted,
not raw serials, APK paths, source paths or unvalidated hardware labels.

`HostClock.linux()` must provide CLOCK_BOOTTIME with HOST boot identity. There is no
synthetic/fallback clock in the CLI. Injected clocks are refused; HOST fixtures
mock the platform factory and never establish physical origin. HOST boot change,
clock rollback, Android boot change or regressed uptime invalidates capture.
The recorded program span includes ADB transport/wait/suspend, not native inference
latency. The fixed 180-second local transport watchdog is not a chosen domain
request duration, profile/calibration value or proof of continuous observation.
A span/end-window gap beyond it is incomplete. No span proves 24 hours or autonomy.

Timeout/interruption, broken transport or missing terminal framing leaves invocation
outcome UNKNOWN. Ending the local ADB subprocess does not prove the Android operation
stopped. No second attempt, cleanup or generic replay follows. The tool does not
poll for late completion or dispatch separate observe/release commands.

## Exact runner framing and minimized storage

Only final INSTRUMENTATION_RESULT keys are accepted, followed by one terminal
INSTRUMENTATION_CODE. Bundle key order and the platform's multiline stream rendering
are permitted; duplicate, extra, status, surplus or missing frames are rejected.

- Primary `orionSingleAttemptStatus` is the original phase header, schema1, at most
  1024 UTF-8 bytes. Normal headers use CORTEX_SINGLE_ATTEMPT_STATUS and
  counterScope DRIVER_METHOD_CALLS. Counters are method calls, not native executions.
- `orionSingleAttemptPartial`, at most 8192 UTF-8 bytes, is permitted only paired with
  the original OBSERVED/partialPresent=true header. It must match the closed #125
  partial format. Missing/unknown/lost values remain missing/unknown/lost.
- `orionSingleAttemptReleaseStatus` is a separate technical control. It never replaces
  OBSERVED or borrows its partial. It reports no app/model cleanup authority.
- NoProfile prepare refusal has counts 1/0/0/0, ownerCallInvoked=null and no partial,
  read or release. EXECUTE_NOT_ATTEMPTED is distinct, with the actual driver refusal.
- CORTEX_SINGLE_ATTEMPT_RUNNER_FAILURE has its own closed keys, unknown owner-call
  state, no fabricated counters/refusal/no-attempt and no partial. A separate release
  control may be present; it is not a replacement outcome.

Streams are exactly DRIVER_NOT_ATTEMPTED / NOT_QUALIFIED for pre-run refusal,
SINGLE_ATTEMPT_PARTIAL / NOT_QUALIFIED for observation, and
SINGLE_ATTEMPT_UNAVAILABLE / NOT_QUALIFIED for runner failure. RESULT_OK (-1) denotes
an observed program, including a refusal; RESULT_CANCELED (0) denotes runner failure.
Neither means successful heavy work or qualification.

The textual keys/values bound is 10 KiB. HOST does not measure Parcel; producer #128
independently checks its actual 24 KiB Parcel bound. Parsing rejects unknown keys,
duplicate JSON keys, wrong types/enums, authority flags true, invalid phase/count/
partial relations, nonfinite/overflow values and oversized content. Native intrinsic
relations are checked only when operands exist. Admitted request/model bounds are
not recoverable from this stripped projection. Stream/loss counters are independent
lower bounds; the parser never turns missing writes into zero or compares them as
complete counts. This is capture parsing, not a new standalone evidence qualifier.

A new owned directory receives `f5-single-attempt.json`. It contains only validated
closed content and technical metadata. Unknown stdout, stderr, exception strings,
caller identifiers and user content are never printed or saved to the report.
No raw-transcript hash is retained. The transport drains a nonblocking HOST pipe with a 64 KiB bound enforced while
reading (at most one surplus byte detects overflow), and discards stderr. It uses
CLOCK_BOOTTIME/boot checks for its deadline, including a fresh check before spawn.
On overflow, deadline, clock failure or interruption it ends only the local Popen
ADB client that it created, never a process group, server or Android process. The
Android outcome remains unknown. Failures serialize only a fixed technical code.

COMPLETE means capture integrity only; an incomplete underlying source can appear
inside a COMPLETE captured PARTIAL. Valid runner failure is INCOMPLETE with UNKNOWN
outcome. Malformed/conflicting output or identity is REJECTED. All authentication,
physical/profile/production/full-runtime-v1/F5/soak/F6 gates remain false, with
qualification NOT_QUALIFIED. CLI exit 0 denotes COMPLETE capture only; other captures
exit 2. There is no heavyWorkIssued=false blanket claim for future invoked workloads.

## Model and recovery boundary

Source/APK/window binding is not proof of the selected READY model. #125 deliberately
omits that identity. Physical provisioning must separately use the real MAIN importer/
registry in `network.orion.qualification`, never copy a production database/READY row,
use emulator ADB-root staging as an S21 procedure, or treat the old private-file
preflight as registry proof. This package provides no provisioning path.

Process death loses the HOST collection, not an app execution lease or idempotency
record. An absent/incomplete report is never permission to replay the invocation.
No app schema, migration, persisted domain state, IPC, owner lifetime or recovery
strategy changes. No hardware/soak/F4/F5 gate or F6 entry is established.

The author performed syntax/whitespace inspection only. Focused pure HOST fixtures
are supplied for canonical Docker execution by root; no ADB/device workload was run.
