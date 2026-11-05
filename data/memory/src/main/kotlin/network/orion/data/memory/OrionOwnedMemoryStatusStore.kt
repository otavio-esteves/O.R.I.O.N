package network.orion.data.memory

import network.orion.core.actions.*
import network.orion.core.commands.memories.*
import network.orion.core.security.*
import network.orion.core.skills.metadata.memories.OrionMemoryStatusDescriptors
import network.orion.core.time.*
import network.orion.data.actions.*
import network.orion.data.database.*

enum class OwnedMemoryStatusResult { APPLIED, DEDUP, NOT_OWNED, PAYLOAD_MISMATCH, CONFLICT, KEY_UNAVAILABLE, UNREADABLE }

fun interface OrionOwnedMemoryStatusWriter {
    fun change(change: ValidatedMemoryStatusChange, owner: MemoryExecutionOwner): OwnedMemoryStatusResult
}

/** Verifies actual RUNNING ownership inside the engine's admitted transaction; IDs alone cannot write. */
class OrionOwnedMemoryStatusStore(private val database: OrionDatabase, encryption: OrionFieldEncryption,
    private val clock: OrionClock) : OrionOwnedMemoryStatusWriter {
    private val actions = OrionActionStore(database, encryption, clock)
    private val memories = OrionMemoryStore(database, encryption, clock)

    fun matchesExisting(change: ValidatedMemoryStatusChange): Boolean {
        return database.memories().find(change.memoryId.toString()) == null &&
            database.memories().deletionCommitted(change.memoryId.toString(), change.expectedVersion + 1) > 0
    }

    override fun change(change: ValidatedMemoryStatusChange, owner: MemoryExecutionOwner): OwnedMemoryStatusResult {
        if (!database.inTransaction()) return OwnedMemoryStatusResult.NOT_OWNED
        val row = database.actions().find(owner.actionId.toString()) ?: return OwnedMemoryStatusResult.NOT_OWNED
        if (row.status != ActionStatus.RUNNING || row.version != owner.version || row.executionId != owner.executionId.toString())
            return OwnedMemoryStatusResult.NOT_OWNED
        val lease = try {
            ActionExecutionLease(owner.executionId, row.claimedBy ?: return OwnedMemoryStatusResult.NOT_OWNED,
                BootSessionId(row.leaseBootSessionId ?: return OwnedMemoryStatusResult.NOT_OWNED),
                row.leaseStartedElapsedRealtime ?: return OwnedMemoryStatusResult.NOT_OWNED,
                row.leaseExpiresElapsedRealtime ?: return OwnedMemoryStatusResult.NOT_OWNED)
        } catch (_: IllegalArgumentException) { return OwnedMemoryStatusResult.NOT_OWNED }
        if (lease.statusAt(clock.monotonicNow()) != ActionLeaseStatus.ACTIVE) return OwnedMemoryStatusResult.NOT_OWNED
        val stored = when (val read = actions.read(owner.actionId)) {
            is ActionReadResult.Ready -> read.action
            is ActionReadResult.Blocked -> return if (read.reason == ActionStoreOutcome.KEY_UNAVAILABLE ||
                read.reason == ActionStoreOutcome.KEY_INVALIDATED) OwnedMemoryStatusResult.KEY_UNAVAILABLE else OwnedMemoryStatusResult.UNREADABLE
        }
        val descriptor = OrionMemoryStatusDescriptors.forName(change.skillName) ?: return OwnedMemoryStatusResult.PAYLOAD_MISMATCH
        val expected = NormalizedActionRequest(owner.actionId, descriptor.name, change.canonicalNormalizedArguments,
            change.normalizedTarget, descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
            descriptor.recoveryStrategy, descriptor.idempotencyMode, stored.request.policyVersion)
        if (stored.request != expected || !stored.requiresConfirmation || row.idempotencyScope != change.skillName ||
            row.idempotencyKey != change.idempotencyKey.toString()) return OwnedMemoryStatusResult.PAYLOAD_MISMATCH
        val confirmation = stored.confirmation ?: return OwnedMemoryStatusResult.NOT_OWNED
        if (ActionConfirmationBinding(clock).validate(expected, confirmation) != ConfirmationBindingResult.VALID_BINDING)
            return OwnedMemoryStatusResult.NOT_OWNED
        val target = database.memories().find(change.memoryId.toString()) ?: return OwnedMemoryStatusResult.CONFLICT
        if (target.version == change.expectedVersion + 1 && target.status == desired(change)) return OwnedMemoryStatusResult.DEDUP
        if (target.version != change.expectedVersion || target.status != MemoryStatus.ACTIVE || target.origin != MemoryOrigin.USER_DECLARED || !target.userConfirmed) return OwnedMemoryStatusResult.CONFLICT
        if (memories.delete(change.memoryId, change.expectedVersion, change.correlationId) != ActionMutation.APPLIED)
            return OwnedMemoryStatusResult.CONFLICT
        database.records().appendAudit(AuditEntity(java.util.UUID.randomUUID().toString(), change.memoryId.toString(),
            change.expectedVersion + 1, "MEMORY_FORGOTTEN", clock.wallNow().toEpochMilli(),
            change.correlationId.toString(), preserveUntilReview = true))
        return OwnedMemoryStatusResult.APPLIED
    }

    private fun desired(change: ValidatedMemoryStatusChange) = MemoryStatus.DELETED
}
