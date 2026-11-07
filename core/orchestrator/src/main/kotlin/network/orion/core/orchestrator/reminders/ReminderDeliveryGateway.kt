package network.orion.core.orchestrator.reminders

import java.time.Instant
import java.util.UUID
import java.time.LocalDateTime
import network.orion.core.model.OrionReminderRecurrenceRule

/** Exact one-shot generic alert. This selected preview is not an authorization credential. */
data class ReminderDeliveryPreview(
    val reminderId: UUID,
    val reminderVersion: Long,
    val generation: Long,
    val scheduledAt: Instant,
    val actionId: UUID,
    val actionVersion: Long,
    val confirmationFingerprint: String,
    val policyVersion: String,
) {
    override fun toString() = "ReminderDeliveryPreview(<redacted>)"
}

enum class ReminderDeliveryOutcome { ENABLED, REJECTED, UNAVAILABLE, QUEUED, POSTED, UNKNOWN }

sealed interface ReminderDeliveryPreparation {
    data class Ready(val preview: ReminderDeliveryPreview) : ReminderDeliveryPreparation
    data class Blocked(val outcome: ReminderDeliveryOutcome) : ReminderDeliveryPreparation
}

/** MAIN-owned explicit opt-in; creating a reminder alone never grants notification authority. */
interface ReminderDeliveryGateway {
    suspend fun processPending() = Unit
    suspend fun prepare(reminderId: UUID, expectedVersion: Long): ReminderDeliveryPreparation
    suspend fun confirm(preview: ReminderDeliveryPreview): ReminderDeliveryOutcome
    suspend fun prepareSeries(reminderId: UUID, expectedVersion: Long, rule: OrionReminderRecurrenceRule): ReminderSeriesPreparation =
        ReminderSeriesPreparation.Blocked(ReminderDeliveryOutcome.UNAVAILABLE)
    suspend fun confirmSeries(preview: ReminderSeriesPreview): ReminderDeliveryOutcome = ReminderDeliveryOutcome.UNAVAILABLE
}

data class ReminderSeriesOccurrence(val delivery: ReminderDeliveryPreview,
    val requestedLocalDateTime: LocalDateTime, val resolvedLocalDateTime: LocalDateTime,
    val offsetSeconds: Int, val decision: String)

data class ReminderSeriesPreview(val seriesId: UUID, val rule: OrionReminderRecurrenceRule,
    val occurrences: List<ReminderSeriesOccurrence>, val confirmationFingerprint: String) {
    override fun toString() = "ReminderSeriesPreview(<redacted>)"
}

sealed interface ReminderSeriesPreparation {
    data class Ready(val preview: ReminderSeriesPreview) : ReminderSeriesPreparation
    data class Blocked(val outcome: ReminderDeliveryOutcome) : ReminderSeriesPreparation
}
