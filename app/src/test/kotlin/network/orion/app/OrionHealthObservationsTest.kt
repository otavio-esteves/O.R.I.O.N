package network.orion.app

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import network.orion.core.health.*
import network.orion.core.time.WallClock
import org.junit.Assert.*
import org.junit.Test

class OrionHealthObservationsTest {
    @Test fun delayedPlatformObservationCannotEraseAConcurrentActionFailureOrRecoveryTimestamp() {
        val recovery = Instant.ofEpochMilli(1234)
        val clock = WallClock { Instant.ofEpochMilli(2000) }
        val states = OrionSubsystem.entries.associateWith { OrionHealthStatus.HEALTHY }
        val report = MutableStateFlow(OrionHealthAggregator.aggregate(states, recovery, recovery))
        val sampling = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val observer = Thread {
            try {
                sampling.countDown()
                check(complete.await(5, TimeUnit.SECONDS))
                report.publishSubsystemObservations(clock, mapOf(
                    OrionSubsystem.NOTIFICATIONS to OrionHealthStatus.HEALTHY,
                    OrionSubsystem.PERMISSIONS to OrionHealthStatus.HEALTHY,
                ))
            } catch (error: Throwable) { failure.set(error) }
        }
        observer.start()
        try {
            assertTrue(sampling.await(5, TimeUnit.SECONDS))
            report.publishSubsystemObservations(clock, mapOf(OrionSubsystem.ACTION_OUTBOX to OrionHealthStatus.FAILED))
        } finally { complete.countDown(); observer.join(5000) }
        assertFalse(observer.isAlive)
        assertNull(failure.get())
        assertEquals(OrionHealthStatus.FAILED, report.value.subsystems[OrionSubsystem.ACTION_OUTBOX])
        assertEquals(OrionHealthStatus.HEALTHY, report.value.subsystems[OrionSubsystem.NOTIFICATIONS])
        assertEquals(CoreReadiness.DEGRADED_SAFE, report.value.core.readiness)
        assertEquals(recovery, report.value.lastRecovery)
    }
}
