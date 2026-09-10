package network.orion.core.health

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreReadinessTest {
    @Test
    fun `readiness vocabulary matches architecture section 124`() {
        assertEquals(
            setOf("STARTING", "RECOVERING", "READY", "DEGRADED_SAFE", "BLOCKED"),
            CoreReadiness.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `only ready and safely degraded core are eligible for barrier release`() {
        val eligibility = mapOf(
            "STARTING" to false,
            "RECOVERING" to false,
            "READY" to true,
            "DEGRADED_SAFE" to true,
            "BLOCKED" to false,
        )
        eligibility.forEach { (name, eligible) ->
            assertEquals(name, eligible, CoreReadiness.valueOf(name).isStartupBarrierOpen)
        }
    }
}
