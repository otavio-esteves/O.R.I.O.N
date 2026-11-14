# Optional MAIN generation metrics reader

[Work package](../issues/ORION-F5-METRICS-CLIENT-001.md), implementing only the
MAIN reader of accepted [ADR-0003](../adr/ADR-0003-optional-cortex-generation-metrics.md).
API model/codec and service/native adapter are independently owned dependencies.

`OrionCortexClient.generationMetrics(logicalRequestId)` is one explicit manual
observation. It never acquires/binds, calls health, reads clocks, evaluates a
governor, starts native work, waits for completion, polls or retries. It returns
an immutable typed snapshot or null. Optional discovery is lazy, after a current
connection has passed the unchanged mandatory major/history negotiation and
negotiated minor >=2. The first eligible manual call performs at most one discovery
and one query; subsequent manual calls may query once using that connection's
cached support mask. Unsupported/malformed/throwing discovery stays unavailable
until another connection; concurrent readers cannot claim another discovery.
Mask zero is valid wire support, not complete measurement availability.

There is one latest inference mapping, never another request history. It records
the actual service-issued transport ID, actual admitted maxTokens/context and the
current service/ServiceConnection/independently advancing connection epoch. Load,
ID reservation and local replay do not install a mapping. The actual next start
attempt clears the previous mapping; only an ACCEPTED start can install its own.
A replay of an older logical ID cannot retarget to another transport ID. Metrics
transactions never treat an unavailable/retired ID as permission to re-execute.

Correlation additionally requires a new valid receipt from the existing
`currentMemoryTelemetry` call inside that inference's original synchronous
resource callback, on that callback's thread. A concurrently obtained receipt or
the old load-stage PID pin does not supply this witness. The already-computed
resource decision remains the same object and only evaluation. Its known boot
and sample time must agree with the receipt and the original request's active
deadline window. No new health/clock call seeks a missing identity. Missing,
regressed or changed identity keeps correlation unavailable; the collector does
not change resource authority. Bad optional receipt projection leaves the
original health response and inference behavior unchanged.

Before and after the optional RPCs the existing monitor checks close, the actual
endpoint/epoch, expected PID/boot and the same mapping. No monitor is held across
discovery/query. A delayed reply from a disconnected/closed/replaced endpoint or
superseded mapping is discarded. The codec applies the actual admitted limits,
not query-supplied/global substitutes. Reported service PID must match the known
admission identity; a KNOWN_BOOT snapshot must also match that boot. Its capture
and optional wrapper-start time cannot precede the actual admission witness. No
upper deadline bound is invented for later native/cleanup completion. UNAVAILABLE
or DISCONTINUOUS time stays unknown. Optional RPC/decode faults return null and
never call lostConnection, unbind, retry or alter inference terminality. Optional
allocation/projection/publication and manual discovery/query contain Throwable
locally; helper faults clear correlation. The original resource callback and its
exceptions remain outside this diagnostic boundary.

This is admission-time identity evidence, not a new resource-freshness observation
at manual query time. Retained generation snapshots are not retimestamped. No
logical terminal becomes JNI completion, slot release or FINAL automatically.
The initial service adapter may expose timeState=UNAVAILABLE; the reader does not
invent wrapper clocks, capture time or boot fields to repair it. No snapshot is
persisted/exported by this package and correlation metadata is not printed.

Fifteen focused pure JVM and twelve Android local-Stub tests are provided for
root-controlled execution, plus mechanical unsupported implementations of the
appended methods in the existing client fixture. They are synthetic contract
fixtures, with no real AI process/native/model or physical result. The author ran
no Gradle/Docker/ADB here. Dependency AIDL transactions 11/12 and CURRENT minor 2
are supplied by the root integration. Production plans stay empty; this feature
approves no model/profile/admission, F4/F5/24-hour gate or F6 work.
