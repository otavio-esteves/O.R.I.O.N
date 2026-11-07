package network.orion.app.reminderdelivery

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import network.orion.core.model.OrionReminderRecurrenceCodec
import network.orion.core.model.OrionReminderRecurrenceRule
import network.orion.core.orchestrator.reminders.*
import network.orion.core.skills.metadata.reminders.OrionDeliverReminderDescriptor
import network.orion.scheduler.reconciliation.*

/** Every future action is materialized before the single explicit finite-batch approval. */
internal object OrionReminderSeriesPlan {
    fun prepare(id: UUID, version: Long, previousGeneration: Long, firstDue: Instant,
        rule: OrionReminderRecurrenceRule, now: Instant, proposalId: UUID? = null): ReminderSeriesPreview {
        require(version in 0 until Long.MAX_VALUE && previousGeneration >= 0 && previousGeneration < Long.MAX_VALUE - rule.count)
        val resolved = ArrayList<CivilOccurrence>(rule.count)
        var occurrence = OrionCivilRecurrence.resolve(rule.startLocalDateTime, rule.zoneId)
        require(occurrence.instant == firstDue && occurrence.instant > now)
        resolved += occurrence
        repeat(rule.count - 1) {
            occurrence = (OrionCivilRecurrence.nextOccurrence(occurrence, rule.zoneId,
                RecurrenceRule { local -> rule.nextAfter(local) }, occurrence.instant) as? CivilOccurrenceSearch.Found)?.occurrence
                ?: throw IllegalArgumentException("Finite recurrence cannot advance.")
            require(occurrence.instant <= now.plus(OrionReminderDeliveryPolicy.MAX_FUTURE))
            resolved += occurrence
        }
        val identity = "$id|$version|${OrionReminderRecurrenceCodec.encode(rule)}|" + resolved.joinToString("|") {
            "${it.requestedLocalDateTime}/${it.resolvedLocalDateTime}/${it.instant}/${it.offset.totalSeconds}/${it.decision}"
        }
        val seriesId = proposalId ?: UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8))
        val children = resolved.mapIndexed { index, civil ->
            val actionId = UUID.nameUUIDFromBytes("orion_series_delivery:$seriesId:$index".toByteArray(Charsets.UTF_8))
            ReminderSeriesOccurrence(ReminderDeliveryPreview(id, version, previousGeneration + index + 1,
                civil.instant, actionId, 0, "", OrionDeliverReminderDescriptor.POLICY_VERSION),
                civil.requestedLocalDateTime, civil.resolvedLocalDateTime, civil.offset.totalSeconds, civil.decision.name)
        }
        val preview = ReminderSeriesPreview(seriesId, rule, children, "")
        return preview.copy(confirmationFingerprint = fingerprint(preview))
    }

    fun fingerprint(preview: ReminderSeriesPreview): String {
        val wire = OrionReminderSeriesWire(false, preview.copy(confirmationFingerprint = "")) .encode()
        return try { MessageDigest.getInstance("SHA-256").digest(wire).joinToString("") { "%02x".format(it.toInt() and 255) } }
        finally { wire.fill(0) }
    }
}
