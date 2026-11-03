package network.orion.app

import network.orion.core.health.CoreReadiness
import network.orion.core.observability.OrionLogEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoreReadyLogEventTest {
    @Test fun `degraded safe startup retains degraded diagnostics`() {
        assertEquals(OrionLogEvent.CORE_READY, readyLogEvent(CoreReadiness.READY))
        assertEquals(OrionLogEvent.CORE_DEGRADED, readyLogEvent(CoreReadiness.DEGRADED_SAFE))
    }
    @Test fun `unfinished or blocked recovery cannot emit a ready event`() {
        for (readiness in listOf(CoreReadiness.STARTING, CoreReadiness.RECOVERING, CoreReadiness.BLOCKED)) {
            assertThrows(IllegalStateException::class.java) { readyLogEvent(readiness) }
        }
    }
}
