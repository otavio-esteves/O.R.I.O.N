package network.orion.core.model

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class OrionMemoryLifecycleTest {
    @Test fun validityIsHalfOpenAndUsesTheEarlierExpiration() {
        val start = Instant.ofEpochMilli(1_000)
        val expiration = Instant.ofEpochMilli(2_000)
        val lifecycle = OrionMemoryLifecycle(validFrom = start, validUntil = expiration.plusSeconds(1), expiresAt = expiration)

        assertFalse(lifecycle.isValidAt(start.minusMillis(1)))
        assertTrue(lifecycle.isValidAt(start))
        assertTrue(lifecycle.isValidAt(expiration.minusMillis(1)))
        assertFalse(lifecycle.isValidAt(expiration))
        assertEquals(OrionMemoryStatus.EXPIRED, lifecycle.stateAt(OrionMemoryStatus.ACTIVE, expiration))
        assertEquals(OrionMemoryStatus.EXPIRED, lifecycle.stateAt(OrionMemoryStatus.CANDIDATE, expiration))
        assertEquals(OrionMemoryStatus.DELETED, lifecycle.stateAt(OrionMemoryStatus.DELETED, expiration))
        assertEquals(OrionMemoryStatus.SUPERSEDED, lifecycle.stateAt(OrionMemoryStatus.SUPERSEDED, expiration))
    }

    @Test fun boundsMustSurviveMillisecondStorageAndImportanceIsFinite() {
        val start = Instant.ofEpochMilli(1_000)
        assertThrows(IllegalArgumentException::class.java) {
            OrionMemoryLifecycle(validFrom = start, validUntil = start.plusNanos(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrionMemoryLifecycle(validFrom = start, expiresAt = start.minusMillis(1))
        }
        for (importance in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
            assertThrows(IllegalArgumentException::class.java) { OrionMemoryLifecycle(importance = importance) }
        }
    }

    @Test fun detailsAreBoundedInBytesCopiedAndRedacted() {
        val tags = mutableListOf("origem")
        val details = OrionMemoryDetails(source = "private-source", tags = tags)
        tags.clear()
        assertEquals(listOf("origem"), details.tags)
        assertFalse(details.toString().contains("private-source"))
        assertThrows(IllegalArgumentException::class.java) { OrionMemoryDetails() }
        assertThrows(IllegalArgumentException::class.java) { OrionMemoryDetails(tags = listOf("é".repeat(33))) }
        assertThrows(IllegalArgumentException::class.java) { OrionMemoryDetails(tags = listOf("x", "x")) }
        assertThrows(IllegalArgumentException::class.java) { OrionMemoryDetails(summary = "\uD800") }
    }
}
