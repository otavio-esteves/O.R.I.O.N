package network.orion.app.reminderdelivery

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import network.orion.core.actions.ActionConfirmationBinding
import network.orion.core.model.*
import network.orion.core.time.WallClock
import org.junit.Assert.*
import org.junit.Test

class OrionReminderSeriesPlanTest {
    private val rule = OrionReminderRecurrenceRule(OrionReminderRecurrenceFrequency.DAILY, 1, 3,
        LocalDateTime.parse("2026-03-07T02:45"), ZoneId.of("America/New_York"))
    private val now = Instant.parse("2026-03-01T00:00:00Z")
    @Test fun finiteExpansionAuditsGapAndPreservesRequestedCivilTimeAndExactIds() {
        val id = UUID.randomUUID()
        val plan = OrionReminderSeriesPlan.prepare(id, 0, 5, Instant.parse("2026-03-07T07:45:00Z"), rule, now)
        assertEquals(listOf(6L, 7L, 8L), plan.occurrences.map { it.delivery.generation })
        assertEquals(3, plan.occurrences.map { it.delivery.actionId }.distinct().size)
        assertEquals("GAP_FIRST_VALID_TIME", plan.occurrences[1].decision)
        assertEquals(LocalDateTime.parse("2026-03-08T03:00"), plan.occurrences[1].resolvedLocalDateTime)
        assertEquals(LocalDateTime.parse("2026-03-09T02:45"), plan.occurrences[2].requestedLocalDateTime)
        assertEquals(plan, OrionReminderSeriesPlan.prepare(id, 0, 5, Instant.parse("2026-03-07T07:45:00Z"), rule, now))
        assertNotEquals(plan.confirmationFingerprint, OrionReminderSeriesPlan.fingerprint(plan.copy(
            occurrences = plan.occurrences.reversed())))
    }
    @Test fun horizonOverflowAndMismatchedFirstInstantAreRejectedBeforeApproval() {
        assertThrows(IllegalArgumentException::class.java) {
            OrionReminderSeriesPlan.prepare(UUID.randomUUID(), 0, 0, Instant.parse("2026-03-07T07:46:00Z"), rule, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrionReminderSeriesPlan.prepare(UUID.randomUUID(), 0, Long.MAX_VALUE - 2,
                Instant.parse("2026-03-07T07:45:00Z"), rule, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrionReminderSeriesPlan.prepare(UUID.randomUUID(), 0, 0, Instant.parse("2026-03-07T07:45:00Z"),
                rule.copy(frequency = OrionReminderRecurrenceFrequency.WEEKLY, interval = 52), now)
        }
    }
    @Test fun batchWirePreservesEachOriginalReceiptAndRejectsTrailingData() {
        val plan = OrionReminderSeriesPlan.prepare(UUID.randomUUID(), 0, 0, Instant.parse("2026-03-07T07:45:00Z"), rule, now)
        val binding = ActionConfirmationBinding(WallClock { now })
        val prepared = plan.copy(occurrences = plan.occurrences.map { occurrence ->
            occurrence.copy(delivery = occurrence.delivery.copy(actionVersion = 1,
                confirmationFingerprint = binding.prepare(OrionReminderDeliveryPolicy.normalized(occurrence.delivery)).confirmationFingerprint))
        })
        val preview = prepared.copy(confirmationFingerprint = OrionReminderSeriesPlan.fingerprint(prepared))
        val receipts = preview.occurrences.map { binding.recordConfirmation(OrionReminderDeliveryPolicy.normalized(it.delivery),
            OrionReminderDeliveryPolicy.expiresAt(it.delivery.scheduledAt)) }
        val wire = OrionReminderSeriesWire(true, preview, receipts)
        assertEquals(wire, OrionReminderSeriesWire.decode(wire.encode()))
        assertEquals(3, receipts.map { it.actionId }.distinct().size)
        assertThrows(IllegalArgumentException::class.java) { OrionReminderSeriesWire.decode(wire.encode() + byteArrayOf(0)) }
    }
}
