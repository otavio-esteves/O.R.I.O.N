package network.orion.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import network.orion.core.model.Sensitivity

/** Local persistence only; exposing this DAO never grants command/Skill authority. */
@Dao
abstract class OrionTaskDao {
    @Query("SELECT * FROM orion_tasks WHERE taskId = :id")
    abstract fun find(id: String): TaskEntity?

    @Insert
    protected abstract fun insert(task: TaskEntity)

    @Insert
    protected abstract fun insertEvent(event: DomainEventOutboxEntity)

    @Query("SELECT count(*) FROM orion_domain_event_outbox")
    protected abstract fun eventCount(): Int

    @Query("""UPDATE orion_tasks SET status = :status, updatedAt = :at, version = version + 1
        WHERE taskId = :id AND version = :version AND status = 'OPEN'""")
    protected abstract fun updateStatus(id: String, version: Long, status: TaskStatus, at: Long): Int

    @Transaction
    open fun createWithEvent(task: TaskEntity, event: DomainEventOutboxEntity) {
        requireTechnicalUuid(task.taskId)
        require(task.contentEnvelope.isNotEmpty() && task.contentEnvelope.size <= MAX_DURABLE_FIELD_BYTES)
        require(task.version == 0L && task.status == TaskStatus.OPEN && task.createdAt == task.updatedAt)
        requireTaskEvent(task.taskId, 0, task.createdAt, "TASK_CREATED", task.sensitivity, event)
        check(eventCount() < 10_000) { "Durable event capacity reached." }
        insert(task)
        insertEvent(event)
    }

    @Transaction
    open fun changeStatusWithEvent(id: String, expectedVersion: Long, status: TaskStatus, at: Long,
        event: DomainEventOutboxEntity): ActionMutation {
        require(expectedVersion >= 0 && expectedVersion < Long.MAX_VALUE)
        require(status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED)
        val current = find(id) ?: return ActionMutation.NOT_FOUND
        if (current.version != expectedVersion || current.status != TaskStatus.OPEN) return ActionMutation.CONFLICT
        requireTaskEvent(id, expectedVersion + 1, at,
            if (status == TaskStatus.COMPLETED) "TASK_COMPLETED" else "TASK_CANCELLED", current.sensitivity, event)
        check(eventCount() < 10_000) { "Durable event capacity reached." }
        if (updateStatus(id, expectedVersion, status, at) != 1) return ActionMutation.CONFLICT
        insertEvent(event)
        return ActionMutation.APPLIED
    }

    private fun requireTaskEvent(id: String, version: Long, at: Long, type: String, sensitivity: Sensitivity, event: DomainEventOutboxEntity) {
        requireTechnicalUuid(event.eventId)
        requireTechnicalUuid(event.correlationId)
        require(event.aggregateId == id && event.aggregateVersion == version && event.occurredAt == at && event.eventType == type)
        require(event.payloadSensitivity == sensitivity)
        require(event.schemaVersion == 1 && event.status == DomainEventDeliveryStatus.PENDING && event.attemptCount == 0 &&
            event.version == 0L && event.deliveredAt == null && event.nextAttemptAt == at)
    }
}
