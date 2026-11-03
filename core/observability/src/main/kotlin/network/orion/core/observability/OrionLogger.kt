package network.orion.core.observability

import java.time.Instant
import java.util.UUID
import network.orion.core.time.WallClock

// Closed vocabularies intentionally exclude arbitrary messages, exceptions and payloads.
enum class OrionLogComponent { BOOTSTRAP, STORAGE, RECOVERY }
enum class OrionLogEvent { STARTING, STORAGE_READY, CORE_READY, CORE_DEGRADED, CORE_BLOCKED }
enum class OrionLogSeverity { INFO, WARN, ERROR }

data class OrionLogRecord(
    val correlationId: UUID,
    val component: OrionLogComponent,
    val event: OrionLogEvent,
    val severity: OrionLogSeverity,
    val timestamp: Instant,
)

fun interface OrionLogSink { fun write(record: OrionLogRecord) }

class OrionLogger(private val clock: WallClock, private val sink: OrionLogSink) {
    fun record(id: UUID, component: OrionLogComponent, event: OrionLogEvent, severity: OrionLogSeverity) {
        sink.write(OrionLogRecord(id, component, event, severity, clock.wallNow()))
    }
}
