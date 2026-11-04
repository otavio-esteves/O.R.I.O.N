package network.orion.data.events

import org.junit.Assert.*
import org.junit.Test

class EventRetryPolicyTest {
    @Test fun exponentialBackoffIsBoundedAndJitteredWithoutOverflow() {
        val low = EventRetryPolicy(100, 1_000, 10_000) { 0.0 }
        val high = EventRetryPolicy(100, 1_000, 10_000) { 0.999 }
        assertEquals(1_500L, low.nextAttemptAt(1_000, 1))
        assertEquals(2_000L, low.nextAttemptAt(1_000, 2))
        assertEquals(6_000L, low.nextAttemptAt(1_000, 100))
        assertTrue(high.nextAttemptAt(1_000, 1) in 1_500..2_000)
        assertTrue(high.nextAttemptAt(1_000, 100) in 6_000..11_000)
        assertEquals(Long.MAX_VALUE, low.nextAttemptAt(Long.MAX_VALUE - 1, 100))
    }

    @Test fun invalidRetryConfigurationOrJitterFailsDeterministically() {
        assertThrows(IllegalArgumentException::class.java) { EventRetryPolicy(0) }
        assertThrows(IllegalArgumentException::class.java) { EventRetryPolicy(baseDelayMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) { EventRetryPolicy(maxDelayMillis = 10) }
        assertThrows(IllegalArgumentException::class.java) { EventRetryPolicy { Double.NaN }.nextAttemptAt(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { EventRetryPolicy().nextAttemptAt(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { EventRetryPolicy().nextAttemptAt(Long.MAX_VALUE, 1) }
    }
}
