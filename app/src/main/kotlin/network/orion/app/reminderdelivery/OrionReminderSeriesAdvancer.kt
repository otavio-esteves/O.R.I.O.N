package network.orion.app.reminderdelivery

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import network.orion.core.model.OrionReminderRecurrenceCodec
import network.orion.data.database.*
import network.orion.scheduler.reconciliation.*

/** The next independently preconfirmed occurrence is activated only after proven current delivery. */
internal class OrionReminderSeriesAdvancer(private val db: OrionDatabase) {
    fun referenceAllowed(reminderId: String, generation: Long, actionId: String, at: Long): Boolean {
        val series = db.reminderSeries().series(reminderId) ?: return true
        val mappings = db.reminderSeries().occurrences(reminderId)
        if (series.state != ReminderSeriesState.ACTIVE) return mappings.none { it.actionId == actionId }
        val current = mappings.getOrNull(series.currentIndex) ?: return false
        return mappings.size == series.occurrenceCount && current.seriesId == series.seriesId &&
            current.actionId == actionId && current.generation == generation && current.scheduledAt == at
    }

    fun advance(reminderId: String, generation: Long, actionId: String, at: Long) {
        check(db.inTransaction())
        val series = db.reminderSeries().series(reminderId) ?: return
        if (series.state != ReminderSeriesState.ACTIVE) return
        val occurrences = db.reminderSeries().occurrences(reminderId)
        check(occurrences.size == series.occurrenceCount && series.currentIndex in occurrences.indices)
        val current = occurrences[series.currentIndex]
        check(current.generation == generation && current.actionId == actionId)
        val rule = checkNotNull(OrionReminderRecurrenceCodec.decode(series.ruleCanonical))
        if (series.currentIndex + 1 < occurrences.size) {
            val previous = CivilOccurrence(LocalDateTime.parse(current.requestedLocalDateTime),
                LocalDateTime.parse(current.resolvedLocalDateTime), rule.zoneId, ZoneOffset.ofTotalSeconds(current.offsetSeconds),
                Instant.ofEpochMilli(current.scheduledAt), CivilTimeDecision.valueOf(current.resolutionDecision))
            val calculated = (OrionCivilRecurrence.nextOccurrence(previous, rule.zoneId,
                RecurrenceRule { local -> rule.nextAfter(local) }, previous.instant) as? CivilOccurrenceSearch.Found)?.occurrence
                ?: error("Finite recurrence cannot advance.")
            val next = occurrences[series.currentIndex + 1]
            val matches = next.requestedLocalDateTime == calculated.requestedLocalDateTime.toString() &&
                next.resolvedLocalDateTime == calculated.resolvedLocalDateTime.toString() &&
                next.scheduledAt == calculated.instant.toEpochMilli() && next.offsetSeconds == calculated.offset.totalSeconds &&
                next.resolutionDecision == calculated.decision.name
            val nextAction = db.actions().find(next.actionId)
            if (!matches || nextAction?.status != network.orion.core.actions.ActionStatus.CONFIRMED ||
                nextAction.confirmationExpiresAt?.let { it <= at } != false) {
                // Future consent changed/expired; preserve proven current success and revoke future work.
                check(db.reminderSeries().cancelSeries(reminderId, at) == ActionMutation.APPLIED)
                return
            }
        }
        val fired = checkNotNull(db.reminders().find(reminderId))
        check(fired.status == ReminderStatus.FIRED)
        check(db.reminderSeries().advanceAfterDelivery(reminderId, fired.version, generation, actionId, at) == ActionMutation.APPLIED)
    }
}
