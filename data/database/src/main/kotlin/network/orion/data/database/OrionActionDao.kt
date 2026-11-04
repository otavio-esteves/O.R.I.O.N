package network.orion.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import network.orion.core.actions.ActionStateMachine
import network.orion.core.actions.ActionStatus
import java.util.UUID

enum class ActionRegistration { INSERTED, DEDUP, IDEMPOTENCY_CONFLICT, ACTION_ID_CONFLICT }
enum class ActionMutation { APPLIED, CONFLICT, NOT_FOUND }

/**
 * Persistence boundary only. No policy, user gesture, lease or execution authority.
 * Blobs are opaque here: only the trusted encrypted adapter validates ciphertext
 * envelopes, decrypts and recomputes their binding. Callers must not treat this DAO
 * as a content-validation or authorization boundary.
 */
@Dao
abstract class OrionActionDao {
    @Query("SELECT * FROM orion_action_requests WHERE actionId = :actionId")
    abstract fun find(actionId: String): ActionRequestEntity?

    @Query("SELECT * FROM orion_action_requests WHERE idempotencyScope = :scope AND idempotencyKey = :key")
    abstract fun findByKey(scope: String, key: String): ActionRequestEntity?

    @Query("SELECT * FROM orion_action_audit WHERE actionId = :actionId ORDER BY actionVersion")
    abstract fun auditFor(actionId: String): List<ActionAuditEntity>

    @Insert
    protected abstract fun insertAction(action: ActionRequestEntity)

    @Insert
    protected abstract fun insertAudit(audit: ActionAuditEntity)

    @Query("""UPDATE orion_action_requests SET status = :toStatus, updatedAt = :at, version = version + 1
        WHERE actionId = :actionId AND version = :expectedVersion AND status = :fromStatus""")
    protected abstract fun updateDraftStatus(actionId: String, expectedVersion: Long,
        fromStatus: ActionStatus, toStatus: ActionStatus, at: Long): Int

    @Query("""UPDATE orion_action_requests SET status = 'CONFIRMED', updatedAt = :at, version = version + 1,
        confirmedAt = :at, confirmationExpiresAt = :expiresAt, confirmedPayloadHash = :payloadHash,
        policyVersionAtConfirmation = :policyVersion, payloadLockedAt = :at
        WHERE actionId = :actionId AND version = :expectedVersion AND status = :fromStatus""")
    protected abstract fun updateConfirmation(actionId: String, expectedVersion: Long,
        fromStatus: ActionStatus, at: Long, expiresAt: Long?, payloadHash: String, policyVersion: String): Int

    @Transaction
    open fun registerDraft(action: ActionRequestEntity, audit: ActionAuditEntity): ActionRegistration {
        require(action.status == ActionStatus.PENDING && action.version == 0L && action.attemptCount == 0)
        require(action.confirmedAt == null && action.confirmedPayloadHash == null &&
            action.confirmationExpiresAt == null && action.policyVersionAtConfirmation == null && action.payloadLockedAt == null)
        require(action.executionId == null && action.executionToken == null && action.claimedBy == null &&
            action.executionStartedAt == null && action.executedAt == null && action.leaseBootSessionId == null &&
            action.leaseStartedElapsedRealtime == null && action.leaseExpiresElapsedRealtime == null && action.leaseWallStartedAt == null)
        require(action.argumentsEnvelope.isNotEmpty() && action.targetEnvelope.isNotEmpty())
        require(action.actionId.isNotBlank() && action.skill.isNotBlank() && action.argumentSchemaVersion > 0 &&
            action.idempotencyScope.isNotBlank() && action.idempotencyKey.isNotBlank() && action.policyVersion.isNotBlank())
        require(action.lastError == null && action.updatedAt == action.createdAt)
        require(action.idempotencyKeyIssuer.isNotBlank() && action.canonicalPayloadSchemaVersion == 1 &&
            action.idempotencyRetainUntil > action.createdAt)
        require(action.normalizedPayloadHash.matches(Regex("[0-9a-f]{64}")) &&
            action.confirmationFingerprint.matches(Regex("[0-9a-f]{64}")))
        requireUuid(action.actionId)
        requireUuid(action.correlationId)
        requireAudit(action, audit, null, ActionStatus.PENDING, 0, action.createdAt)
        val previous = findByKey(action.idempotencyScope, action.idempotencyKey)
        if (previous != null) {
            return if (previous.normalizedPayloadHash == action.normalizedPayloadHash &&
                previous.argumentSchemaVersion == action.argumentSchemaVersion &&
                previous.requiresConfirmation == action.requiresConfirmation &&
                previous.payloadSensitivity == action.payloadSensitivity && previous.policyVersion == action.policyVersion &&
                previous.skill == action.skill && previous.authorizationLevel == action.authorizationLevel &&
                previous.sideEffectClass == action.sideEffectClass && previous.recoveryStrategy == action.recoveryStrategy &&
                previous.idempotencyMode == action.idempotencyMode) {
                ActionRegistration.DEDUP
            } else ActionRegistration.IDEMPOTENCY_CONFLICT
        }
        if (find(action.actionId) != null) return ActionRegistration.ACTION_ID_CONFLICT
        insertAction(action)
        insertAudit(audit)
        return ActionRegistration.INSERTED
    }

    /** Only waiting/cancellation before execution; RUNNING and result/recovery updates have no generic API. */
    @Transaction
    open fun changeDraftStatus(actionId: String, expectedVersion: Long, toStatus: ActionStatus,
        at: Long, audit: ActionAuditEntity): ActionMutation {
        require(expectedVersion >= 0 && expectedVersion < Long.MAX_VALUE)
        val current = find(actionId) ?: return ActionMutation.NOT_FOUND
        if (current.version != expectedVersion) return ActionMutation.CONFLICT
        require(toStatus == ActionStatus.NEEDS_CONFIRMATION || toStatus == ActionStatus.CANCELLED)
        ActionStateMachine.requireTransition(current.status, toStatus)
        requireAudit(current, audit, current.status, toStatus, expectedVersion + 1, at)
        if (updateDraftStatus(actionId, expectedVersion, current.status, toStatus, at) == 0) return ActionMutation.CONFLICT
        insertAudit(audit)
        return ActionMutation.APPLIED
    }

    /** Caller validates the exact persisted binding; this transaction only locks that stored hash/policy. */
    @Transaction
    open fun confirmDraft(actionId: String, expectedVersion: Long, payloadHash: String, fingerprint: String,
        policyVersion: String, at: Long, expiresAt: Long?, audit: ActionAuditEntity): ActionMutation {
        require(expectedVersion >= 0 && expectedVersion < Long.MAX_VALUE)
        require(expiresAt == null || expiresAt > at)
        val current = find(actionId) ?: return ActionMutation.NOT_FOUND
        if (current.version != expectedVersion) return ActionMutation.CONFLICT
        require(current.normalizedPayloadHash == payloadHash && current.confirmationFingerprint == fingerprint &&
            current.policyVersion == policyVersion)
        require(!current.requiresConfirmation || current.status == ActionStatus.NEEDS_CONFIRMATION)
        ActionStateMachine.requireTransition(current.status, ActionStatus.CONFIRMED)
        requireAudit(current, audit, current.status, ActionStatus.CONFIRMED, expectedVersion + 1, at)
        if (updateConfirmation(actionId, expectedVersion, current.status, at, expiresAt, payloadHash, policyVersion) == 0) {
            return ActionMutation.CONFLICT
        }
        insertAudit(audit)
        return ActionMutation.APPLIED
    }

    private fun requireAudit(action: ActionRequestEntity, audit: ActionAuditEntity, from: ActionStatus?,
        to: ActionStatus, version: Long, at: Long) {
        requireUuid(audit.auditId)
        require(audit.auditId.isNotBlank() && audit.actionId == action.actionId && audit.actionVersion == version &&
            audit.fromStatus == from && audit.toStatus == to && audit.occurredAt == at && audit.correlationId == action.correlationId)
    }

    private fun requireUuid(value: String) {
        require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
            "Expected canonical UUID."
        }
    }
}
