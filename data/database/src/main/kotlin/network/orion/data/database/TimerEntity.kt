package network.orion.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import network.orion.core.model.Sensitivity

enum class TimerStatus { ACTIVE, CANCELLED }

/** Durable one-shot duration; a monotonic reference is meaningful only in its original boot. */
@Entity(tableName = "orion_timers", indices = [Index(value = ["status", "updatedAt"])])
data class TimerEntity(
    @PrimaryKey val timerId: String,
    val contentEnvelope: ByteArray,
    val sensitivity: Sensitivity,
    val durationMillis: Long,
    val startedWallAt: Long,
    val deadlineWallAt: Long,
    val bootSessionId: Int,
    val startedElapsedRealtime: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val status: TimerStatus = TimerStatus.ACTIVE,
    val version: Long = 0,
) {
    override fun toString() = "TimerEntity(<redacted>)"
}
