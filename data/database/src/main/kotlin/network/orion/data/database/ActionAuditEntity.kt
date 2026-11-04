package network.orion.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import network.orion.core.actions.ActionStatus

/** Minimal technical audit: no arguments, targets, error text or user content. */
@Entity(tableName = "orion_action_audit", foreignKeys = [
    ForeignKey(entity = ActionRequestEntity::class, parentColumns = ["actionId"],
        childColumns = ["actionId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("actionId"), Index(value = ["actionId", "actionVersion"], unique = true)])
data class ActionAuditEntity(
    @PrimaryKey val auditId: String,
    val actionId: String,
    val actionVersion: Long,
    val fromStatus: ActionStatus?,
    val toStatus: ActionStatus,
    val occurredAt: Long,
    val correlationId: String,
) {
    override fun toString(): String = "ActionAuditEntity(<redacted>)"
}
