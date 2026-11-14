# F5 memory evidence — current source review and qualification protocol

Work package: [ORION-F5-MEMORY-QUAL-001](../issues/ORION-F5-MEMORY-QUAL-001.md).
Reviewed baseline: `3f244d1fcb6abf33b01c38b194434c793404b4d1`.
Source/API review date: 2026-11-14. This is a source and documentation review,
not executed Android or physical qualification. The overall F5 gate remains in
[F5-RuntimeGate.md](F5-RuntimeGate.md). Reviewed source identities and validation
scope are retained in [f5-memory-source-review.json](f5-memory-source-review.json).

## Existing evidence path

The current implementation differs from the historical pre-integration branch:

| Condition | Current mechanism | Scope and refusal |
| --- | --- | --- |
| Complete configured process inventory | `OrionCortexRuntimeOwner.discoverInventory` reads application, activities, services, receivers and providers, including disabled components; `supportedCortexInventory` bounds the scan and requires exactly MAIN+AI | Additional/default/foreign/unknown process configurations fail closed. This is a trusted manifest inventory, not a claim that an ActivityManager process list is exhaustive. |
| Authenticated AI reading | `OrionCortexClient.currentMemoryTelemetry` selects the bound private AIDL endpoint and connection, validates receipt name/PID, pins the PID and rechecks connection ownership | No second bind is created by sampling. Missing or superseded connection/invalid receipt produces no usable reading. |
| Self-owned AI measurement | Service `healthCheck` timestamps before `Debug.getMemoryInfo` and returns its own PID, process name, boot count and total PSS | The service supplies a reading, never application-inventory authority or resource approval. |
| Independently sampled MAIN | `OrionAndroidResourceObserver` timestamps before platform reads, collects MAIN PSS and obtains auxiliary evidence | MAIN owns observation and remains the policy/data owner. The auxiliary receipt cannot replace a missing MAIN sample. |
| Fresh complete aggregate | `aggregateProcessMemory` requires a same-boot, nonfuture, unexpired inventory and each reading, explicit native inclusion, every inventory member exactly once, nonzero values and overflow-safe addition | It does not fill a missing process with zero. The current adapter supports MAIN+AI only; future voice/session processes need their own evidence before admission. |
| Fresh decision after collection | Observer aggregation uses a clock reading after the receipt; `OrionMinimumResourceGovernor` checks observation age again after the observer returns | The observation's timestamp precedes collection, so slow system/exit-history reads cannot be relabelled as fresh at completion. Exact freshness-boundary samples are refused. |

The client performs a guarded lightweight bind before requesting a current AI
measurement. Model load/inference still requires fresh ResourceGovernor approval.
This avoids inventing a zero-byte absent-AI reading or claiming that measurement
alone authorizes heavy work. Production qualified plans remain empty.

## What Android documents

`ActivityManager.getProcessMemoryInfo` is a debugging/process-UI interface. Android
Q and later limit regular apps to same-UID processes and may return the previous
sample when calls exceed its rate limit. `getRunningAppProcesses` also has a
debugging/process-UI scope. Neither is used here to manufacture a fresh complete
inventory or aggregate. [Official ActivityManager reference](https://developer.android.com/reference/android/app/ActivityManager#getProcessMemoryInfo(int%5B%5D)).

`Debug.getMemoryInfo` directly measures the calling process and separates managed,
native and other usage. Its public documentation warns that protected allocations,
such as some graphics memory, may be unavailable. `Debug.getRss`, available from
API35, is also self-process information and does not establish peer inventory.
Those APIs do not by themselves prove all resource-accounting assumptions on the
target build. [Official Debug reference](https://developer.android.com/reference/android/os/Debug#getMemoryInfo(android.os.Debug.MemoryInfo)),
[self RSS reference](https://developer.android.com/reference/android/os/Debug#getRss()).

The current admission path uses **PSS bytes**, not Java heap usage, RSS-plus-swap
or model file size. `nativeIncluded` states the current accounting contract; its
presence is not physical proof of complete coverage. A qualification report must
identify the metric, accessible allocation classes, sampling duration and any
unbounded omission. An unqualified omission cannot be converted into complete
coverage by setting that boolean, changing the timestamp or increasing a cap.

Inference from the documented limits: same-process measurements plus trusted
manifest/connection ownership avoid the known cross-process cache/inventory
problems, but source inspection alone does not establish complete physical
allocation coverage, a safe memory budget or a hardware performance profile.
No new source, IPC contract or architectural exception is authorized by this review.

## Software cases reviewed

These existing cases prove their contract scopes when executed; this package did
not rerun them and does not assign new runtime results to historical artifacts.

| Source | Assertions relevant to memory |
| --- | --- |
| `OrionProcessMemoryEvidenceTest` | MAIN/AI/extra process accounting, missing coverage, duplicate or missing members, overflow, bounded inventory/readings, exact stale/future/boot boundaries |
| `OrionCortexMemoryTelemetryTest` | Fresh authenticated receipt data, schema/status/name/PID/native/PSS constraints, immutable snapshots and exactly supported inventory |
| `OrionCortexMemoryTelemetryAndroidTest` | Real Bundle/Parcel schema/types/size validation; a receipt cannot attest complete application inventory |
| `OrionCortexRuntimeOwnerPolicyTest` | All declared process names/defaults, unknown/extra/foreign inventories, bounded manifest traversal and profile binding |
| `OrionCortexOwnerDefaultTest` | Empty production plans, closed owner, Core gate and invalid/expired time reject before database/inventory/Binder work |
| Resource governor/restart tests | Observation staleness, process+aggregate+system budget checks, resource-exit backoff and failure without permissive defaults |

No reproduced implementation defect was identified in this scoped review. The
review does not claim absence of defects beyond the inspected path or close a
physical, native or long-running gate.

## Physical measurement protocol

1. Freeze the exact source, APK/test-APK hashes and selected model identity. Record
   `PHYSICAL_DEVICE`, explicit serial, S21 RAM/build/API/ABI/page size, application
   identity, boot identity, MAIN/AI PIDs and qualification-plan provenance. A fixture
   plan is labelled as a fixture; it never becomes a production-approved plan.
2. Capture the tested APK's complete declared process inventory. Include disabled
   components and inherited defaults. With this adapter, any configured process
   beyond MAIN+AI must produce a refused admission. Do not remove components or
   substitute a smaller manifest and call it the original artifact's qualification.
3. Record sampling start/end and same-boot elapsed timestamps for MAIN and AI,
   receipt round-trip time and the final governor decision. Exercise collection
   faster than, at and slower than the configured freshness window. Retain denied,
   missing, zero, future and stale samples; never retimestamp an old reading.
4. Collect independent host-side allocation observations for the same recorded
   process identities and bounded windows during unloaded, model loading, prefill,
   decode, unload and post-unload states. Identify metric differences and source
   caching explicitly. A delayed ActivityManager sample or postmortem exit PSS/RSS
   is not a simultaneous ground-truth snapshot. Do not silently mix PSS and RSS.
5. Exercise managed/native allocations, model mappings and an active MAIN UI with
   graphics. Determine which allocation classes the self-PSS path can observe on
   that build. Preserve source/host discrepancies and their observation windows.
   If protected or other app-owned usage remains unbounded/unaccounted, the source
   is not qualified as complete and heavy admission stays blocked; this document
   grants no reserve-based waiver or weaker `nativeIncluded` interpretation.
6. Observe actual AI death, explicit guarded rebind, MAIN recovery, reboot and
   actual target-build memory/resource pressure. The receipt must belong to the
   current private endpoint; changed or missing identity/time/coverage must refuse
   admission. A synthetic exit reason does not qualify MemoryLimiter behavior.
7. Calibrate measured per-process, aggregate and system headroom, model/context/
   thread/token settings and keep-alive only after coverage/freshness is established.
   Keep latency, thermal and battery results distinct from Android API compatibility.
   A profile must identify its measurement metric and all tested limits.

Acceptance requires review of complete retained measurements and discrepancies,
not a matching sample or successful inference demo. Root/ADB/dumpsys/profiling may
collect qualification evidence on the host; they are not runtime APK dependencies
or a production permission model. The complete physical matrix and real-duration
soak remain separate work under the existing F5 qualification plan.

## Current disposition

No physical device was used for this review. No production source, IPC, schema,
profile or readiness state changed. Native/graphics coverage, measured model
budgets and actual target-build behavior remain qualification obligations. No
structural change is currently proposed; an alternative that changes IPC semantics,
process ownership or resource-authority boundaries needs its own ADR first.
