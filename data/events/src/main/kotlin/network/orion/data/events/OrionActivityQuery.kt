package network.orion.data.events

import java.time.Instant
import network.orion.core.queries.activity.*
import network.orion.data.database.OrionDatabase

/** MAIN-owned selected projection. This does not consume, deliver or retry any event/action. */
class OrionActivityQuery(private val database: OrionDatabase) : ActivityQuery {
    override fun get(request: GetActivity): ActivityPage {
        val cursor = request.after
        // Project only technical metadata at SQLite; raw content/errors never enter this adapter.
        val condition = if (cursor == null) "" else "WHERE occurredAt < ? OR (occurredAt = ? AND rowKey > ?)"
        val sql = """SELECT rowKey, occurredAt, code, status, kind FROM (
            SELECT 'E:' || eventId AS rowKey, occurredAt AS occurredAt, eventType AS code,
                status AS status, 'DOMAIN_EVENT' AS kind FROM orion_domain_event_outbox
            UNION ALL
            SELECT 'A:' || actionId AS rowKey, createdAt AS occurredAt, skill AS code,
                status AS status, 'ACTION' AS kind FROM orion_action_requests
            ) $condition ORDER BY occurredAt DESC, rowKey ASC LIMIT ?"""
        val args: Array<Any> = if (cursor == null) arrayOf(request.limit + 1)
            else arrayOf(cursor.occurredAtEpochMillis, cursor.occurredAtEpochMillis, cursor.key, request.limit + 1)
        val items = ArrayList<ActivityEntry>()
        var last: ActivityCursor? = null
        var more = false
        database.openHelper.readableDatabase.query(sql, args).use { rows ->
            while (rows.moveToNext()) {
                if (items.size == request.limit) { more = true; break }
                val at = rows.getLong(1)
                val kind = ActivityKind.valueOf(rows.getString(4))
                val rawCode = rows.getString(2)
                val code = rawCode.takeIf { it in knownCodes } ?: if (kind == ActivityKind.ACTION) "CORE_ACTION" else "CORE_EVENT"
                val status = rows.getString(3).takeIf { it in knownStatuses } ?: "UNKNOWN"
                items += ActivityEntry(kind, code, status, Instant.ofEpochMilli(at))
                last = ActivityCursor(at, rows.getString(0))
            }
        }
        return ActivityPage(items, last.takeIf { more })
    }
    private companion object {
        val knownCodes = setOf("TASK_CREATED", "TASK_COMPLETED", "TASK_CANCELLED", "REMINDER_CREATED", "REMINDER_CANCELLED",
            "REMINDER_SNOOZED", "REMINDER_UPDATED", "TIMER_CREATED", "TIMER_CANCELLED", "MEMORY_CREATED", "MEMORY_UPDATED",
            "MEMORY_DELETED", "task.create", "task.complete", "task.cancel", "reminder.create", "reminder.cancel",
            "reminder.snooze", "timer.create", "timer.cancel")
        val knownStatuses = setOf("PENDING", "DELIVERED", "DEAD_LETTER", "NEEDS_CONFIRMATION", "CONFIRMED", "RUNNING",
            "SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN", "MANUAL_REVIEW")
    }
}
