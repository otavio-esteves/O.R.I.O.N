package network.orion.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class ReminderSeriesState { ACTIVE, CANCELLED, COMPLETED }

/** Current finite manifest only; historical authority remains in immutable child actions and audit. */
@Entity(tableName = "orion_reminder_series", primaryKeys = ["reminderId"],
    foreignKeys = [ForeignKey(entity = ReminderEntity::class, parentColumns = ["reminderId"],
        childColumns = ["reminderId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["seriesId"], unique = true)])
data class OrionReminderSeriesEntity(
    val reminderId: String,
    val seriesId: String,
    val ruleCanonical: String,
    val occurrenceCount: Int,
    val currentIndex: Int = 0,
    val state: ReminderSeriesState = ReminderSeriesState.ACTIVE,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val version: Long = 0,
) {
    override fun toString() = "OrionReminderSeriesEntity(<redacted>)"
}

/** Each row identifies one independently confirmed exact ActionRequest, never a recurring grant. */
@Entity(tableName = "orion_reminder_occurrences", primaryKeys = ["reminderId", "occurrenceIndex"],
    foreignKeys = [ForeignKey(entity = OrionReminderSeriesEntity::class, parentColumns = ["seriesId"],
        childColumns = ["seriesId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("seriesId"), Index(value = ["actionId"], unique = true)])
data class OrionReminderOccurrenceEntity(
    val reminderId: String,
    val occurrenceIndex: Int,
    val seriesId: String,
    val actionId: String,
    val generation: Long,
    val scheduledAt: Long,
    val requestedLocalDateTime: String,
    val resolvedLocalDateTime: String,
    val offsetSeconds: Int,
    val resolutionDecision: String,
) {
    override fun toString() = "OrionReminderOccurrenceEntity(<redacted>)"
}
