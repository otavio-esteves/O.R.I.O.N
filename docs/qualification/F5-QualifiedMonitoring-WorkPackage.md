# Passive monitoring of the qualification package — #165

OBJECTIVE: Bind the existing passive resource/exit and maintenance-history collectors to one explicitly selected application, including the separate non-debuggable `network.orion.qualification` APK used by F5.

FILES ALLOWED: `record-soak-observation.py`, `record-maintenance-history.py`, their focused harnesses, directly dependent compatibility controls and these narrow procedure notes. No APP, schema, policy, process, IPC, permission, profile or maintenance-producer changes.

CONTRACTS / INVARIANTS: Existing passive sampled-observation and maintenance v1 records, ADR-0001, qualification application identity. INV-001/005/006/011/012/019/027/028/032/035/036. Package selection identifies an observation target; it grants no admission or authority.

TESTS: Qualification-only and coexistence targets; exact selected APK/UID/PID/process namespace; wrong artifact/UID changes; literal package matching and injection rejection; inaccessible private storage; maintenance dedup/time/gap/interruption and legacy main-package controls. Canonical Docker HOST suites, followed by separately coordinated read-only Android mechanics.

ACCEPTANCE: Neither collector silently falls back from qualification to the principal package. Target-specific commands and metadata use the same selection. Unknown/OEM-incompatible/private data remains unavailable; only actual same-UID technical records provide queue, retention and database-size histories. No soak/physical/profile/F5/F6 approval follows collection.

OUT OF SCOPE: Installing/starting apps, generating work/maintenance/recovery, triggering process death, model/profile values, changing debug flags/permissions, bypassing `run-as`, raw dumps/content/traces, new diagnostic channels or real 24h qualification.

## Selection and binding

Both CLIs accept `--package network.orion` or `--package network.orion.qualification`. The old default remains `network.orion`; existing Python callers retain their default behavior and may pass the new keyword-only `package` argument explicitly. Other values, test-package names, prefix lookalikes and shell fragments fail before device queries. No global package variable is changed between observations.

The same selection is used for `pm path`, installed base-APK SHA-256, exact package UID lookup, `pidof`, selected process memory, `run-as`, retained exit-info and UID-filtered logcat. Reports and samples identify the selected package; endpoint metadata records selected APK hashes and UIDs. UID matching uses an escaped exact package line, so coexistence with the principal/test package cannot select its UID. A changed/unavailable UID cannot advance the maintenance cursor. Resource samples spanning a UID change discard target-specific metrics and retain an explicit identity gap.

The pure `memory(output)` helper remains compatible. Selected snapshots additionally require one exact Android MEMINFO header matching both the observed PID and selected package. A foreign PID/package, prefix collision, multiple headers or unknown format becomes unavailable. Exit-history rows likewise require the literal selected process namespace. Retained exits can include pre-run/older records and are not counts of newly correlated deaths or MemoryLimiter qualification.

The collectors trust the Android package manager and logcat's fixed `--uid` filter; these observations are not tamper-proof attestation. APK/device hashes are checked at endpoints, not continuously. UID checks bracket each sampled target read, but no observations between polls are inferred.

## Existing producer and honest private-storage limits

MAIN already publishes the versioned maintenance line from `OrionFoundationRuntime.runMaintenance`: `Log.i(OrionMaintenanceSnapshot.LOG_TAG, readMaintenanceSnapshot(...).toLogLine())`. It has no BuildConfig.DEBUG gate. The collector only reads this existing same-UID `orion_maintenance` log; it does not call the producer or schedule a pass.

The twenty existing metric names and all maintenance v1 record fields remain unchanged. Actual records supply action/Event/Ingress/reminder totals, pruning/retention batch counts and `databaseBytes`. Baseline records are not new progress; an empty/missing buffer supplies no queue or database-growth values. Bounded logcat history may omit passes. A sampled heartbeat-origin record does not prove autonomous scheduling or successful delivery.

`run-as network.orion.qualification` normally refuses access because the qualification APK is non-debuggable. The resource collector retains `privateStorage` as unavailable and its report remains incomplete; it does not substitute zeroes, query the principal app, change debug flags or bypass permissions. `databaseBytes` and retention/queue histories can still be read only when the existing technical maintenance records are actually present. Their absence is preserved.

## Concrete passive commands

Build/install the reviewed qualification APK separately and provide its exact local path. The target must already be available; these commands never launch/recover the app or generate maintenance. Set duration/interval from the reviewed collection plan. Use fresh directories and an honest environment label (`ANDROID_EMULATOR` for mechanical emulator evidence, `PHYSICAL_DEVICE` only for a real device):

```sh
python3 -B scripts/android/record-soak-observation.py \
  --adb "$ORION_F5_ADB" --serial "$ORION_F5_SERIAL" \
  --package network.orion.qualification --environment "$ORION_F5_ENVIRONMENT" \
  --apk "$ORION_F5_QUALIFICATION_APK" \
  --duration-seconds "$ORION_F5_MONITOR_DURATION_SECONDS" \
  --interval-seconds "$ORION_F5_MONITOR_INTERVAL_SECONDS" \
  --evidence-dir "$ORION_F5_NEW_RESOURCE_EVIDENCE"

python3 -B scripts/android/record-maintenance-history.py \
  --adb "$ORION_F5_ADB" --serial "$ORION_F5_SERIAL" \
  --package network.orion.qualification --environment "$ORION_F5_ENVIRONMENT" \
  --apk "$ORION_F5_QUALIFICATION_APK" \
  --duration-seconds "$ORION_F5_MONITOR_DURATION_SECONDS" \
  --interval-seconds "$ORION_F5_MONITOR_INTERVAL_SECONDS" \
  --evidence-dir "$ORION_F5_NEW_MAINTENANCE_EVIDENCE"
```

An exit code indicating incomplete resource collection is expected when private storage or another metric is unavailable. Preserve reports and gaps; do not repeat work, trigger a maintenance pass or relax identity checks to manufacture data. All existing cadence, BOOTTIME/suspend, interruption and history dedup semantics remain in force. Polling affects CPU/idle/battery, and collection completion is never qualification or continuous coverage.

VALIDATION: Tests were added before collector edits. Qualification selection REDs failed in canonical Docker because the old APIs could not select a package. The first focused runs passed 35 resource and 13 maintenance tests. After UID-bracketing controls, the full canonical Docker HOST suite passed **629 tests in 46.260s**, EXIT0 with no failures/errors/skips; log `/tmp/orion-165-full-host-20261116.log`, frozen source/test hashes `/tmp/orion-165-full-freeze-20261116.json`. These are DOCKER/HOST synthetic fixtures, not Android or physical evidence. Read-only target mechanics remain separately coordinated; no APP/Gradle/ADB action was performed by this implementation.
