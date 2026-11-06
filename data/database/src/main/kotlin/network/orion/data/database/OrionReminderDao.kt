package network.orion.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/** MAIN-owned reminder metadata access. No scheduling or authorization. */
@Dao
abstract class OrionReminderDao {
    @Query("SELECT * FROM orion_reminders WHERE (:after IS NULL OR reminderId > :after) ORDER BY reminderId LIMIT :limit")
    abstract fun page(after: String?, limit: Int): List<ReminderEntity>
    @Query("SELECT * FROM orion_domain_event_outbox WHERE aggregateId = :id AND aggregateVersion = :version AND eventType = :type LIMIT 2")
    abstract fun historicalEvents(id: String, version: Long, type: String): List<DomainEventOutboxEntity>
    @Query("SELECT * FROM orion_reminders WHERE reminderId = :id")
    abstract fun find(id: String): ReminderEntity?
    @Query("""UPDATE orion_reminders SET scheduledAt = :scheduledAt, zoneId = :zoneId,
        precision = 'UNSCHEDULED', updatedAt = :at, version = version + 1
        WHERE reminderId = :id AND version = :version AND status = 'PENDING' AND :scheduledAt > scheduledAt""")
    protected abstract fun snooze(id: String, version: Long, scheduledAt: Long, zoneId: String, at: Long): Int
    @Insert protected abstract fun insertEvent(event: DomainEventOutboxEntity)
    @Query("SELECT count(*) FROM orion_domain_event_outbox") protected abstract fun eventCount(): Int

    @Transaction
    open fun snoozeWithEvent(id: String, version: Long, scheduledAt: Long, zoneId: String, at: Long,
        event: DomainEventOutboxEntity): ActionMutation {
        require(version in 0 until Long.MAX_VALUE)
        requireTechnicalUuid(id); requireTechnicalUuid(event.eventId); requireTechnicalUuid(event.correlationId)
        require(java.time.ZoneId.of(zoneId).id == zoneId)
        val row = find(id) ?: return ActionMutation.NOT_FOUND
        if (row.status != ReminderStatus.PENDING || row.version != version) return ActionMutation.CONFLICT
        require(event.aggregateId == id && event.aggregateVersion == version + 1 && event.eventType == "REMINDER_SNOOZED" &&
            event.occurredAt == at && event.payloadSensitivity == row.sensitivity && event.schemaVersion == 1 &&
            event.status == DomainEventDeliveryStatus.PENDING && event.attemptCount == 0 && event.version == 0L &&
            event.deliveredAt == null && event.nextAttemptAt == at)
        check(eventCount() < 10_000) { "Durable event capacity reached." }
        if (snooze(id, version, scheduledAt, zoneId, at) != 1) return ActionMutation.CONFLICT
        insertEvent(event)
        return ActionMutation.APPLIED
    }
}
