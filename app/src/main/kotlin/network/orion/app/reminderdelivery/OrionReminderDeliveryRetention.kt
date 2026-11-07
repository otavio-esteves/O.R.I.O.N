package network.orion.app.reminderdelivery

import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import network.orion.core.actions.ActionStatus
import network.orion.core.time.WallClock
import network.orion.data.actions.ActionStoreOutcome
import network.orion.data.actions.OrionActionStore
import network.orion.data.database.*

/**
 * New explicit prepare gestures use fresh proposal UUIDs, retained unchanged through
 * durable replay and exact confirmation. Replacing an unconfirmed proposal cancels
 * its old drafts; returning to the same rule creates new requests, never resurrects
 * a terminal ActionRequest or changes an already confirmed payload.
 */
internal class OrionReminderDeliveryRetention(private val db: OrionDatabase,
    private val actions: OrionActionStore, private val clock: WallClock) {
    /** Called in the existing admitted preparation transaction. Every bound/confirmed/uncertain action is preserved. */
    fun prepareProposal(reminderId: UUID, keep: Set<UUID>): Boolean {
        check(db.inTransaction())
        require(keep.size in 1..31)
        val drafts = db.openHelper.readableDatabase.query("""SELECT actionId,version FROM orion_action_requests
            WHERE skill='reminder.deliver' AND correlationId=? AND status IN ('PENDING','NEEDS_CONFIRMATION')
            AND NOT EXISTS(SELECT 1 FROM orion_reminders WHERE deliveryActionId=orion_action_requests.actionId)
            ORDER BY actionId LIMIT 1001""", arrayOf(reminderId.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(UUID.fromString(cursor.getString(0)) to cursor.getLong(1)) }
        }
        if (drafts.size > MAX_DRAFTS) return false
        for ((id, version) in drafts) if (id !in keep) {
            if (actions.cancel(id, version).outcome != ActionStoreOutcome.APPLIED) return false
        }
        val current = db.openHelper.readableDatabase.query("""SELECT count(*) FROM orion_action_requests
            WHERE skill='reminder.deliver' AND status IN ('PENDING','NEEDS_CONFIRMATION')""").use { it.moveToFirst(); it.getInt(0) }
        val additional = keep.count { db.actions().find(it.toString()) == null }
        return current + additional <= MAX_DRAFTS
    }

    /** At most [limit] expired/local abandoned proposals; no effect invocation or generic retry is possible. */
    fun expireBatch(limit: Int = 100): Int {
        require(limit in 1..100)
        return db.runInTransaction(Callable {
            val now = clock.wallNow().toEpochMilli()
            val draftBefore = Math.subtractExact(now, Duration.ofDays(1).toMillis())
            val candidates = db.openHelper.readableDatabase.query("""SELECT actionId FROM orion_action_requests
                WHERE skill='reminder.deliver' AND
                ((status='CONFIRMED' AND confirmationExpiresAt IS NOT NULL AND confirmationExpiresAt<=?) OR
                 (status IN ('PENDING','NEEDS_CONFIRMATION') AND createdAt<=?))
                ORDER BY actionId LIMIT ?""", arrayOf<Any>(now, draftBefore, limit)).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            var expired = 0
            for (id in candidates) {
                val row = db.actions().find(id) ?: continue
                if (row.status !in setOf(ActionStatus.PENDING, ActionStatus.NEEDS_CONFIRMATION, ActionStatus.CONFIRMED)) continue
                val currentReminder = db.openHelper.readableDatabase.query("""SELECT reminderId FROM orion_reminders
                    WHERE deliveryActionId=? AND status='PENDING' LIMIT 2""", arrayOf(id)).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                val series = currentReminder?.let { db.reminderSeries().series(it) }
                if (row.status == ActionStatus.CONFIRMED && currentReminder != null && series?.state == ReminderSeriesState.ACTIVE) {
                    check(db.reminderSeries().cancelSeries(currentReminder, now) == ActionMutation.APPLIED)
                    expired++
                } else if (actions.cancel(UUID.fromString(id), row.version).outcome == ActionStoreOutcome.APPLIED) expired++
            }
            expired
        })
    }
    companion object { const val MAX_DRAFTS = 1000 }
}
