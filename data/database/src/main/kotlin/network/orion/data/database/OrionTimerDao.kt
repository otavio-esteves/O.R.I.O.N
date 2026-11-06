package network.orion.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import network.orion.core.model.Sensitivity

/** Local persistence only; exposing this DAO never grants command/Skill authority. */
@Dao
abstract class OrionTimerDao {
    @Query("SELECT * FROM orion_timers WHERE (:after IS NULL OR timerId > :after) ORDER BY timerId LIMIT :limit")
    abstract fun page(after: String?, limit: Int): List<TimerEntity>

    @Query("SELECT * FROM orion_timers WHERE timerId = :id")
    abstract fun find(id: String): TimerEntity?

    @Query("SELECT count(*) FROM orion_timers")
    protected abstract fun timerCount(): Int

    @Insert
    protected abstract fun insert(timer: TimerEntity)

    @Insert
    protected abstract fun insertEvent(event: DomainEventOutboxEntity)

    @Query("SELECT count(*) FROM orion_domain_event_outbox")
    protected abstract fun eventCount(): Int

    @Query("""UPDATE orion_timers SET status = :status, updatedAt = :at, version = version + 1
        WHERE timerId = :id AND version = :version AND status = 'ACTIVE'""")
    protected abstract fun updateStatus(id: String, version: Long, status: TimerStatus, at: Long): Int

    @Transaction
    open fun createWithEvent(timer: TimerEntity, event: DomainEventOutboxEntity) {
        requireTechnicalUuid(timer.timerId)
        require(timer.contentEnvelope.isNotEmpty() && timer.contentEnvelope.size <= MAX_DURABLE_FIELD_BYTES)
        require(timer.durationMillis > 0 && timer.bootSessionId >= 0 && timer.startedElapsedRealtime >= 0)
        require(Math.addExact(timer.startedWallAt, timer.durationMillis) == timer.deadlineWallAt)
        Math.addExact(timer.startedElapsedRealtime, timer.durationMillis)
        require(timer.version == 0L && timer.status == TimerStatus.ACTIVE && timer.createdAt == timer.updatedAt)
        requireTimerEvent(timer.timerId, 0, timer.createdAt, "TIMER_CREATED", timer.sensitivity, event)
        check(eventCount() < 10_000) { "Durable event capacity reached." }
        check(timerCount() < 10_000) { "Timer capacity reached." }
        insert(timer)
        insertEvent(event)
    }

    @Transaction
    open fun changeStatusWithEvent(id: String, expectedVersion: Long, status: TimerStatus, at: Long,
        event: DomainEventOutboxEntity): ActionMutation {
        require(expectedVersion >= 0 && expectedVersion < Long.MAX_VALUE)
        require(status == TimerStatus.CANCELLED)
        val current = find(id) ?: return ActionMutation.NOT_FOUND
        if (current.version != expectedVersion || current.status != TimerStatus.ACTIVE) return ActionMutation.CONFLICT
        requireTimerEvent(id, expectedVersion + 1, at,
            "TIMER_CANCELLED", current.sensitivity, event)
        check(eventCount() < 10_000) { "Durable event capacity reached." }
        if (updateStatus(id, expectedVersion, status, at) != 1) return ActionMutation.CONFLICT
        insertEvent(event)
        return ActionMutation.APPLIED
    }

    private fun requireTimerEvent(id: String, version: Long, at: Long, type: String, sensitivity: Sensitivity, event: DomainEventOutboxEntity) {
        requireTechnicalUuid(event.eventId)
        requireTechnicalUuid(event.correlationId)
        require(event.aggregateId == id && event.aggregateVersion == version && event.occurredAt == at && event.eventType == type)
        require(event.payloadSensitivity == sensitivity)
        require(event.schemaVersion == 1 && event.status == DomainEventDeliveryStatus.PENDING && event.attemptCount == 0 &&
            event.version == 0L && event.deliveredAt == null && event.nextAttemptAt == at)
    }
}
