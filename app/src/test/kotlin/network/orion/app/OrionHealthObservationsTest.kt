package network.orion.app

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
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

    @Test fun delayedCortexCallbackFromPreviousRecoveryCannotOverwriteItsReplacement() {
        val at = Instant.ofEpochMilli(1234)
        val states = OrionSubsystem.entries.associateWith { OrionHealthStatus.HEALTHY }
        val report = MutableStateFlow(OrionHealthAggregator.aggregate(states, at, at))
        val owner = OrionHealthObservationOwner(report, WallClock { at })
        val oldSource = owner.replaceSource()
        val sampling = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val published = AtomicReference<Boolean?>()
        val failure = AtomicReference<Throwable?>()
        val delayed = Thread {
            try {
                sampling.countDown()
                check(complete.await(5, TimeUnit.SECONDS))
                published.set(owner.publish(oldSource, mapOf(OrionSubsystem.CORTEX to OrionHealthStatus.HEALTHY)))
            } catch (error: Throwable) { failure.set(error) }
        }
        delayed.start()
        try {
            assertTrue(sampling.await(5, TimeUnit.SECONDS))
            val replacement = owner.replaceSource()
            assertTrue(owner.publish(replacement, mapOf(OrionSubsystem.CORTEX to OrionHealthStatus.UNAVAILABLE)))
            report.publishSubsystemObservations(WallClock { at }, mapOf(OrionSubsystem.ACTION_OUTBOX to OrionHealthStatus.FAILED))
        } finally { complete.countDown(); delayed.join(5000) }
        assertFalse(delayed.isAlive)
        assertNull(failure.get())
        assertEquals(false, published.get())
        assertEquals(OrionHealthStatus.UNAVAILABLE, report.value.subsystems[OrionSubsystem.CORTEX])
        assertEquals(OrionHealthStatus.FAILED, report.value.subsystems[OrionSubsystem.ACTION_OUTBOX])
        assertEquals(at, report.value.lastRecovery)
    }

    @Test fun replacingCortexSourceWaitsForAnAlreadyAdmittedHealthPublication() {
        val at = Instant.ofEpochMilli(1234)
        val states = OrionSubsystem.entries.associateWith { OrionHealthStatus.HEALTHY }
        val report = MutableStateFlow(OrionHealthAggregator.aggregate(states, at, at))
        val publicationEntered = CountDownLatch(1)
        val finishPublication = CountDownLatch(1)
        val pause = AtomicBoolean(true)
        val clock = WallClock {
            if (pause.compareAndSet(true, false)) {
                publicationEntered.countDown()
                check(finishPublication.await(5, TimeUnit.SECONDS))
            }
            at
        }
        val owner = OrionHealthObservationOwner(report, clock)
        val oldSource = owner.replaceSource()
        val replacementStarted = CountDownLatch(1)
        val replacementFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val oldCallback = Thread {
            try { owner.publish(oldSource, mapOf(OrionSubsystem.CORTEX to OrionHealthStatus.HEALTHY)) }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        val replace = Thread {
            try {
                replacementStarted.countDown()
                val current = owner.replaceSource()
                owner.publish(current, mapOf(OrionSubsystem.CORTEX to OrionHealthStatus.UNAVAILABLE))
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { replacementFinished.countDown() }
        }
        oldCallback.start()
        try {
            assertTrue(publicationEntered.await(5, TimeUnit.SECONDS))
            replace.start()
            assertTrue(replacementStarted.await(5, TimeUnit.SECONDS))
            // Invalidating a source cannot return between its ownership check and
            // the local health commit. A check outside the monitor would race here.
            assertFalse(replacementFinished.await(100, TimeUnit.MILLISECONDS))
        } finally {
            finishPublication.countDown()
            oldCallback.join(5000)
            if (replace.state != Thread.State.NEW) replace.join(5000)
        }
        assertFalse(oldCallback.isAlive)
        assertFalse(replace.isAlive)
        assertNull(failure.get())
        assertEquals(OrionHealthStatus.UNAVAILABLE, report.value.subsystems[OrionSubsystem.CORTEX])
        assertFalse(owner.publish(oldSource, mapOf(OrionSubsystem.CORTEX to OrionHealthStatus.HEALTHY)))
        assertEquals(at, report.value.lastRecovery)
    }
}
