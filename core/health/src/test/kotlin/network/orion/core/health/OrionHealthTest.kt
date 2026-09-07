package network.orion.core.health

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionHealthTest {
    private val observedAt = Instant.parse("2026-09-07T12:00:00Z")

    @Test
    fun `startup barrier remains closed while core starts or recovers`() {
        assertFalse(CoreReadiness.STARTING.isStartupBarrierOpen)
        assertFalse(CoreReadiness.RECOVERING.isStartupBarrierOpen)
    }

    @Test
    fun `startup barrier can open only after a terminal core health state`() {
        assertTrue(CoreReadiness.READY.isStartupBarrierOpen)
        assertTrue(CoreReadiness.DEGRADED.isStartupBarrierOpen)
    }

    @Test
    fun `health snapshot accepts only the status matching its readiness`() {
        OrionHealth(CoreReadiness.STARTING, OrionHealthStatus.UNAVAILABLE, observedAt)
        OrionHealth(CoreReadiness.RECOVERING, OrionHealthStatus.UNAVAILABLE, observedAt)
        OrionHealth(CoreReadiness.READY, OrionHealthStatus.HEALTHY, observedAt)
        OrionHealth(CoreReadiness.DEGRADED, OrionHealthStatus.DEGRADED, observedAt)
    }

    @Test
    fun `health snapshot rejects claims that contradict readiness`() {
        assertThrows(IllegalArgumentException::class.java) {
            OrionHealth(CoreReadiness.RECOVERING, OrionHealthStatus.HEALTHY, observedAt)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrionHealth(CoreReadiness.DEGRADED, OrionHealthStatus.HEALTHY, observedAt)
        }
    }
}
