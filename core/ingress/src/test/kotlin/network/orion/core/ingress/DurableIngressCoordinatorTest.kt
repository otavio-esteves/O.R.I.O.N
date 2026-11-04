package network.orion.core.ingress

import java.util.UUID
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import network.orion.core.health.CoreReadiness
import network.orion.core.model.Sensitivity
import org.junit.Assert.*
import org.junit.Test

class DurableIngressCoordinatorTest {
    private class Queue : DurableIngressQueue {
        var enqueued = 0
        var consumed = 0
        var onConsume: () -> Unit = {}
        override fun enqueue(request: DurableIngressRequest): DurableIngressOutcome { enqueued++; return DurableIngressOutcome.QUEUED }
        override fun consumeNext(): Boolean { consumed++; onConsume(); return true }
    }
    private fun request() = DurableIngressRequest(UUID.randomUUID(), "task.create", UUID.randomUUID(), "ui", "CreateTask", 1,
        Sensitivity.SECRET, true, UUID.randomUUID(), null, 1000, 2000, correlationId = UUID.randomUUID())

    @Test fun `ready health alone never authorizes drain but durable enqueue works gated`() {
        val queue = Queue()
        val barrier = StartupBarrier()
        val coordinator = DurableIngressCoordinator(queue, barrier, { CoreReadiness.READY }, { true })
        assertEquals(DurableIngressOutcome.QUEUED, coordinator.submit(request()))
        assertEquals(0, coordinator.drain(10))
        assertEquals(0, queue.consumed)
        assertEquals(1, queue.enqueued)
        assertTrue(barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.READY))
        assertEquals(10, coordinator.drain(10))
    }

    @Test fun `unavailable storage neither persists nor consumes`() {
        val queue = Queue()
        val coordinator = DurableIngressCoordinator(queue, StartupBarrier(), { CoreReadiness.READY }, { false })
        assertEquals(DurableIngressOutcome.STORAGE_UNAVAILABLE, coordinator.submit(request()))
        assertEquals(0, coordinator.drain(1))
        assertEquals(0, queue.enqueued)
        assertEquals(IngressAdmission.UNAVAILABLE, coordinator.admit(IngressClass.MUTATING_DURABLE))
    }

    @Test fun `drain stops between transactions when recovery gates again`() {
        val queue = Queue()
        val barrier = StartupBarrier()
        barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.READY)
        queue.onConsume = { barrier.beginRecovery() }
        assertEquals(1, DurableIngressCoordinator(queue, barrier, { CoreReadiness.READY }, { true }).drain(10))
    }

    @Test fun `stale or foreign recovery cannot release a gate`() {
        val barrier = StartupBarrier()
        val old = barrier.beginRecovery()
        val current = barrier.beginRecovery()
        assertFalse(barrier.releaseAfterRecovery(old, CoreReadiness.READY))
        assertFalse(barrier.releaseAfterRecovery(StartupBarrier().beginRecovery(), CoreReadiness.READY))
        assertFalse(barrier.releaseAfterRecovery(current, CoreReadiness.RECOVERING))
        assertTrue(barrier.releaseAfterRecovery(current, CoreReadiness.DEGRADED_SAFE))
    }

    @Test fun `reference minimization and byte ownership are enforced`() {
        val reference = request()
        assertFalse(reference.toString().contains(reference.referenceId.toString()))
        assertThrows(IllegalArgumentException::class.java) {
            DurableIngressRequest(UUID.randomUUID(), "scope", UUID.randomUUID(), "ui", "Type", 1,
                Sensitivity.SECRET, true, UUID.randomUUID(), byteArrayOf(1), 10, 20, correlationId = UUID.randomUUID())
        }
        val bytes = byteArrayOf(1,2)
        val content = DurableIngressRequest(UUID.randomUUID(), "scope", UUID.randomUUID(), "ui", "Type", 1,
            Sensitivity.SECRET, false, null, bytes, 10, 20, correlationId = UUID.randomUUID())
        bytes[0] = 9
        assertArrayEquals(byteArrayOf(1,2), content.copyContent())
        val copy = content.copyContent()!!
        copy[1] = 9
        assertArrayEquals(byteArrayOf(1,2), content.copyContent())
    }

    @Test fun `recovery waits for admitted transaction then prevents further admissions`() {
        val queue = Queue()
        val barrier = StartupBarrier()
        assertTrue(barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.READY))
        val coordinator = DurableIngressCoordinator(queue, barrier, { CoreReadiness.READY }, { true })
        val entered = CountDownLatch(1)
        val finishTransaction = CountDownLatch(1)
        val recoveryAttempted = CountDownLatch(1)
        val recoveryClosed = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newFixedThreadPool(2)
        queue.onConsume = {
            entered.countDown()
            check(finishTransaction.await(5, TimeUnit.SECONDS))
            order.add("transaction-finished")
        }
        try {
            val transaction = executor.submit<Int> { coordinator.drain(1) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val recovery = executor.submit {
                recoveryAttempted.countDown()
                barrier.beginRecovery()
                order.add("recovery-closed")
                recoveryClosed.countDown()
            }
            assertTrue(recoveryAttempted.await(5, TimeUnit.SECONDS))
            assertFalse("Recovery must wait for the admitted transaction", recoveryClosed.await(100, TimeUnit.MILLISECONDS))
            finishTransaction.countDown()
            assertEquals(1, transaction.get(5, TimeUnit.SECONDS).toInt())
            recovery.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("transaction-finished", "recovery-closed"), order.toList())
            assertFalse(barrier.isOpen())
            assertEquals(0, coordinator.drain(10))
            assertEquals(1, queue.consumed)
        } finally {
            finishTransaction.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun `blocked health during batch prevents next transaction even while barrier remains open`() {
        val queue = Queue()
        val barrier = StartupBarrier()
        assertTrue(barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.READY))
        var readiness = CoreReadiness.READY
        val coordinator = DurableIngressCoordinator(queue, barrier, { readiness }, { true })
        queue.onConsume = { readiness = CoreReadiness.BLOCKED }
        assertEquals(1, coordinator.drain(10))
        assertTrue(barrier.isOpen())
        assertEquals(0, coordinator.drain(10))
        assertEquals(1, queue.consumed)
    }

    @Test fun `writer authority snapshot cannot deadlock ingress holding the admission monitor`() {
        val barrier = StartupBarrier()
        assertTrue(barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.READY))
        val writerLock = ReentrantLock()
        val writerHeld = CountDownLatch(1)
        val admissionHeld = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit<Boolean> {
                writerLock.lockInterruptibly()
                try {
                    writerHeld.countDown()
                    check(admissionHeld.await(5, TimeUnit.SECONDS))
                    // Represents a fresh authority check after acquiring SQLite's writer.
                    barrier.isOpen()
                } finally { writerLock.unlock() }
            }
            assertTrue(writerHeld.await(5, TimeUnit.SECONDS))
            val ingress = executor.submit<Boolean> {
                barrier.whileOpen {
                    admissionHeld.countDown()
                    // Interruptible so a failing regression releases the monitor during cleanup.
                    writerLock.lockInterruptibly()
                    try { true } finally { writerLock.unlock() }
                } ?: false
            }
            assertTrue(writer.get(5, TimeUnit.SECONDS))
            assertTrue(ingress.get(5, TimeUnit.SECONDS))
            assertTrue(barrier.isOpen())
        } finally {
            admissionHeld.countDown()
            executor.shutdownNow()
            assertTrue("Test threads must terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
