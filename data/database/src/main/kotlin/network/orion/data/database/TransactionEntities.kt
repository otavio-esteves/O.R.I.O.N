package network.orion.data.database

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import network.orion.core.model.Sensitivity

enum class TaskStatus { OPEN, COMPLETED, CANCELLED }
enum class ReminderStatus { PENDING, FIRED, CANCELLED }
enum class ReminderPrecision { UNSCHEDULED, EXACT, APPROXIMATE }
enum class ReminderScheduleMethod { UNSCHEDULED, EXACT_ALARM, INEXACT_ALARM }
enum class MemoryOrigin { USER_DECLARED, SYSTEM_OBSERVED, SYSTEM_DERIVED, LLM_INFERRED, IMPORTED, EXTERNAL_SOURCE }
enum class MemoryStatus { ACTIVE, SUPERSEDED, DELETED }

/** F2 persistence records. Content is encrypted, never indexed; business Skills are deferred to F3. */
@Entity(tableName = "orion_tasks", indices = [Index(value = ["status", "updatedAt"])])
data class TaskEntity(
    @PrimaryKey val taskId: String,
    val contentEnvelope: ByteArray,
    val sensitivity: Sensitivity,
    val createdAt: Long,
    val updatedAt: Long,
    val status: TaskStatus = TaskStatus.OPEN,
    val version: Long = 0,
) {
    override fun toString(): String = "TaskEntity(<redacted>)"
}

@Entity(tableName = "orion_reminders", indices = [Index(value = ["status", "scheduledAt"])])
data class ReminderEntity(
    @PrimaryKey val reminderId: String,
    val contentEnvelope: ByteArray,
    val sensitivity: Sensitivity,
    val scheduledAt: Long,
    val zoneId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: ReminderStatus = ReminderStatus.PENDING,
    val precision: ReminderPrecision = ReminderPrecision.UNSCHEDULED,
    val version: Long = 0,
    val localDateTime: String? = null,
    val recurrenceRule: String? = null,
    val resolutionDecision: String? = null,
    val resolvedOffsetSeconds: Int? = null,
    @ColumnInfo(defaultValue = "'UNSCHEDULED'") val scheduleMethod: ReminderScheduleMethod = ReminderScheduleMethod.UNSCHEDULED,
    @ColumnInfo(defaultValue = "0") val requestCode: Int = 0,
    @ColumnInfo(defaultValue = "0") val fireGeneration: Long = 0,
    val lastScheduledAt: Long? = null,
    /** Last registered OS token generation, retained across invalidation for exact cleanup. */
    val lastScheduledGeneration: Long? = null,
    /** Derived OS-artifact observation only; never grants delivery authority. */
    @ColumnInfo(defaultValue = "0") val registrationConfirmed: Boolean = false,
    val lastFiredAt: Long? = null,
    /** Exact future ActionRequest consent anchor; null never authorizes external delivery. */
    val deliveryActionId: String? = null,
) {
    override fun toString(): String = "ReminderEntity(<redacted>)"
}

@Entity(tableName = "orion_memories", indices = [Index(value = ["status", "updatedAt"])])
data class MemoryEntity(
    @PrimaryKey val memoryId: String,
    val contentEnvelope: ByteArray,
    val sensitivity: Sensitivity,
    val origin: MemoryOrigin,
    val confidence: Double,
    val userConfirmed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val status: MemoryStatus = MemoryStatus.ACTIVE,
    val supersedesId: String? = null,
    val version: Long = 0,
) {
    override fun toString(): String = "MemoryEntity(<redacted>)"
}

@Entity(tableName = "orion_memory_claims", foreignKeys = [ForeignKey(
    entity = MemoryEntity::class, parentColumns = ["memoryId"], childColumns = ["memoryId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index(value = ["memoryId"])])
data class MemoryClaimEntity(
    @PrimaryKey val claimId: String,
    val memoryId: String,
    val statementEnvelope: ByteArray,
    val sensitivity: Sensitivity,
    val origin: MemoryOrigin,
    val confidence: Double,
    val userConfirmed: Boolean,
    val status: MemoryStatus = MemoryStatus.ACTIVE,
    val validFrom: Long,
    val validUntil: Long? = null,
    val version: Long = 0,
) {
    override fun toString(): String = "MemoryClaimEntity(<redacted>)"
}

/** Technical audit only; user content, paths, error strings and arguments have no column. */
@Entity(tableName = "orion_audit", indices = [Index(value = ["occurredAt"])])
data class AuditEntity(
    @PrimaryKey val auditId: String,
    val aggregateId: String,
    val aggregateVersion: Long,
    val eventCode: String,
    val occurredAt: Long,
    val correlationId: String,
    val preserveUntilReview: Boolean = false,
)

@Entity(tableName = "orion_technical_events", indices = [Index(value = ["occurredAt"])])
data class TechnicalEventEntity(
    @PrimaryKey val technicalEventId: String,
    val component: String,
    val eventCode: String,
    val occurredAt: Long,
    val correlationId: String,
)
