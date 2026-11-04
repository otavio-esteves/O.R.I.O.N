package network.orion.app

import java.time.Instant
import network.orion.core.health.CoreReadiness
import network.orion.core.health.OrionHealthAggregator
import network.orion.core.health.OrionHealthStatus
import network.orion.core.health.OrionSubsystem
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

    @Test fun `field key failure with healthy storage reports degraded recovery`() {
        val states = OrionSubsystem.entries.associateWith { OrionHealthStatus.UNAVAILABLE }.toMutableMap()
        states[OrionSubsystem.DATABASE] = OrionHealthStatus.HEALTHY
        states[OrionSubsystem.CONFIGURATION] = OrionHealthStatus.HEALTHY
        states[OrionSubsystem.FIELD_KEYS] = OrionHealthStatus.FAILED

        val published = OrionHealthAggregator.aggregate(states, Instant.EPOCH)

        assertEquals(CoreReadiness.DEGRADED_SAFE, published.core.readiness)
        assertEquals(OrionLogEvent.CORE_DEGRADED, failedRecoveryLogEvent(published.core.readiness))
    }

    @Test fun `critical storage failures report blocked recovery`() {
        for (failedSubsystem in listOf(OrionSubsystem.DATABASE, OrionSubsystem.CONFIGURATION)) {
            val states = OrionSubsystem.entries.associateWith { OrionHealthStatus.UNAVAILABLE }.toMutableMap()
            states[OrionSubsystem.DATABASE] = OrionHealthStatus.HEALTHY
            states[OrionSubsystem.CONFIGURATION] = OrionHealthStatus.HEALTHY
            states[failedSubsystem] = OrionHealthStatus.FAILED

            val published = OrionHealthAggregator.aggregate(states, Instant.EPOCH)

            assertEquals(CoreReadiness.BLOCKED, published.core.readiness)
            assertEquals(OrionLogEvent.CORE_BLOCKED, failedRecoveryLogEvent(published.core.readiness))
        }
    }
}
