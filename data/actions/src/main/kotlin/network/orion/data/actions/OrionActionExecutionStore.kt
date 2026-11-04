package network.orion.data.actions

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.time.*
import network.orion.data.database.*

enum class ActionClaimFailure {
    NOT_FOUND, CONFLICT, INVALID_STATE, UNREADABLE, STARTUP_GATED, DESCRIPTOR_MISMATCH,
    POLICY_CHANGED, POLICY_DENIED, CAPABILITY_UNAVAILABLE, PERMISSION_DENIED,
    USER_PRESENCE_REQUIRED, CONFIRMATION_REQUIRED, CONFIRMATION_STALE,
    CONFIRMATION_MISMATCH, CONFIRMATION_EXPIRED, BOOT_ID_UNAVAILABLE,
}

sealed interface ActionClaimResult {
    data class Claimed(val lease: ActionExecutionLease, val version: Long) : ActionClaimResult {
        override fun toString(): String = "ActionClaimResult.Claimed(<redacted>)"
    }
    data class Blocked(val reason: ActionClaimFailure) : ActionClaimResult
}

/** Continue with nextAfterActionId until null; active leases cannot starve later abandoned actions. */
data class ActionRecoveryBatch(val recovered: Int, val scanned: Int, val nextAfterActionId: UUID?)

/**
 * Blocking main-owned persistence, called off the UI thread. Claiming records
 * ownership only: this class calls no Skill and is not wired to runtime ingress.
 * Production callers must provide trusted, fresh main-process authority ports.
 */
class OrionActionExecutionStore(
    private val database: OrionDatabase,
    private val actions: OrionActionStore,
    private val clock: OrionClock,
    private val authority: ActionExecutionAuthority,
) {
    fun claim(actionId: UUID, expectedVersion: Long, claimedBy: String, leaseDuration: Duration): ActionClaimResult {
        require(expectedVersion >= 0 && expectedVersion < Long.MAX_VALUE)
        val durationMillis = leaseDuration.toMillis()
        require(durationMillis in 1..MAX_LEASE_MILLIS)
        val stored = when (val read = actions.read(actionId)) {
            is ActionReadResult.Ready -> read.action
            is ActionReadResult.Blocked -> return ActionClaimResult.Blocked(
                if (read.reason == ActionStoreOutcome.NOT_FOUND) ActionClaimFailure.NOT_FOUND else ActionClaimFailure.UNREADABLE)
        }
        if (stored.version != expectedVersion) return ActionClaimResult.Blocked(ActionClaimFailure.CONFLICT)
        if (stored.status != ActionStatus.CONFIRMED) return ActionClaimResult.Blocked(ActionClaimFailure.INVALID_STATE)
        val confirmation = stored.confirmation ?: return ActionClaimResult.Blocked(ActionClaimFailure.CONFIRMATION_REQUIRED)
        ActionExecutionValidation.denyReason(stored.request, stored.requiresConfirmation, authority)?.let {
            return ActionClaimResult.Blocked(ActionClaimFailure.valueOf(it.name))
        }
        val validation = ActionConfirmationBinding(clock).validate(stored.request, confirmation)
        if (validation != ConfirmationBindingResult.VALID_BINDING) return ActionClaimResult.Blocked(when (validation) {
            ConfirmationBindingResult.CONFIRMATION_STALE -> ActionClaimFailure.CONFIRMATION_STALE
            ConfirmationBindingResult.CONFIRMATION_MISMATCH -> ActionClaimFailure.CONFIRMATION_MISMATCH
            ConfirmationBindingResult.CONFIRMATION_EXPIRED -> ActionClaimFailure.CONFIRMATION_EXPIRED
            ConfirmationBindingResult.POLICY_CHANGED_RECONFIRM_REQUIRED -> ActionClaimFailure.POLICY_CHANGED
            ConfirmationBindingResult.VALID_BINDING -> error("Unexpected confirmation result.")
        })
        val entity = database.actions().find(actionId.toString()) ?: return ActionClaimResult.Blocked(ActionClaimFailure.NOT_FOUND)
        ActionExecutionValidation.denyReason(stored.request, stored.requiresConfirmation, authority)?.let {
            return ActionClaimResult.Blocked(ActionClaimFailure.valueOf(it.name))
        }
        val now = clock.monotonicNow()
        val boot = now.bootSessionId ?: return ActionClaimResult.Blocked(ActionClaimFailure.BOOT_ID_UNAVAILABLE)
        val lease = ActionExecutionLease(UUID.randomUUID(), claimedBy, boot, now.elapsedRealtimeMillis,
            Math.addExact(now.elapsedRealtimeMillis, durationMillis))
        val wall = clock.wallNow()
        if (wall.isBefore(confirmation.confirmedAt)) return ActionClaimResult.Blocked(ActionClaimFailure.CONFIRMATION_STALE)
        if (confirmation.confirmationExpiresAt?.let { !wall.isBefore(it) } == true) {
            return ActionClaimResult.Blocked(ActionClaimFailure.CONFIRMATION_EXPIRED)
        }
        val at = wall.toEpochMilli()
        val result = database.actions().claimConfirmed(actionId.toString(), expectedVersion,
            confirmation.normalizedPayloadHash, confirmation.confirmationFingerprint, stored.request.policyVersion,
            lease.executionId.toString(), lease.claimedBy, lease.bootSessionId.bootCount,
            lease.startedElapsedRealtime, lease.expiresElapsedRealtime, at,
            audit(entity, ActionStatus.RUNNING, expectedVersion + 1, at))
        return if (result == ActionMutation.APPLIED) ActionClaimResult.Claimed(lease, expectedVersion + 1)
            else ActionClaimResult.Blocked(if (result == ActionMutation.NOT_FOUND) ActionClaimFailure.NOT_FOUND else ActionClaimFailure.CONFLICT)
    }

    /** Only an active matching executor may publish a terminal callback. Late results remain evidence for review. */
    fun finish(actionId: UUID, expectedVersion: Long, executionId: UUID, result: ActionStatus): ActionMutation {
        require(expectedVersion >= 0 && expectedVersion < Long.MAX_VALUE)
        require(result == ActionStatus.SUCCEEDED || result == ActionStatus.FAILED || result == ActionStatus.UNKNOWN ||
            result == ActionStatus.MANUAL_REVIEW)
        val entity = database.actions().find(actionId.toString()) ?: return ActionMutation.NOT_FOUND
        val now = clock.monotonicNow()
        val boot = now.bootSessionId ?: return ActionMutation.CONFLICT
        val at = clock.wallNow().toEpochMilli()
        return database.actions().finishExecution(actionId.toString(), expectedVersion, executionId.toString(), boot.bootCount,
            now.elapsedRealtimeMillis, result, at, audit(entity, result, expectedVersion + 1, at))
    }

    /** Bounded batch, no replay. The caller must run recovery before opening mutable ingress. */
    fun recoverAbandoned(limit: Int = 100, afterActionId: UUID? = null): ActionRecoveryBatch {
        require(limit in 1..1000)
        var recovered = 0
        val batch = database.actions().runningActions(limit, afterActionId?.toString() ?: "")
        for (entity in batch) {
            val now = clock.monotonicNow()
            val resolution = AbandonedActionRecovery.resolve(lease(entity), now, entity.recoveryStrategy, entity.sideEffectClass)
            if (resolution == AbandonedActionResolution.KEEP_RUNNING) continue
            val target = if (resolution == AbandonedActionResolution.UNKNOWN) ActionStatus.UNKNOWN else ActionStatus.MANUAL_REVIEW
            val at = clock.wallNow().toEpochMilli()
            if (database.actions().recoverAbandoned(entity.actionId, entity.version, now.bootSessionId?.bootCount,
                    now.elapsedRealtimeMillis, target, at, audit(entity, target, entity.version + 1, at)) == ActionMutation.APPLIED) {
                recovered++
            }
        }
        return ActionRecoveryBatch(recovered, batch.size, if (batch.size == limit) UUID.fromString(batch.last().actionId) else null)
    }

    private fun lease(entity: ActionRequestEntity): ActionExecutionLease? {
        return try { ActionExecutionLease(UUID.fromString(entity.executionId ?: return null), entity.claimedBy ?: return null,
            BootSessionId(entity.leaseBootSessionId ?: return null), entity.leaseStartedElapsedRealtime ?: return null,
            entity.leaseExpiresElapsedRealtime ?: return null)
        } catch (_: IllegalArgumentException) { null }
    }

    private fun audit(entity: ActionRequestEntity, to: ActionStatus, version: Long, at: Long) =
        ActionAuditEntity(UUID.randomUUID().toString(), entity.actionId, version, entity.status, to, at, entity.correlationId)

    companion object { private const val MAX_LEASE_MILLIS = 300_000L }
}
