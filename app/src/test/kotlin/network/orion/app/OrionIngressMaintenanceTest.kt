package network.orion.app

import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.*
import org.junit.Assert.*
import org.junit.Test

class OrionIngressMaintenanceTest {
    @Test fun `maintenance respects recovery gate and resumes existing queued work`() {
        var remaining = 80
        val queue = object : DurableIngressQueue {
            override fun enqueue(request: DurableIngressRequest) = DurableIngressOutcome.QUEUED
            override fun consumeNext(): Boolean {
                if (remaining == 0) return false
                remaining--
                return true
            }
        }
        val barrier = StartupBarrier()
        val recovered = DurableIngressCoordinator(queue, barrier, { CoreReadiness.DEGRADED_SAFE }, { true })
        assertFalse(drainIngressMaintenance(listOf(recovered::drain)))
        assertEquals(80, remaining)
        barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.DEGRADED_SAFE)
        assertEquals(25, recovered.drain(25))
        assertTrue(drainIngressMaintenance(listOf(recovered::drain)))
        assertTrue(drainIngressMaintenance(listOf(recovered::drain)))
        assertFalse(drainIngressMaintenance(listOf(recovered::drain)))
        assertEquals(0, remaining)
    }
    @Test fun `backlog beyond startup batch completes without a UI drain`() {
        // Recovery already consumed 25 of 91 durable entries.
        var remaining = 66
        val consumer: (Int) -> Int = { limit -> minOf(limit, remaining).also { remaining -= it } }
        assertTrue(drainIngressMaintenance(listOf(consumer)))
        assertTrue(drainIngressMaintenance(listOf(consumer)))
        assertFalse(drainIngressMaintenance(listOf(consumer)))
        assertEquals(0, remaining)
    }

    @Test fun `full early domain does not starve later domains`() {
        val visited = mutableListOf<Int>()
        val consumers = (0..5).map { domain ->
            { limit: Int -> visited.add(domain); if (domain == 0) limit else 0 }
        }
        assertTrue(drainIngressMaintenance(consumers))
        assertEquals((0..5).toList(), visited)
    }

    @Test fun `blocked queues await authority instead of requesting repeated retries`() {
        assertFalse(drainIngressMaintenance(List(6) { { _: Int -> 0 } }))
    }
}
