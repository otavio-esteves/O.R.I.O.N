package network.orion.scheduler.reconciliation

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

/** JVM civil-time evidence only; does not qualify Android alarms or physical delivery. */
class OrionCivilRecurrenceTest {
    private val newYork = ZoneId.of("America/New_York")
    private val daily = RecurrenceRule { it.plusDays(1) }

    @Test fun `ordinary time resolves without changing civil fields`() {
        val local = LocalDateTime.parse("2026-01-15T09:30")
        val result = OrionCivilRecurrence.resolve(local, newYork)
        assertEquals(local, result.requestedLocalDateTime)
        assertEquals(local, result.resolvedLocalDateTime)
        assertEquals(newYork, result.zoneId)
        assertEquals(ZoneOffset.ofHours(-5), result.offset)
        assertEquals(Instant.parse("2026-01-15T14:30:00Z"), result.instant)
        assertEquals(CivilTimeDecision.NORMAL, result.decision)
    }

    @Test fun `gap shifts to first valid local time instead of adding gap length`() {
        val result = OrionCivilRecurrence.resolve(LocalDateTime.parse("2026-03-08T02:45:12"), newYork)
        assertEquals(LocalDateTime.parse("2026-03-08T02:45:12"), result.requestedLocalDateTime)
        assertEquals(LocalDateTime.parse("2026-03-08T03:00"), result.resolvedLocalDateTime)
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), result.instant)
        assertEquals(CivilTimeDecision.GAP_FIRST_VALID_TIME, result.decision)
    }

    @Test fun `half-hour gap uses actual transition boundary`() {
        val result = OrionCivilRecurrence.resolve(LocalDateTime.parse("2026-10-04T02:15"), ZoneId.of("Australia/Lord_Howe"))
        assertEquals(LocalDateTime.parse("2026-10-04T02:30"), result.resolvedLocalDateTime)
        assertEquals(ZoneOffset.ofHours(11), result.offset)
        assertEquals(CivilTimeDecision.GAP_FIRST_VALID_TIME, result.decision)
    }

    @Test fun `exact gap end is normal and nanoseconds inside gap do not survive shift`() {
        val end = OrionCivilRecurrence.resolve(LocalDateTime.parse("2026-03-08T03:00"), newYork)
        val gap = OrionCivilRecurrence.resolve(LocalDateTime.parse("2026-03-08T02:59:59.999999999"), newYork)
        assertEquals(end.instant, gap.instant)
        assertEquals(CivilTimeDecision.NORMAL, end.decision)
        assertEquals(0, gap.resolvedLocalDateTime.nano)
    }

    @Test fun `whole skipped civil day uses first valid day`() {
        val result = OrionCivilRecurrence.resolve(LocalDateTime.parse("2011-12-30T12:00"), ZoneId.of("Pacific/Apia"))
        assertEquals(LocalDateTime.parse("2011-12-31T00:00"), result.resolvedLocalDateTime)
        assertEquals(CivilTimeDecision.GAP_FIRST_VALID_TIME, result.decision)
    }

    @Test fun `overlap without prior offset chooses earlier occurrence`() {
        val result = OrionCivilRecurrence.resolve(LocalDateTime.parse("2026-11-01T01:30"), newYork)
        assertEquals(ZoneOffset.ofHours(-4), result.offset)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), result.instant)
        assertEquals(CivilTimeDecision.OVERLAP_EARLIER_OFFSET, result.decision)
    }

    @Test fun `overlap preserves either valid previous offset`() {
        for (hours in listOf(-4, -5)) {
            val offset = ZoneOffset.ofHours(hours)
            val result = OrionCivilRecurrence.resolve(LocalDateTime.parse("2026-11-01T01:30"), newYork, offset)
            assertEquals(offset, result.offset)
            assertEquals(CivilTimeDecision.OVERLAP_PREVIOUS_OFFSET, result.decision)
        }
    }

    @Test fun `overlap rejects unavailable prior offset and records earlier fallback`() {
        val result = OrionCivilRecurrence.resolve(LocalDateTime.parse("2026-11-01T01:30"), newYork, ZoneOffset.UTC)
        assertEquals(ZoneOffset.ofHours(-4), result.offset)
        assertEquals(CivilTimeDecision.OVERLAP_EARLIER_OFFSET, result.decision)
    }

    @Test fun `daily recurrence preserves requested time through spring change`() {
        val before = LocalDateTime.parse("2026-03-07T02:45")
        val gap = found(OrionCivilRecurrence.nextOccurrence(before, newYork, daily,
            Instant.parse("2026-03-07T07:45:00Z"), ZoneOffset.ofHours(-5)))
        val next = found(OrionCivilRecurrence.nextOccurrence(gap.requestedLocalDateTime, newYork, daily,
            gap.instant, gap.offset))
        assertEquals(LocalDateTime.parse("2026-03-09T02:45"), next.requestedLocalDateTime)
        assertEquals(Instant.parse("2026-03-09T06:45:00Z"), next.instant)
    }

    @Test fun `weekly recurrence changes UTC instant across DST`() {
        val result = found(OrionCivilRecurrence.nextOccurrence(LocalDateTime.parse("2026-03-01T09:00"), newYork,
            RecurrenceRule { it.plusWeeks(1) }, Instant.parse("2026-03-01T14:00:00Z")))
        assertEquals(LocalDateTime.parse("2026-03-08T09:00"), result.resolvedLocalDateTime)
        assertEquals(Instant.parse("2026-03-08T13:00:00Z"), result.instant)
    }

    @Test fun `daily overlap never replays the second offset of same civil occurrence`() {
        val result = found(OrionCivilRecurrence.nextOccurrence(LocalDateTime.parse("2026-10-31T01:30"), newYork,
            daily, Instant.parse("2026-11-01T05:30:00Z"), ZoneOffset.ofHours(-4)))
        assertEquals(LocalDateTime.parse("2026-11-02T01:30"), result.requestedLocalDateTime)
    }

    @Test fun `gap candidates mapping to same instant are not delivered twice`() {
        val result = found(OrionCivilRecurrence.nextOccurrence(LocalDateTime.parse("2026-03-08T02:30"), newYork,
            RecurrenceRule { it.plusMinutes(15) }, Instant.parse("2026-03-08T07:00:00Z"), maxCandidates = 4))
        assertEquals(LocalDateTime.parse("2026-03-08T03:15"), result.requestedLocalDateTime)
        assertTrue(result.instant > Instant.parse("2026-03-08T07:00:00Z"))
    }

    @Test fun `next occurrence skips candidates equal to cutoff and preserves actual prior offset`() {
        val result = found(OrionCivilRecurrence.nextOccurrence(LocalDateTime.parse("2026-01-01T01:30"), newYork,
            daily, Instant.parse("2026-10-31T05:30:00Z"), ZoneOffset.ofHours(-5)))
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), result.instant)
        assertEquals(CivilTimeDecision.OVERLAP_PREVIOUS_OFFSET, result.decision)
    }

    @Test fun `leap day and year boundaries follow civil calendar`() {
        for ((start, expected) in listOf("2028-02-28T10:00" to "2028-02-29T10:00",
            "2026-12-31T10:00" to "2027-01-01T10:00")) {
            val local = LocalDateTime.parse(start)
            val result = found(OrionCivilRecurrence.nextOccurrence(local, ZoneOffset.UTC, daily, local.toInstant(ZoneOffset.UTC)))
            assertEquals(LocalDateTime.parse(expected), result.requestedLocalDateTime)
        }
    }

    @Test fun `explicit timezone changes UTC result without changing requested civil time`() {
        val local = LocalDateTime.parse("2026-06-01T10:00")
        assertEquals(Instant.parse("2026-06-01T13:00:00Z"), OrionCivilRecurrence.resolve(local, ZoneId.of("America/Sao_Paulo")).instant)
        assertEquals(Instant.parse("2026-06-01T01:00:00Z"), OrionCivilRecurrence.resolve(local, ZoneId.of("Asia/Tokyo")).instant)
    }

    @Test fun `clock forward catch-up is bounded and rule ending is explicit`() {
        var calls = 0
        val start = LocalDateTime.parse("2026-01-01T10:00")
        val bounded = OrionCivilRecurrence.nextOccurrence(start, ZoneOffset.UTC,
            RecurrenceRule { calls++; it.plusDays(1) }, Instant.parse("2030-01-01T00:00:00Z"), maxCandidates = 3)
        assertEquals(CivilOccurrenceSearch.SearchLimitReached, bounded)
        assertEquals(3, calls)
        assertEquals(CivilOccurrenceSearch.RuleEnded, OrionCivilRecurrence.nextOccurrence(start,
            ZoneOffset.UTC, RecurrenceRule { null }, Instant.EPOCH))
    }

    @Test fun `clock rollback still advances civil occurrence`() {
        val result = found(OrionCivilRecurrence.nextOccurrence(LocalDateTime.parse("2026-06-01T10:00"),
            ZoneOffset.UTC, daily, Instant.parse("2020-01-01T00:00:00Z")))
        assertEquals(LocalDateTime.parse("2026-06-02T10:00"), result.requestedLocalDateTime)
    }

    @Test fun `rollback cannot replay a different gap candidate at same previous instant`() {
        val previous = OrionCivilRecurrence.resolve(LocalDateTime.parse("2026-03-08T02:30"), newYork)
        val rule = RecurrenceRule { it.plusMinutes(15) }
        val rollback = Instant.parse("2026-03-08T06:00:00Z")
        val typed = found(OrionCivilRecurrence.nextOccurrence(previous, newYork, rule, rollback))
        val convenience = found(OrionCivilRecurrence.nextOccurrence(previous.requestedLocalDateTime,
            newYork, rule, rollback, previous.offset))
        assertEquals(Instant.parse("2026-03-08T07:15:00Z"), typed.instant)
        assertEquals(typed, convenience)
        assertTrue(typed.instant > previous.instant)
    }

    @Test fun `timezone reconciliation compares actual previous instant without reinterpreting history`() {
        val previous = OrionCivilRecurrence.resolve(LocalDateTime.parse("2026-06-01T10:00"), ZoneId.of("Asia/Tokyo"))
        val result = found(OrionCivilRecurrence.nextOccurrence(previous, newYork,
            RecurrenceRule { it.plusHours(1) }, Instant.parse("2026-06-01T00:00:00Z")))
        assertEquals(Instant.parse("2026-06-01T15:00:00Z"), result.instant)
        assertEquals(LocalDateTime.parse("2026-06-01T11:00"), result.requestedLocalDateTime)
        assertTrue(result.instant > previous.instant)
    }

    @Test fun `timezone change eastward skips candidates older than actual previous instant`() {
        val previous = OrionCivilRecurrence.resolve(LocalDateTime.parse("2026-06-01T10:00"), newYork)
        val result = found(OrionCivilRecurrence.nextOccurrence(previous, ZoneId.of("Asia/Tokyo"),
            RecurrenceRule { it.plusHours(1) }, Instant.parse("2026-06-01T00:00:00Z")))
        assertEquals(Instant.parse("2026-06-01T15:00:00Z"), result.instant)
        assertEquals(LocalDateTime.parse("2026-06-02T00:00"), result.requestedLocalDateTime)
    }

    @Test fun `invalid candidate limits and nonadvancing rules fail deterministically`() {
        val local = LocalDateTime.parse("2026-01-01T00:00")
        for (limit in listOf(Int.MIN_VALUE, 0, OrionCivilRecurrence.MAX_CANDIDATES + 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                OrionCivilRecurrence.nextOccurrence(local, ZoneOffset.UTC, daily, Instant.EPOCH, maxCandidates = limit)
            }
        }
        for (rule in listOf(RecurrenceRule { it }, RecurrenceRule { it.minusDays(1) })) {
            assertThrows(IllegalArgumentException::class.java) {
                OrionCivilRecurrence.nextOccurrence(local, ZoneOffset.UTC, rule, Instant.EPOCH)
            }
        }
    }

    private fun found(result: CivilOccurrenceSearch): CivilOccurrence = (result as CivilOccurrenceSearch.Found).occurrence
}
