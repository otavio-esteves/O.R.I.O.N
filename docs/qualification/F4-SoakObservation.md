# F4 passive soak observations

Work package: [ORION-F4-SOAK-001](../issues/ORION-F4-SOAK-001.md).
Architecture V3.2 §118 and Master Plan §31 require long-running recovery evidence.
This host tool prepares part of that evidence; no physical soak has been run by
this work package and no F4 gate is closed.

The collector records boot count, main PID, main-process PSS/RSS, battery fields,
aggregate private `databases`/`files` sizes and numeric reasons from Android's
retained exit-history snapshots. Missing/OEM-incompatible metrics remain explicit
gaps. It does not open Room or copy business content, filenames, exit descriptions,
traces or raw dumps. Device serial identity is hashed. The installed single base
APK must match the supplied artifact before and after collection; split installs
are explicitly unsupported. Identity checks at the endpoints do not prove that an
APK/device replacement and return never happened between observations.

No command installs, starts, kills or revives O.R.I.O.N., changes permissions, or
alters time, screen, idle or background policies. The normal Core continues to own
all business state and recovery. The collector should run on the Linux host, with
ADB already connected, after building and installing the chosen APK separately:

```bash
python3 scripts/android/record-soak-observation.py \
  --adb /path/to/Android/Sdk/platform-tools/adb \
  --serial DEVICE_SERIAL \
  --environment PHYSICAL_DEVICE \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --duration-seconds 86400 \
  --interval-seconds 60 \
  --evidence-dir /path/to/new-soak-evidence
```

The evidence directory must be new. Samples are appended and flushed to
`samples.jsonl`, followed by a summary and sample-file hash in
`soak-observation-report.json`. Ctrl-C preserves completed samples and an
`INCOMPLETE` report. Abrupt host/power termination may leave only flushed samples;
missing summaries must never be interpreted as success. Reruns use a new directory.
Commands time out after ten seconds; decoded output is capped at 1 MiB per command.
Collection is limited to seven days and 20,000 samples. Timing uses the host
monotonic clock; a start-to-start sampling gap exceeding twice the requested
interval prevents a complete observation result. Connection failures remain gaps,
even after the device reconnects.

`OBSERVATION_COMPLETE_REVIEW_REQUIRED` means only that bounded collection finished
without detected identity/metric/cadence gaps. It is never a soak PASS. Retained
exit histories can include pre-run exits, truncate older records and disappear
across reboot. Missing processes or changed PIDs alone do not prove crashes or
restart loops. ADB polling adds overhead and can affect idle/battery behavior;
these samples cannot qualify battery drain or undisturbed Doze behavior.

The full soak still requires controlled tasks/reminders/heartbeat/reboot/process
death scenarios and independent evidence for delivery/duplicates, queue and UNKNOWN
growth, wakelocks, thermal behavior, retention/pruning and storage/resource stress.
No read-only domain diagnostics API is introduced by this package.

Device-independent verification runs in a separate pinned Python container:

```bash
docker compose run --rm host-harness-check
```

That service has no network/device access, mounts source read-only and uses a
temporary writable filesystem for synthetic fixtures. It runs all host-harness
contracts, including a CLI test with a fake ADB executable. CI uses the same command.
The canonical Android image, Gradle graph, Room schema, runtime and APK are unchanged.

Validation in Docker passed 59 host contracts on this branch, including 24 observer
cases. A read-only integration copy combining the observer with the pending S21
recorder fixes passed all 70 host contracts. Compose configuration, workflow
`actionlint` and whitespace checks also passed. The
[validation record](f4-soak-observer-evidence.json) identifies source and container
identity. No Android runtime or 24-hour evidence is claimed by these checks.

A subsequent physical smoke check on the S21/API35 collected three samples over
11.6 seconds, with matching installed APK/device identity and no unavailable metrics
or cadence gaps. It exercised the actual OEM battery, main-process memory, private
directory size and retained exit-history parsers. After the wireless-reboot recorder
fix, the combined host contracts passed 73 cases. These short observations do not
qualify sustained recovery, delivery reliability or battery behavior; the full
24-hour soak remains open. Raw smoke observations and their hashes are referenced
in the validation record.

## Controlled workload component

[ORION-F4-QUAL-002](../issues/ORION-F4-QUAL-002.md) adds
`verify-f4-soak-runtime.py` and explicit instrumentation checkpoints. The installed
app and test APKs must match the supplied files. Preparation requires the target
unlocked: a real task, timer and each finite reminder receive their original exact
approval through the production gateways. The unattended interval observes actual
notifications before any endpoint verification and plans one UID-scoped MAIN
SIGKILL. It never opens the app to obtain autonomous recovery evidence.

For a short protocol check on an explicitly available dedicated target:

```bash
python3 scripts/android/verify-f4-soak-runtime.py \
  --adb /path/to/Android/Sdk/platform-tools/adb \
  --serial DEVICE_SERIAL --environment PHYSICAL_DEVICE --dedicated-target \
  --app-apk /path/to/app-debug.apk \
  --test-apk /path/to/app-debug-androidTest.apk \
  --duration-seconds 600 --reminder-interval-seconds 300 \
  --evidence-dir /path/to/new-short-workload
```

A separately arranged full-duration collection uses `--duration-seconds 86400`
and `--reminder-interval-seconds 3600` (24 finite reminders). Do not start it when
the device is available only a few hours. Even a completed duration keeps
`fullSoakProfileComplete`, `eligibleFor24HourReview`, `fullSoakGateClosed` and
`fullF4GateClosed` false: this component does not itself supply the entire approved
profile. Review still needs planned reboot/unlock with increased boot count and
independent delivery, actual heartbeat execution across checkpoints (registration
alone is insufficient), queue/UNKNOWN histories, crashes/ANRs, retention/growth,
resource observations and declared effects of collection on idle/battery behavior.

Endpoint Room reads restart MAIN and are explicitly marked
`instrumentationCheckpoint`; they are not passive backlog observations. Settings
are restored and only owned synthetic records are cleaned. If ADB disappears,
restoration cannot be assumed: preserve the failed report and complete scoped
cleanup after reconnection, recording that separately from qualification.

The first S21 one-hour attempt lost wireless ADB around its halfway checkpoint.
Three real notifications and 30 passive samples were retained, but the attempt is
`INCOMPLETE`; it did not prove another controlled death/return or a complete hour.
See the [closeout audit](F4-Closeout.md) for current results and remaining gates.

The first 10-minute follow-up independently proved two deliveries, SIGKILL/return
and endpoint integrity, but endpoint instrumentation raced the final observer
memory sample. The strict collector retained that unavailable metric and marked
the observation incomplete. A host regression then required observer completion
before any endpoint instrumentation. The corrected recorder passed all 98 host
contracts and a fresh physical 600-second workload with 11 gap-free samples and all
delivery/binding/task/timer/uncertainty checks passing. Both failed attempts remain
retained. All owned fixtures and temporary policies were restored. This scoped
short result supplies no 24-hour, battery-drain, continuous-backlog or full-profile
approval.

Parser references: Android's [dumpsys documentation](https://developer.android.com/tools/dumpsys)
and the AOSP [ApplicationExitInfo dump implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/core/java/android/app/ApplicationExitInfo.java).
