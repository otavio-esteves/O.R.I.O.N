package network.orion.data.timers

import network.orion.core.actions.*
import network.orion.core.commands.timers.*
import network.orion.core.security.*
import network.orion.core.skills.metadata.timers.OrionTimerStatusDescriptors
import network.orion.core.time.*
import network.orion.data.actions.*
import network.orion.data.database.*

enum class OwnedTimerStatusResult { APPLIED, DEDUP, NOT_OWNED, PAYLOAD_MISMATCH, CONFLICT, KEY_UNAVAILABLE, UNREADABLE }

fun interface OrionOwnedTimerStatusWriter {
    fun change(change: ValidatedTimerStatusChange, owner: TimerExecutionOwner): OwnedTimerStatusResult
}

/** Verifies actual RUNNING ownership inside the engine's admitted transaction; IDs alone cannot write. */
class OrionOwnedTimerStatusStore(private val database: OrionDatabase, encryption: OrionFieldEncryption,
    private val clock: OrionClock) : OrionOwnedTimerStatusWriter {
    private val actions = OrionActionStore(database, encryption, clock)
    private val timers = OrionTimerStore(database, encryption, clock)

    fun matchesExisting(change: ValidatedTimerStatusChange): Boolean {
        val row = database.timers().find(change.timerId.toString()) ?: return false
        return row.version == change.expectedVersion + 1 && row.status == desired(change)
    }

    override fun change(change: ValidatedTimerStatusChange, owner: TimerExecutionOwner): OwnedTimerStatusResult {
        if (!database.inTransaction()) return OwnedTimerStatusResult.NOT_OWNED
        val row = database.actions().find(owner.actionId.toString()) ?: return OwnedTimerStatusResult.NOT_OWNED
        if (row.status != ActionStatus.RUNNING || row.version != owner.version || row.executionId != owner.executionId.toString())
            return OwnedTimerStatusResult.NOT_OWNED
        val lease = try {
            ActionExecutionLease(owner.executionId, row.claimedBy ?: return OwnedTimerStatusResult.NOT_OWNED,
                BootSessionId(row.leaseBootSessionId ?: return OwnedTimerStatusResult.NOT_OWNED),
                row.leaseStartedElapsedRealtime ?: return OwnedTimerStatusResult.NOT_OWNED,
                row.leaseExpiresElapsedRealtime ?: return OwnedTimerStatusResult.NOT_OWNED)
        } catch (_: IllegalArgumentException) { return OwnedTimerStatusResult.NOT_OWNED }
        if (lease.statusAt(clock.monotonicNow()) != ActionLeaseStatus.ACTIVE) return OwnedTimerStatusResult.NOT_OWNED
        val stored = when (val read = actions.read(owner.actionId)) {
            is ActionReadResult.Ready -> read.action
            is ActionReadResult.Blocked -> return if (read.reason == ActionStoreOutcome.KEY_UNAVAILABLE ||
                read.reason == ActionStoreOutcome.KEY_INVALIDATED) OwnedTimerStatusResult.KEY_UNAVAILABLE else OwnedTimerStatusResult.UNREADABLE
        }
        val descriptor = OrionTimerStatusDescriptors.forName(change.skillName) ?: return OwnedTimerStatusResult.PAYLOAD_MISMATCH
        val expected = NormalizedActionRequest(owner.actionId, descriptor.name, change.canonicalNormalizedArguments,
            change.normalizedTarget, descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
            descriptor.recoveryStrategy, descriptor.idempotencyMode, stored.request.policyVersion)
        if (stored.request != expected || !stored.requiresConfirmation || row.idempotencyScope != change.skillName ||
            row.idempotencyKey != change.idempotencyKey.toString()) return OwnedTimerStatusResult.PAYLOAD_MISMATCH
        val confirmation = stored.confirmation ?: return OwnedTimerStatusResult.NOT_OWNED
        if (ActionConfirmationBinding(clock).validate(expected, confirmation) != ConfirmationBindingResult.VALID_BINDING)
            return OwnedTimerStatusResult.NOT_OWNED
        val target = database.timers().find(change.timerId.toString()) ?: return OwnedTimerStatusResult.CONFLICT
        if (target.version == change.expectedVersion + 1 && target.status == desired(change)) return OwnedTimerStatusResult.DEDUP
        if (target.version != change.expectedVersion || target.status != TimerStatus.ACTIVE) return OwnedTimerStatusResult.CONFLICT
        return if (timers.changeStatus(change.timerId, change.expectedVersion, desired(change), change.correlationId) == ActionMutation.APPLIED)
            OwnedTimerStatusResult.APPLIED else OwnedTimerStatusResult.CONFLICT
    }

    private fun desired(change: ValidatedTimerStatusChange) = when (change.operation) {
        TimerStatusOperation.CANCEL -> TimerStatus.CANCELLED
    }
}
