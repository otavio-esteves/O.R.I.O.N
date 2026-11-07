package network.orion.core.model

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class OrionReminderRecurrenceRuleTest {
    private val start = LocalDateTime.parse("2026-11-07T09:30")
    private val rule = OrionReminderRecurrenceRule(OrionReminderRecurrenceFrequency.DAILY, 1, 3,
        start, ZoneId.of("America/Sao_Paulo"))

    @Test fun dailyAndWeeklyIntervalsAdvanceRequestedCivilTimeOnly() {
        assertEquals(LocalDateTime.parse("2026-11-08T09:30"), rule.nextAfter(start))
        assertEquals(LocalDateTime.parse("2026-11-09T09:30"), rule.copy(interval = 2).nextAfter(start))
        val weekly = rule.copy(frequency = OrionReminderRecurrenceFrequency.WEEKLY, interval = 3)
        val next = weekly.nextAfter(start)
        assertEquals(LocalDateTime.parse("2026-11-28T09:30"), next)
        assertEquals(start.dayOfWeek, next.dayOfWeek)
        assertEquals(start.toLocalTime(), next.toLocalTime())
    }

    @Test fun calendarProgressionHandlesLeapAndYearBoundariesWithoutRawClocks() {
        assertEquals(LocalDateTime.parse("2028-02-29T09:30"), rule.nextAfter(LocalDateTime.parse("2028-02-28T09:30")))
        assertEquals(LocalDateTime.parse("2027-01-01T09:30"), rule.nextAfter(LocalDateTime.parse("2026-12-31T09:30")))
    }

    @Test fun intervalAndFiniteCountBoundsRejectUnsupportedPlans() {
        for (interval in listOf(Int.MIN_VALUE, 0, 53, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { rule.copy(interval = interval) }
        }
        for (count in listOf(Int.MIN_VALUE, 0, 1, 32, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { rule.copy(count = count) }
        }
        assertEquals(2, rule.copy(interval = 52, count = 2).count)
        assertEquals(31, rule.copy(count = 31).count)
    }

    @Test fun overflowingCalendarArithmeticFailsRatherThanWrappingOrRepeating() {
        for (frequency in OrionReminderRecurrenceFrequency.entries) {
            assertThrows(DateTimeException::class.java) {
                rule.copy(frequency = frequency).nextAfter(LocalDateTime.MAX)
            }
        }
    }

    @Test fun canonicalVersionOneCodecRoundTripsEverySupportedFrequencyAndBoundary() {
        for (frequency in OrionReminderRecurrenceFrequency.entries) {
            for (interval in listOf(1, 52)) {
                for (count in listOf(2, 31)) {
                    val expected = rule.copy(frequency = frequency, interval = interval, count = count)
                    val encoded = OrionReminderRecurrenceCodec.encode(expected)
                    assertEquals(expected, OrionReminderRecurrenceCodec.decode(encoded))
                    assertEquals(encoded, OrionReminderRecurrenceCodec.encode(checkNotNull(OrionReminderRecurrenceCodec.decode(encoded))))
                }
            }
        }
        assertEquals("1|DAILY|1|3|2026-11-07T09:30|America/Sao_Paulo", OrionReminderRecurrenceCodec.encode(rule))
    }

    @Test fun codecPreservesCivilNanosecondsAndNamedOrFixedZoneIdentity() {
        for (zone in listOf("UTC", "Z", "+05:30", "America/New_York")) {
            val expected = rule.copy(startLocalDateTime = LocalDateTime.parse("2026-11-07T09:30:12.123456789"),
                zoneId = ZoneId.of(zone))
            assertEquals(expected, OrionReminderRecurrenceCodec.decode(OrionReminderRecurrenceCodec.encode(expected)))
        }
    }

    @Test fun malformedUnsupportedAndNoncanonicalFormsNeverDecode() {
        val invalid = listOf(
            "", "1", "1|DAILY|1|3|2026-11-07T09:30", "2|DAILY|1|3|2026-11-07T09:30|UTC",
            "1|MONTHLY|1|3|2026-11-07T09:30|UTC", "1|daily|1|3|2026-11-07T09:30|UTC",
            "1|DAILY|01|3|2026-11-07T09:30|UTC", "1|DAILY|+1|3|2026-11-07T09:30|UTC",
            "1|DAILY|1|03|2026-11-07T09:30|UTC", "1|DAILY|1|+3|2026-11-07T09:30|UTC",
            "1|DAILY|1|3|2026-11-07T09:30:00|UTC", "1|DAILY|1|3|2026-11-07T09:30|UTC+00:00",
            "1|DAILY|0|3|2026-11-07T09:30|UTC", "1|DAILY|53|3|2026-11-07T09:30|UTC",
            "1|DAILY|1|1|2026-11-07T09:30|UTC", "1|DAILY|1|32|2026-11-07T09:30|UTC",
            "1|DAILY|99999999999|3|2026-11-07T09:30|UTC", "1|DAILY|1|3|bad-date|UTC",
            "1|DAILY|1|3|2026-11-07T09:30|Bad/Zone", "1|DAILY|1|3|2026-11-07T09:30|UTC|extra",
            "1|DAILY|1|3|2026-11-07T09:30|UTC\n", "1|DAILY|1|3|2026-11-07T09:30|UTC|",
            "x".repeat(OrionReminderRecurrenceCodec.MAX_ENCODED_LENGTH + 1),
        )
        invalid.forEach { assertNull(it, OrionReminderRecurrenceCodec.decode(it)) }
    }
}
