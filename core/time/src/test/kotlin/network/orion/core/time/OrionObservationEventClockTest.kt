package network.orion.core.time

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.*
import org.junit.Test

/** Synthetic finite counter readings only, never Android timing or physical evidence. */
class OrionObservationEventClockTest {
    private val prepared = MonotonicInstant(BootSessionId(3), 100)
    private val validated = MonotonicInstant(BootSessionId(3), 110)
    private fun state(clock: OrionObservationEventClock) = checkNotNull(clock.snapshot())
    private fun bind(clock: OrionObservationEventClock) = clock.bindOriginalWitness(prepared, validated, MonotonicDeadlineStatus.ACTIVE)

    @Test fun `inactive construction and failed unbound reads never touch native or functional clock`() {
        val clock = checkNotNull(OrionObservationEventClock.createInactive())
        assertFalse(OrionClock::class.java.isAssignableFrom(clock.javaClass))
        assertNull(clock.observe())
        val current = state(clock)
        assertEquals(0L, current.counterReadsAtLeast); assertFalse(current.bound); assertFalse(current.invalidated)
        assertNull(current.observationalCachedBootCount)
        assertTrue(bind(clock)); assertEquals(0L, state(clock).counterReadsAtLeast)
    }

    @Test fun `binding consumes existing samples only and event uses actual new counter value`() {
        var functionalReads = 0
        val original = MonotonicClock { functionalReads++; if (functionalReads == 1) prepared else validated }
        val clock = OrionObservationEventClock.finiteForTests(listOf(120, 125))
        assertTrue(clock.bindOriginalWitness(original.monotonicNow(), original.monotonicNow(), MonotonicDeadlineStatus.ACTIVE))
        assertEquals(2, functionalReads); assertEquals(0L, state(clock).counterReadsAtLeast)
        val first = checkNotNull(clock.observe()); val second = checkNotNull(clock.observe())
        assertEquals(120L, first.elapsedRealtimeMillis); assertEquals(3, first.observationalBootCount)
        assertEquals(125L, second.elapsedRealtimeMillis); assertTrue(first.sameObservationAs(second))
        assertEquals(2, functionalReads); assertEquals(2L, state(clock).counterReadsAtLeast)
        assertFalse(MonotonicInstant::class.java.isAssignableFrom(first.javaClass))
    }

    @Test fun `unknown missing invalid regressed and mismatched bind witnesses fail permanently without reads`() {
        val cases = listOf(
            Triple(MonotonicInstant(null, 100), validated, MonotonicDeadlineStatus.ACTIVE),
            Triple(prepared, null, MonotonicDeadlineStatus.ACTIVE),
            Triple(prepared, MonotonicInstant(null, 110), MonotonicDeadlineStatus.ACTIVE),
            Triple(prepared, MonotonicInstant(BootSessionId(4), 110), MonotonicDeadlineStatus.ACTIVE),
            Triple(prepared, MonotonicInstant(BootSessionId(3), 99), MonotonicDeadlineStatus.ACTIVE),
            Triple(prepared, validated, MonotonicDeadlineStatus.INVALID_REFERENCE),
            Triple(prepared, validated, null))
        for ((start, checked, classification) in cases) {
            val clock = OrionObservationEventClock.finiteForTests(listOf(120))
            assertFalse(clock.bindOriginalWitness(start, checked, classification))
            assertNull(clock.observe()); assertFalse(bind(clock)); assertTrue(state(clock).invalidated)
            assertEquals(0L, state(clock).counterReadsAtLeast)
        }
    }

    @Test fun `second binding invalidates rather than refreshing observational cache`() {
        val clock = OrionObservationEventClock.finiteForTests(listOf(120, 130))
        assertTrue(bind(clock)); assertNotNull(clock.observe())
        assertFalse(bind(clock)); assertNull(clock.observe())
        assertTrue(state(clock).invalidated); assertEquals(1L, state(clock).counterReadsAtLeast)
    }

    @Test fun `negative signed counter wrap regression and unavailable readings stay unavailable without clamping`() {
        for (values in listOf(listOf(-1L), listOf(Long.MIN_VALUE), listOf(null), listOf(109L), listOf(120L, 119L))) {
            val clock = OrionObservationEventClock.finiteForTests(values)
            assertTrue(bind(clock))
            if (values.size == 2) assertEquals(120L, clock.observe()?.elapsedRealtimeMillis)
            assertNull(clock.observe()); val current = state(clock)
            assertTrue(current.invalidated); assertNotNull(current.firstGap); assertTrue(current.droppedAtLeast >= 1)
            assertNull(clock.observe()); assertFalse(bind(clock))
        }
        val clock = OrionObservationEventClock.finiteForTests(listOf(Long.MAX_VALUE, Long.MIN_VALUE))
        assertTrue(bind(clock)); assertEquals(Long.MAX_VALUE, clock.observe()?.elapsedRealtimeMillis)
        assertNull(clock.observe()); assertTrue(state(clock).invalidated)
    }

    @Test fun `finite source exhaustion and independent observation scopes cannot repair one another`() {
        val first = OrionObservationEventClock.finiteForTests(listOf(120))
        val other = OrionObservationEventClock.finiteForTests(listOf(120))
        assertTrue(bind(first)); assertTrue(bind(other))
        val a = checkNotNull(first.observe()); val b = checkNotNull(other.observe())
        assertFalse(a.sameObservationAs(b)); assertNull(first.observe()); assertNull(first.observe())
        assertEquals(2L, state(first).counterReadsAtLeast)
        assertFalse(state(other).invalidated)
        assertThrows(IllegalArgumentException::class.java) { OrionObservationEventClock.finiteForTests(List(257) { 120L }) }
    }

    @Test fun `original witness disagreements invalidate without a native read or cached success fallback`() {
        for (sample in listOf(MonotonicInstant(null, 130), MonotonicInstant(BootSessionId(4), 130),
            MonotonicInstant(BootSessionId(3), 109), MonotonicInstant(BootSessionId(3), 115))) {
            val clock = OrionObservationEventClock.finiteForTests(listOf(120, 140))
            assertTrue(bind(clock)); assertNotNull(clock.observe())
            assertFalse(clock.confirmOriginalWitness(sample, MonotonicDeadlineStatus.ACTIVE))
            assertNull(clock.observe()); assertEquals(1L, state(clock).counterReadsAtLeast)
        }
    }

    @Test fun `contention never waits and permanently fences new observations`() {
        val clock = OrionObservationEventClock.finiteForTests(listOf(120))
        assertTrue(bind(clock))
        val lock = clock.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(clock) as ReentrantLock
        val worker = Executors.newSingleThreadExecutor()
        lock.lock()
        try { assertNull(worker.submit(Callable { clock.observe() }).get(1, TimeUnit.SECONDS)) }
        finally { lock.unlock(); worker.shutdownNow() }
        assertNull(clock.observe()); assertTrue(state(clock).invalidated)
        assertEquals(0L, state(clock).counterReadsAtLeast)
    }

    @Test fun `native source faults and counter accounting overflow remain sticky unavailable`() {
        val clock = OrionObservationEventClock.finiteForTests(listOf(120, 130))
        assertTrue(bind(clock))
        val counter = clock.javaClass.getDeclaredField("counter").apply { isAccessible = true }
        val original = counter.get(clock)
        try { counter.set(clock, null); assertNull(clock.observe()) } finally { counter.set(clock, original) }
        assertTrue(state(clock).recordingFailuresAtLeast >= 1); assertFalse(state(clock).lossAccountingComplete)
        assertNull(clock.observe()); assertEquals(1L, state(clock).counterReadsAtLeast)
        val overflow = OrionObservationEventClock.finiteForTests(listOf(120))
        assertTrue(bind(overflow))
        val reads = overflow.javaClass.getDeclaredField("reads").apply { isAccessible = true }.get(overflow) as java.util.concurrent.atomic.AtomicLong
        reads.set(Long.MAX_VALUE)
        assertNotNull(overflow.observe()); assertEquals(Long.MAX_VALUE, state(overflow).counterReadsAtLeast)
        assertFalse(state(overflow).lossAccountingComplete)
    }


    @Test fun `invalidation during an in flight counter read prevents publication or high water advance`() {
        repeat(3) { scenario ->
            val clock = OrionObservationEventClock.finiteForTests(listOf(120))
            assertTrue(bind(clock))
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val counter = clock.javaClass.getDeclaredField("counter").apply { isAccessible = true }.get(clock)
            val values = counter.javaClass.getDeclaredField("values").apply { isAccessible = true }
            // Reflection changes the private finite test source only. Production exposes no provider/callback seam.
            values.set(counter, object : java.util.AbstractList<Long?>() {
                override val size = 1
                override fun get(index: Int): Long? {
                    entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); return 120L
                }
            })
            val worker = Executors.newSingleThreadExecutor()
            val pending = worker.submit(Callable { clock.observe() })
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                when (scenario) {
                    0 -> assertFalse(bind(clock))
                    1 -> assertFalse(clock.confirmOriginalWitness(MonotonicInstant(BootSessionId(3), 130), MonotonicDeadlineStatus.ACTIVE))
                    else -> assertTrue(checkNotNull(clock.snapshot()).invalidated)
                }
                release.countDown()
                assertNull(pending.get(3, TimeUnit.SECONDS))
            } finally { release.countDown(); worker.shutdownNow() }
            assertTrue(state(clock).invalidated); assertEquals(0L, state(clock).recordingFailuresAtLeast)
            assertEquals(1L, state(clock).counterReadsAtLeast); assertNull(clock.observe())
            val last = clock.javaClass.getDeclaredField("lastElapsed").apply { isAccessible = true }.getLong(clock)
            assertEquals(110L, last)
        }
    }

}
