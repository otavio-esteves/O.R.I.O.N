package network.orion.data.tasks

import network.orion.core.actions.*
import network.orion.core.commands.tasks.*
import network.orion.core.security.*
import network.orion.core.skills.metadata.tasks.OrionTaskStatusDescriptors
import network.orion.core.time.*
import network.orion.data.actions.*
import network.orion.data.database.*

enum class OwnedTaskStatusResult { APPLIED, DEDUP, NOT_OWNED, PAYLOAD_MISMATCH, CONFLICT, KEY_UNAVAILABLE, UNREADABLE }

fun interface OrionOwnedTaskStatusWriter {
    fun change(change: ValidatedTaskStatusChange, owner: TaskExecutionOwner): OwnedTaskStatusResult
}

/** Verifies actual RUNNING ownership inside the engine's admitted transaction; IDs alone cannot write. */
class OrionOwnedTaskStatusStore(private val database: OrionDatabase, encryption: OrionFieldEncryption,
    private val clock: OrionClock) : OrionOwnedTaskStatusWriter {
    private val actions = OrionActionStore(database, encryption, clock)
    private val tasks = OrionTaskStore(database, encryption, clock)

    fun matchesExisting(change: ValidatedTaskStatusChange): Boolean {
        val row = database.tasks().find(change.taskId.toString()) ?: return false
        return row.version == change.expectedVersion + 1 && row.status == desired(change)
    }

    override fun change(change: ValidatedTaskStatusChange, owner: TaskExecutionOwner): OwnedTaskStatusResult {
        if (!database.inTransaction()) return OwnedTaskStatusResult.NOT_OWNED
        val row = database.actions().find(owner.actionId.toString()) ?: return OwnedTaskStatusResult.NOT_OWNED
        if (row.status != ActionStatus.RUNNING || row.version != owner.version || row.executionId != owner.executionId.toString())
            return OwnedTaskStatusResult.NOT_OWNED
        val lease = try {
            ActionExecutionLease(owner.executionId, row.claimedBy ?: return OwnedTaskStatusResult.NOT_OWNED,
                BootSessionId(row.leaseBootSessionId ?: return OwnedTaskStatusResult.NOT_OWNED),
                row.leaseStartedElapsedRealtime ?: return OwnedTaskStatusResult.NOT_OWNED,
                row.leaseExpiresElapsedRealtime ?: return OwnedTaskStatusResult.NOT_OWNED)
        } catch (_: IllegalArgumentException) { return OwnedTaskStatusResult.NOT_OWNED }
        if (lease.statusAt(clock.monotonicNow()) != ActionLeaseStatus.ACTIVE) return OwnedTaskStatusResult.NOT_OWNED
        val stored = when (val read = actions.read(owner.actionId)) {
            is ActionReadResult.Ready -> read.action
            is ActionReadResult.Blocked -> return if (read.reason == ActionStoreOutcome.KEY_UNAVAILABLE ||
                read.reason == ActionStoreOutcome.KEY_INVALIDATED) OwnedTaskStatusResult.KEY_UNAVAILABLE else OwnedTaskStatusResult.UNREADABLE
        }
        val descriptor = OrionTaskStatusDescriptors.forName(change.skillName) ?: return OwnedTaskStatusResult.PAYLOAD_MISMATCH
        val expected = NormalizedActionRequest(owner.actionId, descriptor.name, change.canonicalNormalizedArguments,
            change.normalizedTarget, descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
            descriptor.recoveryStrategy, descriptor.idempotencyMode, stored.request.policyVersion)
        if (stored.request != expected || !stored.requiresConfirmation || row.idempotencyScope != change.skillName ||
            row.idempotencyKey != change.idempotencyKey.toString()) return OwnedTaskStatusResult.PAYLOAD_MISMATCH
        val confirmation = stored.confirmation ?: return OwnedTaskStatusResult.NOT_OWNED
        if (ActionConfirmationBinding(clock).validate(expected, confirmation) != ConfirmationBindingResult.VALID_BINDING)
            return OwnedTaskStatusResult.NOT_OWNED
        val target = database.tasks().find(change.taskId.toString()) ?: return OwnedTaskStatusResult.CONFLICT
        if (target.version == change.expectedVersion + 1 && target.status == desired(change)) return OwnedTaskStatusResult.DEDUP
        if (target.version != change.expectedVersion || target.status != TaskStatus.OPEN) return OwnedTaskStatusResult.CONFLICT
        return if (tasks.changeStatus(change.taskId, change.expectedVersion, desired(change), change.correlationId) == ActionMutation.APPLIED)
            OwnedTaskStatusResult.APPLIED else OwnedTaskStatusResult.CONFLICT
    }

    private fun desired(change: ValidatedTaskStatusChange) = when (change.operation) {
        TaskStatusOperation.COMPLETE -> TaskStatus.COMPLETED
        TaskStatusOperation.CANCEL -> TaskStatus.CANCELLED
    }
}
