package network.orion.core.queries.reminders

import java.util.Collections
import java.util.UUID
import network.orion.core.queries.Query

/** Stable UUID cursor. A query never drains ingress or changes reminder state. */
data class GetReminders(val after: UUID? = null, val limit: Int = 50) : Query<ReminderPage> {
    init { require(limit in 1..100) }
}

enum class ReminderState { PENDING, FIRED, CANCELLED }
enum class ReminderAlertState { DISABLED, AWAITING_SCHEDULE, SCHEDULED, DELIVERED, UNCERTAIN, EXPIRED }
enum class ReminderContentState { READABLE, UNREADABLE, KEY_UNAVAILABLE }

class ReminderSummary(val reminderId: UUID, val title: String?, val state: ReminderState, val version: Long,
    val contentState: ReminderContentState, val scheduledAt: java.time.Instant, val zoneId: String, val precision: String,
    val alertState: ReminderAlertState = ReminderAlertState.DISABLED) {
    init { require(version >= 0); require((title != null) == (contentState == ReminderContentState.READABLE)) }
    override fun toString() = "ReminderSummary(<redacted>)"
}

class ReminderPage(items: List<ReminderSummary>, val next: UUID?) {
    val items: List<ReminderSummary> = Collections.unmodifiableList(ArrayList(items))
    init { require(items.size <= 100); require(next == null || items.lastOrNull()?.reminderId == next) }
    override fun toString() = "ReminderPage(<redacted>)"
}

fun interface ReminderQuery { fun get(request: GetReminders): ReminderPage }
