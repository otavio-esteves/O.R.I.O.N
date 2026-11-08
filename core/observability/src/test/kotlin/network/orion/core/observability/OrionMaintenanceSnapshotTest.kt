package network.orion.core.observability

import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class OrionMaintenanceSnapshotTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private fun metrics() = OrionMaintenanceMetric.entries.associateWith { 0L }.toMutableMap()

    @Test fun `only an exact unique worker identity proves heartbeat origin`() {
        assertEquals(OrionMaintenanceOrigin.HEARTBEAT, OrionMaintenanceOrigin.forWorker(id, setOf(id), emptySet()))
        assertEquals(OrionMaintenanceOrigin.RECONCILIATION, OrionMaintenanceOrigin.forWorker(id, emptySet(), setOf(id)))
        assertEquals(OrionMaintenanceOrigin.WORKER_UNCLASSIFIED, OrionMaintenanceOrigin.forWorker(id, null, null))
        assertEquals(OrionMaintenanceOrigin.WORKER_UNCLASSIFIED, OrionMaintenanceOrigin.forWorker(id, setOf(id), setOf(id)))
        assertEquals(OrionMaintenanceOrigin.WORKER_UNCLASSIFIED, OrionMaintenanceOrigin.forWorker(id, emptySet(), emptySet()))
    }

    @Test fun `aggregate snapshot is bounded immutable and has no arbitrary payload slot`() {
        val values = metrics()
        val snapshot = OrionMaintenanceSnapshot(id, Instant.parse("2026-11-07T00:00:00Z"), 12, 123, 40,
            OrionMaintenanceOrigin.HEARTBEAT, true, values)
        values[OrionMaintenanceMetric.ACTION_UNKNOWN] = -1
        assertEquals(0L, snapshot.metrics[OrionMaintenanceMetric.ACTION_UNKNOWN])
        val line = snapshot.toLogLine()
        assertTrue(line.startsWith("{\"schemaVersion\":1,"))
        assertTrue(line.contains("\"origin\":\"HEARTBEAT\""))
        assertTrue(line.contains("\"actionUnknown\":0"))
        assertTrue(line.length < 4096)
        assertFalse(line.contains("arguments")); assertFalse(line.contains("ciphertext"))
        assertThrows(IllegalArgumentException::class.java) {
            OrionMaintenanceSnapshot(id, Instant.EPOCH, 12, 123, 40, OrionMaintenanceOrigin.SYSTEM_TRIGGER, false, values)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrionMaintenanceSnapshot(id, Instant.EPOCH, 12, 123, 40, OrionMaintenanceOrigin.SYSTEM_TRIGGER, false, emptyMap())
        }
    }
}
