# Passive maintenance and queue history

Work package: [ORION-F4-SOAK-003](../issues/ORION-F4-SOAK-003.md).
Runtime protocol: [F4 observability](F4-Observability.md).

`record-maintenance-history.py` reads only the matching app UID's
`orion_maintenance` tag through host ADB. It never starts MAIN, opens Room, exports
raw logs, changes settings, forces jobs or revives a stopped package. Device/APK/UID
identity is checked at both endpoints; a replacement-and-return between those
checks is not ruled out. Duration and cadence use the host monotonic clock.

```bash
python3 scripts/android/record-maintenance-history.py \
  --adb /path/to/platform-tools/adb --serial DEVICE_SERIAL \
  --environment PHYSICAL_DEVICE --apk /path/to/matching-app-debug.apk \
  --duration-seconds 86400 --interval-seconds 60 \
  --evidence-dir /path/to/new-maintenance-history
```

Run only within an arranged device window. A short protocol check can use ten
seconds with a five-second interval. The controlled workload recorder can collect
the same history alongside its existing passive metrics with `--maintenance-history`.
Both collectors finish before endpoint verification starts instrumentation. Missing
history or observation gaps keep that integrated workload incomplete. Old APKs
without schema-v1 telemetry do not supply queue counts or heartbeat proof.

The collector accepts only the closed schema, numeric bounds and consistent totals.
Unknown fields, duplicate JSON keys, malformed records and snapshot-unavailable
markers are gaps, not zero counts. Observations are deduplicated by generated UUID;
conflicting content for an existing UUID is rejected without advancing the cursor.
Sequence loss or monotonic regression within a boot/PID remains observable. Wall
rollback is allowed; elapsed readings from different boots are never compared.

The initial buffer is explicitly baseline history. Later events are labelled only
as first seen after baseline, not independently proven event time. A heartbeat-origin
pass is actual worker execution evidence from the matching APK, but this collector
cannot establish whether a separate host forced that job. It never equates heartbeat
registration, event count or elapsed duration with autonomous successful delivery.
Queue/UNKNOWN totals, pruning-batch counts and storage sizes are sampled; no continuous
coverage or absence of intermediate growth is inferred. Pruning counts cover the
explicit maintenance-policy batch, not every recovery cleanup.

Reports always keep `continuousCoverageProven`, `heartbeatAutonomyProven`,
`fullSoakGateClosed` and `fullF4GateClosed` false. A complete bounded collection is
`HISTORY_COLLECTION_COMPLETE_REVIEW_REQUIRED`; review still needs the full approved
workload, reboot/unlock and delivery proofs, crashes/ANRs, battery/thermal/retention
analysis and the required real duration. Polling affects idle/battery behavior.

Bounds: seven days, 20,000 polls/events, 512 records per poll, 4 KiB per record and
1 MiB per command. Only validated technical records enter JSONL; raw output and error
text are never persisted. Interruptions preserve flushed samples and an incomplete
summary. Sequence/transport/cadence/identity gaps remain retained even if subsequent
polls recover.

Validation: 123 Docker host contracts passed, including schema/privacy, duplicate
and conflicting delivery, boot/time changes, missing history, interruption,
integration ordering and restoration after failed collection. An actual ten-second
API35/x86_64 collection completed with three polls, no sequence/metric/cadence gaps
and matching identity. No subsequent heartbeat was observed in that short interval;
the collector reported zero first-seen heartbeat passes without inventing empty
queue measurements. See the [scoped evidence](f4-maintenance-history-evidence.json).

An integrated emulator attempt refused a genuinely absent screen-timeout setting
before fixture preparation. Its incomplete report is retained. Recovery journal v2
now distinguishes that observed absence from an unavailable policy, restores it by
deleting the setting and verifies absence; v1 numeric journals remain supported.
No effective timeout value or other unknown policy is guessed.
