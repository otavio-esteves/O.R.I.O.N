package network.orion.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import network.orion.core.actions.ActionStatus
import network.orion.core.model.OrionReminderRecurrenceCodec

/** Finite exact mappings only. Every child has independent original human confirmation. */
@Dao
abstract class OrionReminderSeriesDao : OrionReminderSeriesMutationDao() {
    @Query("SELECT * FROM orion_reminder_series WHERE reminderId = :id")
    abstract fun series(id: String): OrionReminderSeriesEntity?

    @Query("SELECT * FROM orion_reminder_occurrences WHERE reminderId = :id ORDER BY occurrenceIndex")
    abstract fun occurrences(id: String): List<OrionReminderOccurrenceEntity>

    @Query("SELECT * FROM orion_reminders WHERE reminderId = :id")
    protected abstract fun reminder(id: String): ReminderEntity?

    @Query("SELECT * FROM orion_action_requests WHERE actionId = :id")
    protected abstract fun childAction(id: String): ActionRequestEntity?

    @Query("SELECT count(*) FROM orion_domain_event_outbox")
    protected abstract fun eventCount(): Int

    @Insert protected abstract fun insertSeries(series: OrionReminderSeriesEntity)
    @Insert protected abstract fun insertOccurrences(occurrences: List<OrionReminderOccurrenceEntity>)
    @Insert protected abstract fun insertSeriesEvent(event: DomainEventOutboxEntity)

    @Query("DELETE FROM orion_reminder_series WHERE reminderId = :id AND state != 'ACTIVE'")
    protected abstract fun removeHistoricalManifest(id: String): Int

    @Query("""UPDATE orion_reminders SET recurrenceRule = :rule, localDateTime = :local, zoneId = :zoneId,
        resolvedOffsetSeconds = :offset, resolutionDecision = :decision, updatedAt = :at, version = version + 1
        WHERE reminderId = :id AND version = :version AND status = 'PENDING'
        AND deliveryActionId = :actionId AND fireGeneration = :generation""")
    protected abstract fun attach(id: String, version: Long, actionId: String, generation: Long,
        rule: String, local: String, zoneId: String, offset: Int, decision: String, at: Long): Int

    /** Must share the outer admitted transaction that confirms all exact children and binds the first. */
    @Transaction
    open fun attachSeries(series: OrionReminderSeriesEntity, occurrences: List<OrionReminderOccurrenceEntity>): ActionMutation {
        requireTechnicalUuid(series.reminderId); requireTechnicalUuid(series.seriesId)
        require(series.version == 0L && series.currentIndex == 0 && series.state == ReminderSeriesState.ACTIVE &&
            series.createdAt == series.updatedAt && series.occurrenceCount in 2..31)
        val rule = requireNotNull(OrionReminderRecurrenceCodec.decode(series.ruleCanonical)) { "Invalid canonical recurrence rule." }
        require(rule.count == series.occurrenceCount && occurrences.size == series.occurrenceCount)
        require(occurrences.map { it.actionId }.distinct().size == occurrences.size)
        val row = reminder(series.reminderId) ?: return ActionMutation.NOT_FOUND
        if (row.status != ReminderStatus.PENDING || row.version == Long.MAX_VALUE ||
            this.series(series.reminderId)?.state == ReminderSeriesState.ACTIVE) return ActionMutation.CONFLICT
        var expected = resolveCivil(rule.startLocalDateTime, rule.zoneId, null)
        var previous: OrionReminderOccurrenceEntity? = null
        for ((index, occurrence) in occurrences.withIndex()) {
            requireTechnicalUuid(occurrence.actionId)
            require(occurrence.reminderId == series.reminderId && occurrence.seriesId == series.seriesId &&
                occurrence.occurrenceIndex == index && occurrence.generation > 0)
            require(occurrence.requestedLocalDateTime == expected.requested.toString() &&
                occurrence.resolvedLocalDateTime == expected.resolved.toString() &&
                occurrence.offsetSeconds == expected.offset.totalSeconds && occurrence.scheduledAt == expected.at &&
                occurrence.resolutionDecision == expected.decision)
            if (previous != null) {
                require(previous.generation < Long.MAX_VALUE && occurrence.generation == previous.generation + 1 &&
                    occurrence.scheduledAt > previous.scheduledAt)
            }
            if (!confirmedChild(occurrence)) return ActionMutation.CONFLICT
            if (index != occurrences.lastIndex) {
                var candidate = rule.nextAfter(expected.requested)
                var next = resolveCivil(candidate, rule.zoneId, expected.offset)
                var skipped = 0
                while (next.at <= expected.at && skipped < 366) {
                    candidate = rule.nextAfter(candidate)
                    next = resolveCivil(candidate, rule.zoneId, expected.offset)
                    skipped++
                }
                require(next.at > expected.at) { "Civil recurrence cannot advance within its candidate bound." }
                expected = next
            }
            previous = occurrence
        }
        val first = occurrences.first()
        if (row.deliveryActionId != first.actionId || row.fireGeneration != first.generation ||
            row.scheduledAt != first.scheduledAt) return ActionMutation.CONFLICT
        ensureCapacity()
        removeHistoricalManifest(row.reminderId)
        insertSeries(series)
        insertOccurrences(occurrences)
        check(attach(row.reminderId, row.version, first.actionId, first.generation, series.ruleCanonical,
            first.requestedLocalDateTime, rule.zoneId.id, first.offsetSeconds, first.resolutionDecision, series.createdAt) == 1)
        insertSeriesEvent(event(row, row.version + 1, "REMINDER_SERIES_ATTACHED", series.createdAt, first.actionId))
        return ActionMutation.APPLIED
    }

    @Query("""UPDATE orion_reminders SET status = 'PENDING', scheduledAt = :due, fireGeneration = :generation,
        deliveryActionId = :actionId, localDateTime = :local, resolvedOffsetSeconds = :offset,
        resolutionDecision = :decision, precision = 'UNSCHEDULED', scheduleMethod = 'UNSCHEDULED', registrationConfirmed = 0,
        updatedAt = :at, version = version + 1
        WHERE reminderId = :id AND version = :version AND status = 'FIRED' AND deliveryActionId = :priorActionId""")
    protected abstract fun activateNext(id: String, version: Long, priorActionId: String, due: Long, generation: Long,
        actionId: String, local: String, offset: Int, decision: String, at: Long): Int

    @Query("""UPDATE orion_reminder_series SET currentIndex = :nextIndex, state = :state, updatedAt = :at,
        version = version + 1 WHERE reminderId = :id AND version = :version AND state = 'ACTIVE'""")
    protected abstract fun advanceSeries(id: String, version: Long, nextIndex: Int, state: ReminderSeriesState, at: Long): Int

    @Query("""UPDATE orion_reminders SET deliveryActionId = NULL, fireGeneration = fireGeneration + 1,
        precision = 'UNSCHEDULED', scheduleMethod = 'UNSCHEDULED', registrationConfirmed = 0, updatedAt = :at, version = version + 1
        WHERE reminderId = :id AND version = :version AND status = 'PENDING'""")
    protected abstract fun revokePendingSeriesAnchor(id: String, version: Long, at: Long): Int

    /** Caller recomputes civil resolution, then invokes after action success and FIRED in the SAME outer transaction. */
    @Transaction
    open fun advanceAfterDelivery(id: String, expectedVersion: Long, generation: Long, deliveryActionId: String, at: Long): ActionMutation {
        requireTechnicalUuid(id); requireTechnicalUuid(deliveryActionId)
        require(expectedVersion in 0 until Long.MAX_VALUE && generation > 0)
        val current = series(id) ?: return ActionMutation.NOT_FOUND
        val row = reminder(id) ?: return ActionMutation.NOT_FOUND
        if (current.state != ReminderSeriesState.ACTIVE || current.version == Long.MAX_VALUE ||
            row.status != ReminderStatus.FIRED || row.version != expectedVersion || row.fireGeneration != generation ||
            row.deliveryActionId != deliveryActionId) return ActionMutation.CONFLICT
        val mappings = occurrences(id)
        if (current.occurrenceCount !in 2..31 || mappings.size != current.occurrenceCount ||
            current.currentIndex !in mappings.indices) return ActionMutation.CONFLICT
        val previous = mappings.getOrNull(current.currentIndex) ?: return ActionMutation.CONFLICT
        if (previous.seriesId != current.seriesId || previous.actionId != deliveryActionId ||
            previous.generation != generation || previous.scheduledAt != row.scheduledAt ||
            childAction(deliveryActionId)?.status != ActionStatus.SUCCEEDED) return ActionMutation.CONFLICT
        ensureCapacity()
        if (current.currentIndex == current.occurrenceCount - 1) {
            check(advanceSeries(id, current.version, current.currentIndex, ReminderSeriesState.COMPLETED, at) == 1)
            insertSeriesEvent(event(row, row.version, "REMINDER_SERIES_COMPLETED", at, deliveryActionId))
            return ActionMutation.APPLIED
        }
        val next = mappings.getOrNull(current.currentIndex + 1) ?: return ActionMutation.CONFLICT
        if (next.seriesId != current.seriesId || previous.generation == Long.MAX_VALUE ||
            next.generation != previous.generation + 1 || next.scheduledAt <= previous.scheduledAt ||
            !confirmedChild(next) || childAction(next.actionId)?.confirmationExpiresAt?.let { it <= at } != false)
            return ActionMutation.CONFLICT
        check(activateNext(id, expectedVersion, deliveryActionId, next.scheduledAt, next.generation, next.actionId,
            next.requestedLocalDateTime, next.offsetSeconds, next.resolutionDecision, at) == 1)
        check(advanceSeries(id, current.version, next.occurrenceIndex, ReminderSeriesState.ACTIVE, at) == 1)
        insertSeriesEvent(event(row, row.version + 1, "REMINDER_OCCURRENCE_ADVANCED", at, next.actionId))
        return ActionMutation.APPLIED
    }

    /** Pauses the entire finite manifest without touching ambiguous RUNNING/UNKNOWN children. */
    @Transaction
    open fun cancelSeries(id: String, at: Long): ActionMutation {
        requireTechnicalUuid(id)
        val row = reminder(id) ?: return ActionMutation.NOT_FOUND
        val active = series(id) ?: return ActionMutation.NOT_FOUND
        if (active.state != ReminderSeriesState.ACTIVE) return ActionMutation.CONFLICT
        if (row.status == ReminderStatus.PENDING && (row.version == Long.MAX_VALUE || row.fireGeneration == Long.MAX_VALUE))
            return ActionMutation.CONFLICT
        ensureCapacity()
        check(invalidateReminderSeries(id, at))
        val version = if (row.status == ReminderStatus.PENDING) {
            check(revokePendingSeriesAnchor(id, row.version, at) == 1)
            row.version + 1
        } else row.version
        insertSeriesEvent(event(row, version, "REMINDER_SERIES_CANCELLED", at, row.deliveryActionId))
        return ActionMutation.APPLIED
    }

    private fun confirmedChild(occurrence: OrionReminderOccurrenceEntity): Boolean {
        val action = childAction(occurrence.actionId) ?: return false
        return action.skill == OrionReminderScheduleDao.DELIVERY_SKILL && action.status == ActionStatus.CONFIRMED &&
            action.requiresConfirmation && action.confirmedAt?.let { it < occurrence.scheduledAt } == true &&
            action.confirmationExpiresAt?.let { it > occurrence.scheduledAt } == true &&
            action.normalizedPayloadHash == action.confirmedPayloadHash && action.payloadLockedAt == action.confirmedAt &&
            action.policyVersion == action.policyVersionAtConfirmation && action.idempotencyScope == "reminder.deliver" &&
            action.idempotencyKey == occurrence.actionId
    }

    private fun ensureCapacity() { check(eventCount() < 10_000) { "Durable event capacity reached." } }

    private data class CivilCheck(val requested: LocalDateTime, val resolved: LocalDateTime,
        val offset: ZoneOffset, val at: Long, val decision: String)

    /** Verification of the finite manifest only; platform delivery remains outside this module. */
    private fun resolveCivil(local: LocalDateTime, zone: ZoneId, previousOffset: ZoneOffset?): CivilCheck {
        val valid = zone.rules.getValidOffsets(local)
        val resolved: LocalDateTime
        val offset: ZoneOffset
        val decision: String
        when (valid.size) {
            0 -> {
                val transition = checkNotNull(zone.rules.getTransition(local))
                require(transition.isGap)
                resolved = transition.dateTimeAfter; offset = transition.offsetAfter
                decision = "GAP_FIRST_VALID_TIME"
            }
            1 -> { resolved = local; offset = valid.single(); decision = "NORMAL" }
            else -> {
                resolved = local
                if (previousOffset != null && previousOffset in valid) {
                    offset = previousOffset; decision = "OVERLAP_PREVIOUS_OFFSET"
                } else { offset = valid.first(); decision = "OVERLAP_EARLIER_OFFSET" }
            }
        }
        return CivilCheck(local, resolved, offset, resolved.toInstant(offset).toEpochMilli(), decision)
    }
    private fun event(row: ReminderEntity, version: Long, type: String, at: Long, actionId: String?): DomainEventOutboxEntity =
        DomainEventOutboxEntity(UUID.randomUUID().toString(), row.reminderId, version, type, 1, row.sensitivity,
            at, actionId?.let { childAction(it)?.correlationId } ?: UUID.randomUUID().toString())
}
