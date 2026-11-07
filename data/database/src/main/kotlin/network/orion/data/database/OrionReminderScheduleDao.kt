package network.orion.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import network.orion.core.actions.ActionStatus

/** MAIN metadata only. No DAO operation invokes Android or grants execution authority. */
@Dao
abstract class OrionReminderScheduleDao {
    @Query("""SELECT * FROM orion_reminders WHERE status = 'PENDING' AND deliveryActionId IS NOT NULL
        AND (:after IS NULL OR reminderId > :after) ORDER BY reminderId LIMIT :limit""")
    protected abstract fun pendingPage(after: String?, limit: Int): List<ReminderEntity>

    @Query("""SELECT * FROM orion_reminders WHERE lastScheduledAt IS NOT NULL
        AND (:after IS NULL OR reminderId > :after) ORDER BY reminderId LIMIT :limit""")
    protected abstract fun registeredPage(after: String?, limit: Int): List<ReminderEntity>

    @Query("""SELECT reminder.* FROM orion_reminders AS reminder
        INNER JOIN orion_action_requests AS owned_action ON owned_action.actionId = reminder.deliveryActionId
        WHERE reminder.status = 'PENDING' AND owned_action.status = 'CONFIRMED' AND owned_action.skill = 'reminder.deliver'
        AND reminder.scheduledAt <= :now AND (:after IS NULL OR reminder.reminderId > :after)
        ORDER BY reminder.reminderId LIMIT :limit""")
    protected abstract fun confirmedDuePage(after: String?, now: Long, limit: Int): List<ReminderEntity>

    fun schedulingPage(after: String? = null, limit: Int = 50): List<ReminderEntity> {
        require(limit in 1..100)
        if (after != null) requireTechnicalUuid(after)
        return pendingPage(after, limit)
    }

    /** Includes cancelled/invalidated metadata so MAIN can discard obsolete OS artifacts. */
    fun artifactPage(after: String? = null, limit: Int = 50): List<ReminderEntity> {
        require(limit in 1..100)
        if (after != null) requireTechnicalUuid(after)
        return registeredPage(after, limit)
    }

    /** Due facts are not authority: the runner must still validate consent, ownership and policy. */
    fun dueDeliveryPage(after: String? = null, now: Long, limit: Int = 50): List<ReminderEntity> {
        require(limit in 1..100)
        if (after != null) requireTechnicalUuid(after)
        return confirmedDuePage(after, now, limit)
    }

    @Query("SELECT * FROM orion_reminders WHERE reminderId = :id")
    abstract fun find(id: String): ReminderEntity?

    @Query("SELECT * FROM orion_action_requests WHERE actionId = :id")
    protected abstract fun action(id: String): ActionRequestEntity?

    @Query("SELECT count(*) FROM orion_domain_event_outbox")
    protected abstract fun eventCount(): Int

    @Insert protected abstract fun insertEvent(event: DomainEventOutboxEntity)

    @Query("""UPDATE orion_reminders SET deliveryActionId = :actionId, fireGeneration = :generation,
        precision = 'UNSCHEDULED', scheduleMethod = 'UNSCHEDULED', registrationConfirmed = 0,
        localDateTime = NULL, resolutionDecision = NULL,
        resolvedOffsetSeconds = NULL, updatedAt = :at, version = version + 1
        WHERE reminderId = :id AND status = 'PENDING' AND version = :version
        AND fireGeneration = :priorGeneration""")
    protected abstract fun bind(id: String, version: Long, priorGeneration: Long, generation: Long, actionId: String, at: Long): Int

    /** Caller commits confirmation and binding together in its admitted MAIN writer transaction. */
    @Transaction
    open fun bindDelivery(id: String, expectedVersion: Long, generation: Long, deliveryActionId: String, at: Long): ActionMutation {
        validateBinding(id, expectedVersion, generation, deliveryActionId)
        val row = find(id) ?: return ActionMutation.NOT_FOUND
        if (row.version != expectedVersion || row.status != ReminderStatus.PENDING ||
            row.fireGeneration == Long.MAX_VALUE || generation != row.fireGeneration + 1) return ActionMutation.CONFLICT
        val owner = action(deliveryActionId) ?: return ActionMutation.CONFLICT
        if (owner.skill != DELIVERY_SKILL || owner.status != ActionStatus.CONFIRMED) return ActionMutation.CONFLICT
        ensureEventCapacity()
        if (bind(id, expectedVersion, row.fireGeneration, generation, deliveryActionId, at) != 1) return ActionMutation.CONFLICT
        insertEvent(event(row, "REMINDER_DELIVERY_CONFIRMED", at, owner.correlationId))
        return ActionMutation.APPLIED
    }

    @Query("""UPDATE orion_reminders SET precision = :precision, scheduleMethod = :method, registrationConfirmed = 0,
        requestCode = :requestCode, lastScheduledAt = :at, lastScheduledGeneration = :generation,
        localDateTime = CASE WHEN recurrenceRule IS NULL THEN :localDateTime ELSE localDateTime END,
        resolvedOffsetSeconds = CASE WHEN recurrenceRule IS NULL THEN :offsetSeconds ELSE resolvedOffsetSeconds END,
        resolutionDecision = CASE WHEN recurrenceRule IS NULL THEN :decision ELSE resolutionDecision END,
        updatedAt = :at, version = version + 1
        WHERE reminderId = :id AND status = 'PENDING' AND version = :version
        AND fireGeneration = :generation AND deliveryActionId = :actionId""")
    protected abstract fun schedule(id: String, version: Long, generation: Long, actionId: String,
        precision: ReminderPrecision, method: ReminderScheduleMethod, requestCode: Int, at: Long,
        localDateTime: String, offsetSeconds: Int, decision: String): Int

    /** Persist a selected delivery mechanism before Android registration; replay retains the same generation. */
    @Transaction
    open fun prepareSchedule(id: String, expectedVersion: Long, generation: Long, deliveryActionId: String,
        precision: ReminderPrecision, method: ReminderScheduleMethod, requestCode: Int, at: Long,
        localDateTime: String, offsetSeconds: Int, resolutionDecision: String): ActionMutation {
        validateBinding(id, expectedVersion, generation, deliveryActionId)
        require((precision == ReminderPrecision.EXACT && method == ReminderScheduleMethod.EXACT_ALARM) ||
            (precision == ReminderPrecision.APPROXIMATE && method == ReminderScheduleMethod.INEXACT_ALARM))
        require(requestCode >= 0)
        val local = LocalDateTime.parse(localDateTime)
        require(local.toString() == localDateTime)
        val offset = ZoneOffset.ofTotalSeconds(offsetSeconds)
        require(resolutionDecision in setOf("NORMAL", "GAP_FIRST_VALID_TIME", "OVERLAP_PREVIOUS_OFFSET", "OVERLAP_EARLIER_OFFSET"))
        val row = matching(id, expectedVersion, generation, deliveryActionId) ?: return conflictOrMissing(id)
        require(local.toInstant(offset).toEpochMilli() == row.scheduledAt) { "Resolved civil time must match the bound instant." }
        val owner = action(deliveryActionId) ?: return ActionMutation.CONFLICT
        if (owner.skill != DELIVERY_SKILL || owner.status != ActionStatus.CONFIRMED) return ActionMutation.CONFLICT
        ensureEventCapacity()
        if (schedule(id, expectedVersion, generation, deliveryActionId, precision, method, requestCode, at,
                localDateTime, offsetSeconds, resolutionDecision) != 1) return ActionMutation.CONFLICT
        insertEvent(event(row, "REMINDER_SCHEDULE_PLANNED", at, owner.correlationId))
        return ActionMutation.APPLIED
    }

    @Query("""UPDATE orion_reminders SET registrationConfirmed = 1, lastScheduledAt = :at,
        updatedAt = :at, version = version + 1
        WHERE reminderId = :id AND version = :version AND status = 'PENDING'
        AND fireGeneration = :generation AND deliveryActionId = :actionId AND registrationConfirmed = 0""")
    protected abstract fun acknowledgeRegistration(id: String, version: Long, generation: Long, actionId: String, at: Long): Int

    /** Commit only after successful Android registration, outside the Android call itself. */
    @Transaction
    open fun confirmRegistration(id: String, expectedVersion: Long, generation: Long, deliveryActionId: String, at: Long): ActionMutation {
        validateBinding(id, expectedVersion, generation, deliveryActionId)
        val row = matching(id, expectedVersion, generation, deliveryActionId) ?: return conflictOrMissing(id)
        val owner = action(deliveryActionId) ?: return ActionMutation.CONFLICT
        if (owner.skill != DELIVERY_SKILL || owner.status != ActionStatus.CONFIRMED ||
            row.lastScheduledGeneration != generation || row.precision == ReminderPrecision.UNSCHEDULED ||
            row.scheduleMethod == ReminderScheduleMethod.UNSCHEDULED) return ActionMutation.CONFLICT
        if (row.registrationConfirmed) return ActionMutation.APPLIED
        ensureEventCapacity()
        if (acknowledgeRegistration(id, expectedVersion, generation, deliveryActionId, at) != 1) return ActionMutation.CONFLICT
        insertEvent(event(row, "REMINDER_SCHEDULED", at, owner.correlationId))
        return ActionMutation.APPLIED
    }

    @Query("UPDATE orion_reminders SET registrationConfirmed = 0 WHERE registrationConfirmed = 1")
    protected abstract fun resetRegistrationObservations(): Int

    /** Derived cache reset during gated recovery; no business version or authority changes. */
    @Transaction
    open fun invalidateRegistrationObservations(): Int = resetRegistrationObservations()

    @Query("""UPDATE orion_reminders SET status = 'FIRED', lastFiredAt = :at,
        updatedAt = :at, version = version + 1
        WHERE reminderId = :id AND version = :version AND status = 'PENDING'
        AND fireGeneration = :generation AND deliveryActionId = :actionId AND scheduledAt <= :at""")
    protected abstract fun finish(id: String, version: Long, generation: Long, actionId: String, at: Long): Int

    /**
     * Called inside the SAME outer Room transaction as action success, after real
     * platform proof. A callback alone cannot mark FIRED, and an event failure
     * rolls this transition back. An uncertain external attempt stays uncertain.
     */
    @Transaction
    open fun completeDeliveryWithEvent(id: String, expectedVersion: Long, generation: Long,
        deliveryActionId: String, at: Long, event: DomainEventOutboxEntity): ActionMutation {
        validateBinding(id, expectedVersion, generation, deliveryActionId)
        val row = matching(id, expectedVersion, generation, deliveryActionId) ?: return conflictOrMissing(id)
        if (row.scheduledAt > at) return ActionMutation.CONFLICT
        val owner = action(deliveryActionId) ?: return ActionMutation.CONFLICT
        if (owner.skill != DELIVERY_SKILL || owner.status != ActionStatus.SUCCEEDED) return ActionMutation.CONFLICT
        validateEvent(row, at, event)
        ensureEventCapacity()
        if (finish(id, expectedVersion, generation, deliveryActionId, at) != 1) return ActionMutation.CONFLICT
        insertEvent(event)
        return ActionMutation.APPLIED
    }

    private fun matching(id: String, version: Long, generation: Long, actionId: String): ReminderEntity? =
        find(id)?.takeIf { it.status == ReminderStatus.PENDING && it.version == version &&
            it.fireGeneration == generation && it.deliveryActionId == actionId }

    private fun conflictOrMissing(id: String) = if (find(id) == null) ActionMutation.NOT_FOUND else ActionMutation.CONFLICT
    private fun ensureEventCapacity() { check(eventCount() < 10_000) { "Durable event capacity reached." } }

    private fun validateBinding(id: String, version: Long, generation: Long, actionId: String) {
        requireTechnicalUuid(id); requireTechnicalUuid(actionId)
        require(version in 0 until Long.MAX_VALUE && generation > 0)
    }

    private fun event(row: ReminderEntity, type: String, at: Long, correlation: String): DomainEventOutboxEntity {
        requireTechnicalUuid(correlation)
        return DomainEventOutboxEntity(UUID.randomUUID().toString(), row.reminderId, row.version + 1,
            type, 1, row.sensitivity, at, correlation)
    }

    private fun validateEvent(row: ReminderEntity, at: Long, event: DomainEventOutboxEntity) {
        requireTechnicalUuid(event.eventId); requireTechnicalUuid(event.correlationId)
        require(event.aggregateId == row.reminderId && event.aggregateVersion == row.version + 1 &&
            event.eventType == "REMINDER_FIRED" && event.schemaVersion == 1 && event.payloadSensitivity == row.sensitivity &&
            event.occurredAt == at && event.status == DomainEventDeliveryStatus.PENDING && event.version == 0L &&
            event.attemptCount == 0 && event.nextAttemptAt == at && event.deliveredAt == null)
    }

    /** Derived artifact observation only; never changes business authority or a confirmation. */
    @Query("UPDATE orion_reminders SET registrationConfirmed = 0 WHERE reminderId = :id AND fireGeneration = :generation")
    abstract fun invalidateRegistrationObservation(id: String, generation: Long): Int

    companion object { const val DELIVERY_SKILL = "reminder.deliver" }
}
