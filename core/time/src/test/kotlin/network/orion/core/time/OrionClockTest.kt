package network.orion.core.time

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OrionClockTest {
    private val boot = BootSessionId(42)
    private val epoch = Instant.parse("2026-01-01T00:00:00Z")

    private class FakeClock(
        var civil: Instant,
        var monotonic: MonotonicInstant,
    ) : OrionClock {
        override fun wallNow() = civil
        override fun monotonicNow() = monotonic
    }

    @Test
    fun `civil rollback and forward do not advance monotonic time`() {
        val clock = FakeClock(epoch, MonotonicInstant(boot, 100))
        val start = clock.monotonicNow()
        val deadline = start.deadlineAfter(20)
        for (offset in listOf(-86_400L, 86_400L, 0L)) {
            clock.civil = epoch.plusSeconds(offset)
            assertEquals(epoch.plusSeconds(offset), clock.wallNow())
            assertEquals(Duration.ZERO, clock.monotonicNow().elapsedSince(start))
            assertEquals(MonotonicDeadlineStatus.ACTIVE, deadline.statusAt(clock.monotonicNow()))
        }
        clock.monotonic = MonotonicInstant(boot, 120)
        assertEquals(epoch, clock.wallNow())
        assertEquals(Duration.ofMillis(20), clock.monotonicNow().elapsedSince(start))
        assertEquals(MonotonicDeadlineStatus.EXPIRED, deadline.statusAt(clock.monotonicNow()))
    }

    @Test
    fun `deadline is active before and expired at or after its boundary`() {
        val deadline = MonotonicInstant(boot, 100).deadlineAfter(20)
        for (now in listOf(100L, 119L)) {
            assertEquals(MonotonicDeadlineStatus.ACTIVE, deadline.statusAt(MonotonicInstant(boot, now)))
        }
        for (now in listOf(120L, 121L, Long.MAX_VALUE)) {
            assertEquals(MonotonicDeadlineStatus.EXPIRED, deadline.statusAt(MonotonicInstant(boot, now)))
        }
        val start = MonotonicInstant(boot, 100)
        assertEquals(MonotonicDeadlineStatus.EXPIRED, start.deadlineAfter(0).statusAt(start))
    }

    @Test
    fun `recreated identity in same boot validates a reconstructed reference`() {
        val persistedStart = MonotonicInstant(BootSessionId(42), 100)
        val recreatedNow = MonotonicInstant(BootSessionId(42), 110)
        assertEquals(Duration.ofMillis(10), recreatedNow.elapsedSince(persistedStart))
        assertEquals(MonotonicDeadlineStatus.ACTIVE, persistedStart.deadlineAfter(20).statusAt(recreatedNow))
    }

    @Test
    fun `another boot is rejected even when its elapsed time exceeds old deadline`() {
        val start = MonotonicInstant(boot, 100)
        for (elapsed in listOf(0L, 110L, 500L)) {
            val now = MonotonicInstant(BootSessionId(43), elapsed)
            assertNull(now.elapsedSince(start))
            assertEquals(MonotonicDeadlineStatus.INVALID_REFERENCE, start.deadlineAfter(20).statusAt(now))
        }
    }

    @Test
    fun `missing identity on either side never validates including null against null`() {
        for ((oldBoot, newBoot) in listOf(boot to null, null to boot, null to null)) {
            val start = MonotonicInstant(oldBoot, 100)
            val now = MonotonicInstant(newBoot, 200)
            assertNull(now.elapsedSince(start))
            assertEquals(MonotonicDeadlineStatus.INVALID_REFERENCE, start.deadlineAfter(20).statusAt(now))
        }
    }

    @Test
    fun `monotonic regression is invalid rather than a negative duration or active deadline`() {
        val start = MonotonicInstant(boot, 100)
        val now = MonotonicInstant(boot, 99)
        assertNull(now.elapsedSince(start))
        assertEquals(MonotonicDeadlineStatus.INVALID_REFERENCE, start.deadlineAfter(20).statusAt(now))
    }

    @Test
    fun `invalid inputs and arithmetic overflow are rejected`() {
        for (value in listOf(-1L, Long.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { MonotonicInstant(boot, value) }
            assertThrows(IllegalArgumentException::class.java) { MonotonicInstant(boot, 0).deadlineAfter(value) }
        }
        assertThrows(IllegalArgumentException::class.java) { BootSessionId(-1) }
        assertThrows(IllegalArgumentException::class.java) { BootSessionId(Int.MIN_VALUE) }
        assertThrows(ArithmeticException::class.java) { MonotonicInstant(boot, 1).deadlineAfter(Long.MAX_VALUE) }
        val start = MonotonicInstant(BootSessionId(0), 0)
        val end = MonotonicInstant(BootSessionId(0), Long.MAX_VALUE)
        assertEquals(Duration.ofMillis(Long.MAX_VALUE), end.elapsedSince(start))
        assertEquals(MonotonicDeadlineStatus.EXPIRED, start.deadlineAfter(Long.MAX_VALUE).statusAt(end))
        assertEquals(Int.MAX_VALUE, BootSessionId(Int.MAX_VALUE).bootCount)
    }

    @Test
    fun `zone projection and DST transitions preserve instants and monotonic elapsed time`() {
        val zone = ZoneId.of("America/New_York")
        // These are projections of already resolved instants, not a recurrence resolver.
        for ((before, after, hours) in listOf(
            Triple("2026-03-08T06:59:00Z", "2026-03-08T07:01:00Z", 1 to 3),
            Triple("2026-11-01T05:59:00Z", "2026-11-01T06:01:00Z", 1 to 1),
        )) {
            val clock = FakeClock(Instant.parse(before), MonotonicInstant(boot, 0))
            val start = clock.monotonicNow()
            assertEquals(hours.first, clock.wallNow().atZone(zone).hour)
            for (projection in listOf(zone, ZoneId.of("UTC"), ZoneId.of("Asia/Tokyo"))) {
                assertEquals(clock.wallNow(), clock.wallNow().atZone(projection).toInstant())
                assertEquals(start, clock.monotonicNow())
            }
            clock.civil = Instant.parse(after)
            clock.monotonic = MonotonicInstant(boot, 120_000)
            assertEquals(hours.second, clock.wallNow().atZone(zone).hour)
            assertEquals(Duration.ofMinutes(2), clock.monotonicNow().elapsedSince(start))
        }
    }
}
