package network.orion.app.reminderdelivery

import java.time.Duration
import java.time.Instant
import java.util.UUID
import network.orion.core.actions.NormalizedActionRequest
import network.orion.core.orchestrator.reminders.ReminderDeliveryPreview
import network.orion.core.skills.metadata.reminders.OrionDeliverReminderDescriptor

/** Explicit one-shot generic notification consent. Lateness is bounded to 24h; no recurrence is authorized. */
internal object OrionReminderDeliveryPolicy {
    val MAX_FUTURE: Duration = Duration.ofDays(365)
    val MAX_LATENESS: Duration = Duration.ofDays(1)
    const val TITLE = "O.R.I.O.N."
    const val BODY = "Você tem um lembrete."
    const val CHANNEL = "orion_reminders"
    fun expiresAt(scheduledAt: Instant): Instant = scheduledAt.plus(MAX_LATENESS)
    fun canonical(id: UUID, generation: Long, at: Instant): String =
        "{\"reminderId\":\"$id\",\"generation\":$generation,\"scheduledAt\":${at.toEpochMilli()}}"
    fun normalized(preview: ReminderDeliveryPreview): NormalizedActionRequest {
        val descriptor = OrionDeliverReminderDescriptor.value
        return NormalizedActionRequest(preview.actionId, descriptor.name,
            canonical(preview.reminderId, preview.generation, preview.scheduledAt), "reminder/${preview.reminderId}",
            descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
            descriptor.recoveryStrategy, descriptor.idempotencyMode, preview.policyVersion)
    }
}
