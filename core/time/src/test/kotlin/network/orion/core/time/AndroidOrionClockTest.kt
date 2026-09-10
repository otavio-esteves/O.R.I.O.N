package network.orion.core.time

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Injected source tests on the JVM; these do not execute Android platform APIs. */
class AndroidOrionClockTest {
    @Test
    fun `boot source is stable across instances and rereads changes without process UUIDs`() {
        var setting: String? = "42"
        val first = BootCountSource { setting }
        val recreated = BootCountSource { setting }
        assertEquals(BootSessionId(42), first.read())
        assertEquals(first.read(), recreated.read())
        setting = "43"
        assertEquals(BootSessionId(43), first.read())
        assertEquals(first.read(), recreated.read())
        setting = null
        assertNull(first.read())
        setting = "44"
        assertEquals(BootSessionId(44), first.read())
    }

    @Test
    fun `missing malformed negative and overflowing counts are unavailable`() {
        for (setting in listOf(null, "", " ", "unknown", "-1", "2147483648", "1.0")) {
            assertNull(BootCountSource { setting }.read())
        }
        assertEquals(BootSessionId(0), BootCountSource { "0" }.read())
        assertEquals(BootSessionId(Int.MAX_VALUE), BootCountSource { "2147483647" }.read())
    }

    @Test
    fun `access and provider failures do not retain a previously available identity`() {
        for (failure in listOf(SecurityException("private detail"), IllegalStateException("provider unavailable"))) {
            var fail = false
            val source = BootCountSource { if (fail) throw failure else "42" }
            assertEquals(BootSessionId(42), source.read())
            fail = true
            assertNull(source.read())
        }
    }

    @Test
    fun `adapter keeps sources independent and propagates unavailable identity`() {
        var wall = -1L
        var elapsed = 100L
        var count: String? = "42"
        var identityReads = 0
        val source = BootCountSource { identityReads++; count }
        val clock = AndroidOrionClock({ wall }, { elapsed }, source)
        assertEquals(0, identityReads)
        assertEquals(Instant.ofEpochMilli(-1), clock.wallNow())
        assertEquals(0, identityReads)
        val start = clock.monotonicNow()
        assertEquals(MonotonicInstant(BootSessionId(42), 100), start)
        wall = 100_000
        assertEquals(start, clock.monotonicNow())
        count = null
        elapsed = 200
        assertEquals(MonotonicInstant(null, 200), clock.monotonicNow())
        assertEquals(MonotonicDeadlineStatus.INVALID_REFERENCE, start.deadlineAfter(10).statusAt(clock.monotonicNow()))
        assertEquals(Instant.ofEpochMilli(100_000), clock.wallNow())
    }
}
