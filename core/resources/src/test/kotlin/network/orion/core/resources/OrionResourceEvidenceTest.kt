package network.orion.core.resources

import java.time.Instant
import network.orion.core.time.*
import org.junit.Assert.*
import org.junit.Test

class OrionResourceEvidenceTest {
    private class Clock : OrionClock {
        var now = MonotonicInstant(BootSessionId(1), 100)
        var wall = Instant.EPOCH
        override fun wallNow() = wall
        override fun monotonicNow() = now
    }
    private val clock = Clock()
    private fun evidence() = OrionAppMemoryEvidence(clock.now, true, setOf(1, 2), 2,
        listOf(OrionProcessMemorySample(1, 100, 20), OrionProcessMemorySample(2, 200, 30)))

    @Test fun `complete accounting counts resident and swap for all native and Java processes`() {
        val result = OrionAppMemoryAccounting.calculate(evidence(), clock.now, 500)!!
        assertEquals(230L, result.inferenceProcessBytes)
        assertEquals(350L, result.aggregateAppBytes)
    }

    @Test fun `partial unknown duplicate or unobserved process never becomes zero usage`() {
        val good = evidence()
        listOf(null, good.copy(completeRoster = false), good.copy(processIds = emptySet()),
            good.copy(samples = good.samples.take(1)), good.copy(samples = listOf(good.samples[0], good.samples[0])),
            good.copy(inferenceProcessId = 3), good.copy(processIds = setOf(1, -2)),
            good.copy(samples = listOf(OrionProcessMemorySample(1, 0, 0), good.samples[1]))).forEach {
            assertNull(OrionAppMemoryAccounting.calculate(it, clock.now, 500))
        }
    }

    @Test fun `only independently complete roster can attest inference process absence`() {
        val absent = evidence().copy(inferenceProcessId = null)
        assertEquals(0L, OrionAppMemoryAccounting.calculate(absent, clock.now, 500)!!.inferenceProcessBytes)
        assertNull(OrionAppMemoryAccounting.calculate(absent.copy(completeRoster = false), clock.now, 500))
    }

    @Test fun `memory sampling is boot bound and stale evidence is rejected at exact boundary`() {
        val good = evidence()
        assertNotNull(OrionAppMemoryAccounting.calculate(good, MonotonicInstant(BootSessionId(1), 599), 500))
        assertNull(OrionAppMemoryAccounting.calculate(good, MonotonicInstant(BootSessionId(1), 600), 500))
        assertNull(OrionAppMemoryAccounting.calculate(good, MonotonicInstant(BootSessionId(2), 101), 500))
        assertNull(OrionAppMemoryAccounting.calculate(good, MonotonicInstant(null, 101), 500))
        assertNull(OrionAppMemoryAccounting.calculate(good, MonotonicInstant(BootSessionId(1), 99), 500))
    }

    @Test fun `invalid and overflowing memory readings cannot approve a budget`() {
        val good = evidence()
        listOf(good.copy(samples = listOf(OrionProcessMemorySample(1, Long.MAX_VALUE, 1), good.samples[1])),
            good.copy(samples = listOf(OrionProcessMemorySample(1, Long.MAX_VALUE, 0), good.samples[1])),
            good.copy(samples = listOf(OrionProcessMemorySample(1, 100, -1), good.samples[1])),
            good.copy(processIds = (1..17).toSet())).forEach {
            assertNull(OrionAppMemoryAccounting.calculate(it, clock.now, 500))
        }
    }

    @Test fun `pressure survives ordinary healthy samples until configured monotonic hold expires`() {
        val tracker = OrionMemoryPressureTracker(clock, 500)
        assertEquals(OrionMemoryPressure.NONE, tracker.current(false))
        tracker.record(OrionMemoryPressure.CRITICAL)
        clock.wall = Instant.MAX
        clock.now = MonotonicInstant(BootSessionId(1), 599)
        assertEquals(OrionMemoryPressure.CRITICAL, tracker.current(false))
        clock.now = MonotonicInstant(BootSessionId(1), 600)
        assertEquals(OrionMemoryPressure.NONE, tracker.current(false))
        assertEquals(OrionMemoryPressure.CRITICAL, tracker.current(true))
        assertEquals(OrionMemoryPressure.UNKNOWN, tracker.current(null))
    }

    @Test fun `less severe callback never prematurely clears critical pressure`() {
        val tracker = OrionMemoryPressureTracker(clock, 500)
        tracker.record(OrionMemoryPressure.CRITICAL)
        clock.now = MonotonicInstant(BootSessionId(1), 200)
        tracker.record(OrionMemoryPressure.MODERATE)
        assertEquals(OrionMemoryPressure.CRITICAL, tracker.current(false))
        clock.now = MonotonicInstant(BootSessionId(1), 700)
        assertEquals(OrionMemoryPressure.NONE, tracker.current(false))
    }

    @Test fun `boot change invalidates pressure timing and same boot rollback remains unknown`() {
        val tracker = OrionMemoryPressureTracker(clock, 500)
        tracker.record(OrionMemoryPressure.MODERATE)
        clock.now = MonotonicInstant(BootSessionId(1), 99)
        assertEquals(OrionMemoryPressure.UNKNOWN, tracker.current(false))
        clock.now = MonotonicInstant(BootSessionId(2), 10)
        assertEquals(OrionMemoryPressure.NONE, tracker.current(false))
        clock.now = MonotonicInstant(null, 11)
        assertEquals(OrionMemoryPressure.UNKNOWN, tracker.current(false))
    }

    @Test fun `pressure received without boot identity gets a complete conservative hold on return`() {
        val tracker = OrionMemoryPressureTracker(clock, 500)
        clock.now = MonotonicInstant(null, 100)
        tracker.record(OrionMemoryPressure.CRITICAL)
        assertEquals(OrionMemoryPressure.UNKNOWN, tracker.current(false))
        clock.now = MonotonicInstant(BootSessionId(1), 1_000)
        assertEquals(OrionMemoryPressure.CRITICAL, tracker.current(false))
        clock.now = MonotonicInstant(BootSessionId(1), 1_500)
        assertEquals(OrionMemoryPressure.NONE, tracker.current(false))
    }

    @Test fun `system low memory sample retains recent pressure even without a trim callback`() {
        val tracker = OrionMemoryPressureTracker(clock, 500)
        assertEquals(OrionMemoryPressure.CRITICAL, tracker.current(true))
        clock.now = MonotonicInstant(BootSessionId(1), 599)
        assertEquals(OrionMemoryPressure.CRITICAL, tracker.current(false))
        clock.now = MonotonicInstant(BootSessionId(1), 600)
        assertEquals(OrionMemoryPressure.NONE, tracker.current(false))
    }

    @Test fun `system pressure observed with missing boot identity is preserved on a later healthy sample`() {
        val tracker = OrionMemoryPressureTracker(clock, 500)
        clock.now = MonotonicInstant(null, 100)
        assertEquals(OrionMemoryPressure.UNKNOWN, tracker.current(true))
        clock.now = MonotonicInstant(BootSessionId(1), 1_000)
        assertEquals(OrionMemoryPressure.CRITICAL, tracker.current(false))
    }
}
