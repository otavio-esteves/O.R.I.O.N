package network.orion.core.sentinel

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionProactivityHabitsTest {
    private val now = Instant.parse("2026-11-22T13:00:00Z")
    private val samples = (1L..3L).map { offset ->
        now.minus(Duration.ofDays(offset)) to OrionProactivityFeedbackKind.IGNORED
    }

    @Test fun learnsOnlyAfterEnoughOutcomesInTheCurrentSlot() {
        assertFalse(OrionProactivityHabits.shouldDefer(now, ZoneOffset.UTC, samples.take(2)))
        assertTrue(OrionProactivityHabits.shouldDefer(now, ZoneOffset.UTC, samples))
        assertFalse(OrionProactivityHabits.shouldDefer(now.plus(Duration.ofHours(4)), ZoneOffset.UTC, samples))
        assertFalse(OrionProactivityHabits.shouldDefer(now, ZoneOffset.UTC,
            samples.map { (at, _) -> at.plus(Duration.ofDays(4)) to OrionProactivityFeedbackKind.DISMISSED }))
    }

    @Test fun successfulOutcomesOffsetWeakNegativeFeedback() {
        val completed = samples.first().first to OrionProactivityFeedbackKind.COMPLETED
        assertFalse(OrionProactivityHabits.shouldDefer(now, ZoneOffset.UTC, samples.drop(1) + completed))
        val dismissed = completed.first to OrionProactivityFeedbackKind.DISMISSED
        assertTrue(OrionProactivityHabits.shouldDefer(now, ZoneOffset.UTC, samples.drop(1) + dismissed))
    }
}
