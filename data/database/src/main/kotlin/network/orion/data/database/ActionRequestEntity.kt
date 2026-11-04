package network.orion.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import network.orion.core.actions.ActionStatus
import network.orion.core.model.AuthorizationLevel
import network.orion.core.model.IdempotencyMode
import network.orion.core.model.RecoveryStrategy
import network.orion.core.model.Sensitivity
import network.orion.core.model.SideEffectClass

/** Storage snapshot, never an authorization credential. Enum names, never ordinals, are persisted. */
@Entity(tableName = "orion_action_requests", indices = [
    Index(value = ["idempotencyScope", "idempotencyKey"], unique = true),
    Index(value = ["status", "updatedAt"]),
])
data class ActionRequestEntity(
    @PrimaryKey val actionId: String,
    val skill: String,
    val argumentSchemaVersion: Int,
    val argumentsEnvelope: ByteArray,
    val targetEnvelope: ByteArray,
    val payloadSensitivity: Sensitivity,
    val authorizationLevel: AuthorizationLevel,
    val sideEffectClass: SideEffectClass,
    val recoveryStrategy: RecoveryStrategy,
    val idempotencyMode: IdempotencyMode,
    val policyVersion: String,
    val status: ActionStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val idempotencyScope: String,
    val idempotencyKey: String,
    val idempotencyKeyIssuer: String,
    val canonicalPayloadSchemaVersion: Int,
    val idempotencyRetainUntil: Long,
    val correlationId: String,
    val requiresConfirmation: Boolean,
    val normalizedPayloadHash: String,
    val confirmationFingerprint: String,
    val version: Long = 0,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val confirmedAt: Long? = null,
    val confirmationExpiresAt: Long? = null,
    val confirmedPayloadHash: String? = null,
    val policyVersionAtConfirmation: String? = null,
    val payloadLockedAt: Long? = null,
    val executionStartedAt: Long? = null,
    val executedAt: Long? = null,
    val executionToken: String? = null,
    val executionId: String? = null,
    val claimedBy: String? = null,
    val leaseBootSessionId: Int? = null,
    val leaseStartedElapsedRealtime: Long? = null,
    val leaseExpiresElapsedRealtime: Long? = null,
    val leaseWallStartedAt: Long? = null,
) {
    override fun toString(): String = "ActionRequestEntity(<redacted>)"
}
