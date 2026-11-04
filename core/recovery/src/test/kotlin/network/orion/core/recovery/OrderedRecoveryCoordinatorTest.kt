package network.orion.core.recovery

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import org.junit.Assert.*
import org.junit.Test

class OrderedRecoveryCoordinatorTest {
    @Test fun `all mandatory stages execute before health publication and release`() {
        val barrier = StartupBarrier()
        val order = mutableListOf<String>()
        val steps = RecoveryStage.entries.associateWith { stage -> { assertFalse(barrier.isOpen()); order.add(stage.name); Unit } }
        val runner = OrderedRecoveryCoordinator(barrier, steps, {
            assertFalse(barrier.isOpen()); order.add("HEALTH"); CoreReadiness.DEGRADED_SAFE
        }, { assertTrue(barrier.isOpen()); order.add("DRAIN") })
        assertEquals(RecoveryRunOutcome.RELEASED, runner.recover())
        assertEquals(RecoveryStage.entries.map { it.name } + listOf("HEALTH", "DRAIN"), order)
    }

    @Test fun `every stage failure aborts later steps and preserves closed barrier`() {
        for (failedStage in RecoveryStage.entries) {
            val barrier = StartupBarrier()
            val reached = mutableListOf<RecoveryStage>()
            val steps = RecoveryStage.entries.associateWith { stage -> {
                reached.add(stage)
                if (stage == failedStage) error("synthetic failure")
            } }
            val runner = OrderedRecoveryCoordinator(barrier, steps, { error("Must not publish ready") }, { error("Must not drain") })
            assertEquals(RecoveryRunOutcome.FAILED, runner.recover())
            assertEquals(RecoveryStage.entries.take(failedStage.ordinal + 1), reached)
            assertFalse(barrier.isOpen())
        }
    }

    @Test fun `blocked health and failed drain do not leave an open gate`() {
        val barrier = StartupBarrier()
        val steps = RecoveryStage.entries.associateWith { { Unit } }
        assertEquals(RecoveryRunOutcome.BLOCKED, OrderedRecoveryCoordinator(barrier, steps,
            { CoreReadiness.BLOCKED }, { error("Must not drain") }).recover())
        assertFalse(barrier.isOpen())
        assertEquals(RecoveryRunOutcome.FAILED, OrderedRecoveryCoordinator(barrier, steps,
            { CoreReadiness.READY }, { error("Drain failure") }).recover())
        assertFalse(barrier.isOpen())
    }

    @Test fun `cancellation closes barrier and propagates`() {
        val barrier = StartupBarrier()
        val steps = RecoveryStage.entries.associateWith { { Unit } }
        val runner = OrderedRecoveryCoordinator(barrier, steps, { CoreReadiness.READY }, { throw CancellationException() })
        assertThrows(CancellationException::class.java) { runner.recover() }
        assertFalse(barrier.isOpen())
    }

    @Test fun `missing recovery stage is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            OrderedRecoveryCoordinator(StartupBarrier(), emptyMap(), { CoreReadiness.READY }, {})
        }
    }

    @Test fun `superseded recovery cannot drain or close the newer completed recovery`() {
        val barrier = StartupBarrier()
        val oldPaused = CountDownLatch(1)
        val finishOld = CountDownLatch(1)
        val oldDrains = AtomicInteger()
        val oldHealthPublications = AtomicInteger()
        val newDrains = AtomicInteger()
        val oldSteps = RecoveryStage.entries.associateWith { stage -> {
            if (stage == RecoveryStage.RESOURCE_GOVERNOR) {
                oldPaused.countDown()
                check(finishOld.await(5, TimeUnit.SECONDS))
            }
        } }
        val oldRunner = OrderedRecoveryCoordinator(barrier, oldSteps,
            { oldHealthPublications.incrementAndGet(); CoreReadiness.READY }, { oldDrains.incrementAndGet(); Unit })
        val newRunner = OrderedRecoveryCoordinator(barrier, RecoveryStage.entries.associateWith { { Unit } },
            { CoreReadiness.DEGRADED_SAFE }, { newDrains.incrementAndGet(); Unit })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val oldResult = executor.submit<RecoveryRunOutcome> { oldRunner.recover() }
            assertTrue(oldPaused.await(5, TimeUnit.SECONDS))
            assertEquals(RecoveryRunOutcome.RELEASED, newRunner.recover())
            assertTrue(barrier.isOpen())
            finishOld.countDown()
            assertEquals(RecoveryRunOutcome.SUPERSEDED, oldResult.get(5, TimeUnit.SECONDS))
            assertEquals(0, oldDrains.get())
            assertEquals(0, oldHealthPublications.get())
            assertEquals(1, newDrains.get())
            assertTrue(barrier.isOpen())
        } finally {
            finishOld.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun `superseded failure cannot close newer successful recovery`() {
        assertSupersededFailurePreservesNewRecovery(cancel = false)
    }

    @Test fun `superseded cancellation propagates without closing newer successful recovery`() {
        assertSupersededFailurePreservesNewRecovery(cancel = true)
    }

    @Test fun `health publication release and local drain cannot be interleaved by newer recovery`() {
        val barrier = StartupBarrier()
        val healthEntered = CountDownLatch(1)
        val finishHealth = CountDownLatch(1)
        val newerAttempted = CountDownLatch(1)
        val newerEntered = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val oldRunner = OrderedRecoveryCoordinator(barrier, RecoveryStage.entries.associateWith { { Unit } }, {
            healthEntered.countDown()
            check(finishHealth.await(5, TimeUnit.SECONDS))
            order.add("old-health")
            CoreReadiness.READY
        }, { order.add("old-drain"); Unit })
        val newSteps = RecoveryStage.entries.associateWith { stage -> {
            if (stage == RecoveryStage.MINIMAL_LOGGING) {
                order.add("new-first-stage")
                newerEntered.countDown()
            }
        } }
        val newRunner = OrderedRecoveryCoordinator(barrier, newSteps,
            { order.add("new-health"); CoreReadiness.READY }, { order.add("new-drain"); Unit })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val oldResult = executor.submit<RecoveryRunOutcome> { oldRunner.recover() }
            assertTrue(healthEntered.await(5, TimeUnit.SECONDS))
            val newResult = executor.submit<RecoveryRunOutcome> {
                newerAttempted.countDown()
                newRunner.recover()
            }
            assertTrue(newerAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(newerEntered.await(100, TimeUnit.MILLISECONDS))
            finishHealth.countDown()
            assertEquals(RecoveryRunOutcome.RELEASED, oldResult.get(5, TimeUnit.SECONDS))
            assertEquals(RecoveryRunOutcome.RELEASED, newResult.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("old-health", "old-drain", "new-first-stage", "new-health", "new-drain"), order.toList())
            assertTrue(barrier.isOpen())
        } finally {
            finishHealth.countDown()
            executor.shutdownNow()
        }
    }

    private fun assertSupersededFailurePreservesNewRecovery(cancel: Boolean) {
        val barrier = StartupBarrier()
        val oldPaused = CountDownLatch(1)
        val finishOld = CountDownLatch(1)
        val oldHealthPublications = AtomicInteger()
        val oldDrains = AtomicInteger()
        val newDrains = AtomicInteger()
        val oldSteps = RecoveryStage.entries.associateWith { stage -> {
            if (stage == RecoveryStage.RESOURCE_GOVERNOR) {
                oldPaused.countDown()
                check(finishOld.await(5, TimeUnit.SECONDS))
                if (cancel) throw CancellationException("synthetic cancellation")
                error("synthetic failure")
            }
        } }
        val oldRunner = OrderedRecoveryCoordinator(barrier, oldSteps,
            { oldHealthPublications.incrementAndGet(); CoreReadiness.READY }, { oldDrains.incrementAndGet(); Unit })
        val newRunner = OrderedRecoveryCoordinator(barrier, RecoveryStage.entries.associateWith { { Unit } },
            { CoreReadiness.READY }, { newDrains.incrementAndGet(); Unit })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val oldResult = executor.submit<RecoveryRunOutcome> { oldRunner.recover() }
            assertTrue(oldPaused.await(5, TimeUnit.SECONDS))
            assertEquals(RecoveryRunOutcome.RELEASED, newRunner.recover())
            finishOld.countDown()
            if (cancel) {
                val failure = assertThrows(ExecutionException::class.java) { oldResult.get(5, TimeUnit.SECONDS) }
                assertTrue(failure.cause is CancellationException)
            } else {
                assertEquals(RecoveryRunOutcome.SUPERSEDED, oldResult.get(5, TimeUnit.SECONDS))
            }
            assertEquals(0, oldHealthPublications.get())
            assertEquals(0, oldDrains.get())
            assertEquals(1, newDrains.get())
            assertTrue(barrier.isOpen())
        } finally {
            finishOld.countDown()
            executor.shutdownNow()
        }
    }
}
