package network.orion.data.database

import androidx.room.Insert
import androidx.room.Query
import java.util.UUID
import network.orion.core.actions.ActionStatus

/** Shared transaction-local invalidation for every existing reminder mutation path. */
abstract class OrionReminderSeriesMutationDao {
    @Query("SELECT * FROM orion_reminder_series WHERE reminderId = :id AND state = 'ACTIVE'")
    protected abstract fun activeSeriesForInvalidation(id: String): OrionReminderSeriesEntity?

    @Query("""SELECT owned_action.* FROM orion_action_requests AS owned_action
        INNER JOIN orion_reminder_occurrences AS occurrence ON occurrence.actionId = owned_action.actionId
        WHERE occurrence.reminderId = :id AND occurrence.seriesId = :seriesId
        AND owned_action.status IN ('PENDING','NEEDS_CONFIRMATION','CONFIRMED')
        ORDER BY occurrence.occurrenceIndex LIMIT 31""")
    protected abstract fun waitingSeriesActions(id: String, seriesId: String): List<ActionRequestEntity>

    @Query("""UPDATE orion_action_requests SET status = 'CANCELLED', updatedAt = :at, version = version + 1
        WHERE actionId = :id AND version = :version AND status = :status""")
    protected abstract fun cancelSeriesAction(id: String, version: Long, status: ActionStatus, at: Long): Int

    @Insert protected abstract fun insertSeriesCancellationAudit(audit: ActionAuditEntity)

    @Query("""UPDATE orion_reminder_series SET state = 'CANCELLED', updatedAt = :at, version = version + 1
        WHERE reminderId = :id AND version = :version AND state = 'ACTIVE'""")
    protected abstract fun markSeriesCancelled(id: String, version: Long, at: Long): Int

    /** Must execute inside the same writer transaction as the material reminder edit/cancel/snooze. */
    protected fun invalidateReminderSeries(id: String, at: Long): Boolean {
        val series = activeSeriesForInvalidation(id) ?: return false
        check(series.version in 0 until Long.MAX_VALUE)
        for (action in waitingSeriesActions(id, series.seriesId)) {
            check(action.version in 0 until Long.MAX_VALUE)
            check(cancelSeriesAction(action.actionId, action.version, action.status, at) == 1)
            insertSeriesCancellationAudit(ActionAuditEntity(UUID.randomUUID().toString(), action.actionId,
                action.version + 1, action.status, ActionStatus.CANCELLED, at, action.correlationId))
        }
        check(markSeriesCancelled(id, series.version, at) == 1)
        return true
    }
}
