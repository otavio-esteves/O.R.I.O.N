package network.orion.core.observability

import java.time.Instant
import java.util.UUID
import network.orion.core.time.WallClock
import org.junit.Assert.*
import org.junit.Test

class OrionLoggerTest {
    @Test fun `structured record uses injected wall clock and static event vocabulary`() {
        val instant = Instant.parse("2026-11-03T12:00:00Z")
        val id = UUID(0, 1)
        val records = mutableListOf<OrionLogRecord>()
        val logger = OrionLogger(WallClock { instant }, OrionLogSink { records.add(it) })
        logger.record(id, OrionLogComponent.BOOTSTRAP, OrionLogEvent.STARTING, OrionLogSeverity.INFO)
        assertEquals(OrionLogRecord(id, OrionLogComponent.BOOTSTRAP, OrionLogEvent.STARTING,
            OrionLogSeverity.INFO, instant), records.single())
        assertFalse(records.single().toString().contains("payload"))
    }
}
