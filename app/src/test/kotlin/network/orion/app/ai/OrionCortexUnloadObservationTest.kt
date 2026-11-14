package network.orion.app.ai

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import org.junit.Assert.*
import org.junit.Test

class OrionCortexUnloadObservationTest {
    private val start = MonotonicInstant(BootSessionId(7), 1000)
    private val deadline = start.deadlineAfter(5000)
    private fun classify(at: MonotonicInstant? = start, identity: Boolean = true, current: Boolean = true,
        closed: Boolean = false, valid: Boolean = true, unloaded: Boolean = true) =
        classifyCortexUnloadObservation(deadline, at, start, identity, current, closed, valid, unloaded)
    private fun event(index: Long = 1) = OrionCortexUnloadObservation(1, index, null, start, start,
        OrionCortexUnloadOutcome.UNLOADED_OBSERVED)

    @Test fun `only real unloaded status with valid identity and time can confirm`() {
        assertEquals(OrionCortexUnloadOutcome.UNLOADED_OBSERVED, classify())
        assertEquals(OrionCortexUnloadOutcome.OBSERVATION_UNAVAILABLE, classify(unloaded = false))
        assertEquals(OrionCortexUnloadOutcome.STATUS_INVALID, classify(valid = false))
        assertEquals(OrionCortexUnloadOutcome.IDENTITY_UNAVAILABLE, classify(identity = false))
        assertEquals(OrionCortexUnloadOutcome.ENDPOINT_CHANGED, classify(current = false))
        assertEquals(OrionCortexUnloadOutcome.CLIENT_CLOSED, classify(closed = true))
    }

    @Test fun `reply time is strictly before original deadline in the same known boot`() {
        assertEquals(OrionCortexUnloadOutcome.UNLOADED_OBSERVED, classify(MonotonicInstant(BootSessionId(7), 5999)))
        for (elapsed in listOf(6000L, 6001L, Long.MAX_VALUE))
            assertEquals(OrionCortexUnloadOutcome.DEADLINE_EXPIRED, classify(MonotonicInstant(BootSessionId(7), elapsed)))
        for (invalid in listOf(null, MonotonicInstant(null, 1001), MonotonicInstant(BootSessionId(8), 1001),
            MonotonicInstant(BootSessionId(7), 999)))
            assertEquals(OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID, classify(invalid))
        assertEquals(OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID,
            classifyCortexUnloadObservation(null, start, start, true, true, false, true, true))
    }

    @Test fun `post reply cannot regress behind pre RPC reading even within original deadline`() {
        val beforeRead = MonotonicInstant(BootSessionId(7), 5000)
        for (after in listOf(MonotonicInstant(BootSessionId(7), 4000), MonotonicInstant(BootSessionId(8), 5001), null)) {
            assertEquals(OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID,
                classifyCortexUnloadObservation(deadline, after, beforeRead, true, true, false, true, true))
        }
        assertEquals(OrionCortexUnloadOutcome.UNLOADED_OBSERVED,
            classifyCortexUnloadObservation(deadline, beforeRead, beforeRead, true, true, false, true, true))
    }

    @Test fun `endpoint uncertainty never becomes confirmation when a late unloaded string exists`() {
        val late = MonotonicInstant(BootSessionId(7), 6000)
        assertEquals(OrionCortexUnloadOutcome.ENDPOINT_CHANGED, classify(late, current = false))
        assertEquals(OrionCortexUnloadOutcome.CLIENT_CLOSED, classify(late, current = false, closed = true))
    }

    @Test fun `buffer retains oldest bounded observations and reports discarded records`() {
        val buffer = OrionCortexUnloadObservationBuffer(2)
        assertTrue(buffer.offer(event(1)))
        assertTrue(buffer.offer(event(2)))
        assertFalse(buffer.offer(event(3)))
        val batch = buffer.drain()
        assertEquals(listOf(1L, 2L), batch.observations.map { it.connectionEpoch })
        assertEquals(1L, batch.droppedAtLeast)
        assertEquals(0L, batch.contendedAtLeast)
        assertTrue(batch.lossAccountingComplete)
        assertFalse(batch.drainContended)
        assertTrue(buffer.drain().observations.isEmpty())
        assertTrue(buffer.offer(event(4)))
    }

    @Test fun `consumer cannot mutate retained observations or a later drain`() {
        val buffer = OrionCortexUnloadObservationBuffer()
        buffer.offer(event())
        val snapshot = buffer.drain()
        try {
            (snapshot.observations as MutableList<OrionCortexUnloadObservation>).clear()
            fail("Snapshot was mutable")
        } catch (_: UnsupportedOperationException) { }
        assertTrue(buffer.offer(event(2)))
        assertEquals(1L, snapshot.observations.single().connectionEpoch)
        assertEquals(2L, buffer.drain().observations.single().connectionEpoch)
    }

    @Test fun `busy collector cannot block producer and busy drain is explicit`() {
        val buffer = OrionCortexUnloadObservationBuffer()
        // Test only the collector lock; no production callback/private client endpoint is injected.
        val field = buffer.javaClass.getDeclaredField("lock").apply { isAccessible = true }
        val lock = field.get(buffer) as ReentrantLock
        val worker = Executors.newSingleThreadExecutor()
        lock.lock()
        try {
            val offered = worker.submit(Callable { buffer.offer(event()) }).get(1, TimeUnit.SECONDS)
            assertFalse(offered)
            val batch = worker.submit(Callable { buffer.drain() }).get(1, TimeUnit.SECONDS)
            assertTrue(batch.drainContended)
            assertTrue(batch.observations.isEmpty())
        } finally { lock.unlock(); worker.shutdownNow() }
        val batch = buffer.drain()
        assertEquals(1L, batch.droppedAtLeast)
        assertEquals(1L, batch.contendedAtLeast)
    }

    @Test fun `concurrent producers retain unique bounded entries with honest lower bound loss accounting`() {
        val buffer = OrionCortexUnloadObservationBuffer(64)
        val workers = Executors.newFixedThreadPool(8)
        val startWork = CountDownLatch(1)
        try {
            val jobs = (0..7).map { worker -> workers.submit {
                startWork.await()
                repeat(1000) { buffer.offer(event(worker * 1000L + it + 1)) }
            } }
            startWork.countDown()
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            val batch = buffer.drain()
            assertTrue(batch.observations.size <= 64)
            assertEquals(batch.observations.size, batch.observations.map { it.connectionEpoch }.distinct().size)
            assertTrue(batch.observations.size + batch.droppedAtLeast <= 8000L)
            if (batch.lossAccountingComplete) assertEquals(8000L, batch.observations.size + batch.droppedAtLeast)
        } finally { workers.shutdownNow() }
    }

    @Test fun `recording fault leaves accounting explicitly incomplete without manufacturing exact zero losses`() {
        val buffer = OrionCortexUnloadObservationBuffer()
        buffer.recordingFailed()
        assertTrue(buffer.offer(event()))
        val batch = buffer.drain()
        assertFalse(batch.lossAccountingComplete)
        assertEquals(1L, batch.recordingFailuresAtLeast)
        assertEquals(1, batch.observations.size)
    }

    @Test fun `collector capacity rejects unbounded configuration`() {
        for (capacity in listOf(0, 65, Int.MAX_VALUE)) {
            try { OrionCortexUnloadObservationBuffer(capacity); fail("Unbounded collector") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
