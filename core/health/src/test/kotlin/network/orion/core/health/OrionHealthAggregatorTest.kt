package network.orion.core.health

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class OrionHealthAggregatorTest {
    private val now = Instant.parse("2026-11-03T12:00:00Z")
    private fun statuses() = OrionSubsystem.entries.associateWith { OrionHealthStatus.UNAVAILABLE }.toMutableMap()
    @Test fun `optional unavailability never pretends storage is ready`() {
        val states = statuses()
        assertEquals(CoreReadiness.STARTING, OrionHealthAggregator.aggregate(states, now).core.readiness)
        states[OrionSubsystem.DATABASE] = OrionHealthStatus.HEALTHY
        states[OrionSubsystem.CONFIGURATION] = OrionHealthStatus.HEALTHY
        val report = OrionHealthAggregator.aggregate(states, now, now)
        assertEquals(CoreReadiness.READY, report.core.readiness)
        assertEquals(OrionHealthStatus.UNAVAILABLE, report.subsystems[OrionSubsystem.CORTEX])
        assertEquals(now, report.lastRecovery)
        states[OrionSubsystem.DATABASE] = OrionHealthStatus.FAILED
        assertEquals(OrionHealthStatus.HEALTHY, report.subsystems[OrionSubsystem.DATABASE])
        assertEquals(CoreReadiness.BLOCKED, OrionHealthAggregator.aggregate(states, now).core.readiness)
    }
    @Test fun `critical recovery gates reads while optional failure degrades safely`() {
        val states = statuses()
        states[OrionSubsystem.DATABASE] = OrionHealthStatus.RECOVERING
        assertEquals(CoreReadiness.RECOVERING, OrionHealthAggregator.aggregate(states, now).core.readiness)
        states[OrionSubsystem.DATABASE] = OrionHealthStatus.HEALTHY
        states[OrionSubsystem.CONFIGURATION] = OrionHealthStatus.HEALTHY
        states[OrionSubsystem.VOICE] = OrionHealthStatus.FAILED
        assertEquals(CoreReadiness.DEGRADED_SAFE, OrionHealthAggregator.aggregate(states, now).core.readiness)
        states[OrionSubsystem.DATABASE] = OrionHealthStatus.DEGRADED
        assertEquals(CoreReadiness.BLOCKED, OrionHealthAggregator.aggregate(states, now).core.readiness)
    }
    @Test fun `missing subsystem evidence is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { OrionHealthAggregator.aggregate(emptyMap(), now) }
    }
}
