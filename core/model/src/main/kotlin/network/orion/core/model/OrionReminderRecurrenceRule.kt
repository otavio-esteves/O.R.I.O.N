package network.orion.core.model

import java.time.LocalDateTime
import java.time.ZoneId

enum class OrionReminderRecurrenceFrequency { DAILY, WEEKLY }

/**
 * A finite requested civil plan, not execution authority. [count] includes the
 * first occurrence at [startLocalDateTime]. Weekly progression keeps the same
 * weekday, every [interval] weeks. The application expands the plan with the
 * civil-time resolver, enforces its 365-day horizon, and binds consent to every
 * exact normalized occurrence before persisting any future execution.
 */
data class OrionReminderRecurrenceRule(
    val frequency: OrionReminderRecurrenceFrequency,
    val interval: Int,
    val count: Int,
    val startLocalDateTime: LocalDateTime,
    val zoneId: ZoneId,
) {
    init {
        require(interval in 1..52) { "Recurrence interval outside supported bounds." }
        require(count in 2..31) { "Recurrence count outside supported bounds." }
    }

    /** Advance a requested civil candidate; never pass a DST-adjusted local time. */
    fun nextAfter(previousLocalDateTime: LocalDateTime): LocalDateTime = when (frequency) {
        OrionReminderRecurrenceFrequency.DAILY -> previousLocalDateTime.plusDays(interval.toLong())
        OrionReminderRecurrenceFrequency.WEEKLY -> previousLocalDateTime.plusWeeks(interval.toLong())
    }
}

/**
 * Strict, bounded, versioned serialization of plan metadata only. Re-encoding
 * rejects alternate numeric/date representations and noncanonical identifiers;
 * no callback, arbitrary expression or user content is accepted.
 */
object OrionReminderRecurrenceCodec {
    const val MAX_ENCODED_LENGTH = 256

    fun encode(rule: OrionReminderRecurrenceRule): String = listOf(
        "1", rule.frequency.name, rule.interval.toString(), rule.count.toString(),
        rule.startLocalDateTime.toString(), rule.zoneId.id,
    ).joinToString("|").also { require(it.length <= MAX_ENCODED_LENGTH) }

    fun decode(encoded: String): OrionReminderRecurrenceRule? {
        if (encoded.length !in 1..MAX_ENCODED_LENGTH) return null
        val fields = encoded.split('|')
        if (fields.size != 6 || fields[0] != "1") return null
        return try {
            val rule = OrionReminderRecurrenceRule(
                OrionReminderRecurrenceFrequency.valueOf(fields[1]), fields[2].toInt(), fields[3].toInt(),
                LocalDateTime.parse(fields[4]), ZoneId.of(fields[5]),
            )
            rule.takeIf { encode(it) == encoded }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.time.DateTimeException) {
            null
        }
    }
}
