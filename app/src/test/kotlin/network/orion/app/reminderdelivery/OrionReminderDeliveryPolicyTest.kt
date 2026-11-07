package network.orion.app.reminderdelivery

import java.time.Duration
import java.time.Instant
import java.util.UUID
import network.orion.core.actions.ActionConfirmationBinding
import network.orion.core.actions.ConfirmationBindingResult
import network.orion.core.orchestrator.reminders.ReminderDeliveryPreview
import network.orion.core.skills.metadata.reminders.OrionDeliverReminderDescriptor
import network.orion.core.time.WallClock
import org.junit.Assert.*
import org.junit.Test

class OrionReminderDeliveryPolicyTest {
    @Test fun futureConsentExpiresAtExactBoundAndEveryMaterialReferenceChangesBinding() {
        val id = UUID.randomUUID()
        val due = Instant.parse("2026-11-08T10:00:00Z")
        var now = due.minusSeconds(60)
        val clock = WallClock { now }
        val binding = ActionConfirmationBinding(clock)
        val preview = ReminderDeliveryPreview(id, 0, 1, due, UUID.randomUUID(), 1, "",
            OrionDeliverReminderDescriptor.POLICY_VERSION)
        val normalized = OrionReminderDeliveryPolicy.normalized(preview)
        val expiry = OrionReminderDeliveryPolicy.expiresAt(due)
        assertEquals(Duration.ofHours(24), Duration.between(due, expiry))
        val receipt = binding.recordConfirmation(normalized, expiry)
        now = due
        assertEquals(ConfirmationBindingResult.VALID_BINDING, binding.validate(normalized, receipt))
        for (changed in listOf(preview.copy(reminderId = UUID.randomUUID()), preview.copy(generation = 2),
            preview.copy(scheduledAt = due.plusSeconds(1)), preview.copy(actionId = UUID.randomUUID()))) {
            assertEquals(ConfirmationBindingResult.CONFIRMATION_MISMATCH,
                binding.validate(OrionReminderDeliveryPolicy.normalized(changed), receipt))
        }
        now = expiry
        assertEquals(ConfirmationBindingResult.CONFIRMATION_EXPIRED, binding.validate(normalized, receipt))
    }
}
