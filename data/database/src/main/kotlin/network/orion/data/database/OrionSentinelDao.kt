package network.orion.data.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Authority for the one supported proactive Skill. No permission is implied by installation. */
@Entity(tableName = "orion_sentinel_consent")
data class OrionSentinelConsent(@PrimaryKey val id: Int = 1, val enabled: Boolean, val version: Long)

/** Only a reference to a task, with no user content or device observations. */
@Entity(tableName = "orion_sentinel_suggestions", indices = [Index("reservedAt"), Index(value = ["taskId"], unique = true)])
data class OrionSentinelSuggestion(
    @PrimaryKey val suggestionId: String,
    val taskId: String,
    val reservedAt: Long,
)

@Entity(
    tableName = "orion_sentinel_feedback",
    primaryKeys = ["suggestionId", "kind"],
    foreignKeys = [ForeignKey(
        entity = OrionSentinelSuggestion::class,
        parentColumns = ["suggestionId"],
        childColumns = ["suggestionId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class OrionSentinelFeedback(
    val suggestionId: String,
    val kind: String,
    val occurredAt: Long,
    val timeToActionMillis: Long?,
)

data class OrionSentinelTask(val taskId: String, val createdAt: Long)

@Dao
interface OrionSentinelDao {
    @Query("SELECT * FROM orion_sentinel_consent WHERE id = 1")
    fun consent(): OrionSentinelConsent?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveConsent(consent: OrionSentinelConsent)

    @Query("""SELECT taskId, createdAt FROM orion_tasks t WHERE status = 'OPEN'
        AND sensitivity IN ('PUBLIC', 'PERSONAL') AND createdAt BETWEEN :oldest AND :newest
        AND NOT EXISTS (SELECT 1 FROM orion_sentinel_suggestions s WHERE s.taskId = t.taskId)
        ORDER BY createdAt, taskId LIMIT 1""")
    fun nextOpenTask(oldest: Long, newest: Long): OrionSentinelTask?

    @Query("""SELECT COUNT(*) FROM (SELECT 1 FROM orion_reminders
        WHERE status = 'PENDING' AND sensitivity IN ('PUBLIC', 'PERSONAL')
        AND scheduledAt BETWEEN :now AND :until LIMIT 100)""")
    fun remindersDueSoon(now: Long, until: Long): Int

    @Query("SELECT * FROM orion_sentinel_suggestions WHERE suggestionId = :id")
    fun suggestion(id: String): OrionSentinelSuggestion?

    @Query("SELECT COUNT(*) FROM orion_sentinel_suggestions")
    fun reservationCount(): Int

    @Insert
    fun reserve(suggestion: OrionSentinelSuggestion)

    @Query("SELECT * FROM orion_sentinel_suggestions WHERE reservedAt >= :since ORDER BY reservedAt DESC LIMIT 10")
    fun recent(since: Long): List<OrionSentinelSuggestion>

    @Query("SELECT * FROM orion_sentinel_feedback WHERE suggestionId = :id")
    fun feedback(id: String): List<OrionSentinelFeedback>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun record(feedback: OrionSentinelFeedback): Long

    @Query("""SELECT s.* FROM orion_sentinel_suggestions s
        JOIN orion_sentinel_feedback f ON s.suggestionId = f.suggestionId AND f.kind = 'SHOWN'
        LEFT JOIN orion_tasks t ON s.taskId = t.taskId
        WHERE (t.status = 'COMPLETED' AND NOT EXISTS (SELECT 1 FROM orion_sentinel_feedback n
            WHERE n.suggestionId = s.suggestionId AND n.kind = 'COMPLETED'))
        OR (f.occurredAt <= :ignoreBefore AND NOT EXISTS (SELECT 1 FROM orion_sentinel_feedback n
            WHERE n.suggestionId = s.suggestionId AND n.kind != 'SHOWN'))
        ORDER BY s.reservedAt LIMIT 25""")
    fun needsFeedback(ignoreBefore: Long): List<OrionSentinelSuggestion>

    @Query("""DELETE FROM orion_sentinel_suggestions WHERE suggestionId IN (
        SELECT s.suggestionId FROM orion_sentinel_suggestions s WHERE reservedAt < :before
        AND NOT EXISTS (SELECT 1 FROM orion_tasks t WHERE t.taskId = s.taskId AND t.status = 'OPEN')
        ORDER BY reservedAt LIMIT 25)""")
    fun prune(before: Long): Int

    @Query("""DELETE FROM orion_sentinel_feedback WHERE suggestionId IN (
        SELECT suggestionId FROM orion_sentinel_suggestions WHERE reservedAt < :before AND EXISTS (
            SELECT 1 FROM orion_sentinel_feedback f WHERE f.suggestionId = orion_sentinel_suggestions.suggestionId)
        ORDER BY reservedAt LIMIT 25)""")
    fun pruneFeedback(before: Long): Int
}
