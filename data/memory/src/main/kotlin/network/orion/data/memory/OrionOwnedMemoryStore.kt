package network.orion.data.memory

import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.memories.ValidatedCreateMemory
import network.orion.core.model.Sensitivity
import network.orion.core.security.*
import network.orion.core.skills.metadata.memories.OrionCreateMemoryDescriptor
import network.orion.core.time.*
import network.orion.data.actions.*
import network.orion.data.database.OrionDatabase
import network.orion.data.database.MemoryOrigin
import network.orion.data.database.MemoryStatus

data class MemoryExecutionOwner(val actionId: UUID, val executionId: UUID, val version: Long) {
    override fun toString() = "MemoryExecutionOwner(<redacted>)"
}

enum class OwnedMemoryWriteResult { CREATED, DEDUP, NOT_OWNED, PAYLOAD_MISMATCH, MEMORY_ID_CONFLICT, KEY_UNAVAILABLE, UNREADABLE }

/** MAIN provides the real implementation. A test port or IDs are not authorization credentials. */
fun interface OrionOwnedMemoryWriter {
    fun create(memory: ValidatedCreateMemory, owner: MemoryExecutionOwner): OwnedMemoryWriteResult
}

/**
 * Called only inside the Action Engine's admitted writer transaction. Neither
 * this adapter nor the Skill starts/finishes an execution or grants authority.
 * A direct call without that transaction and active exact ownership cannot write.
 */
class OrionOwnedMemoryStore(private val database: OrionDatabase, encryption: OrionFieldEncryption,
    private val clock: OrionClock) : OrionOwnedMemoryWriter {
    private val actions = OrionActionStore(database, encryption, clock)
    private val memories = OrionMemoryStore(database, encryption, clock)

    /** Read-only verification before returning a previously committed action result. */
    fun matchesExisting(memory: ValidatedCreateMemory): Boolean {
        val entity = database.memories().find(memory.memoryId.toString()) ?: return false
        if (entity.origin != MemoryOrigin.USER_DECLARED || !entity.userConfirmed || entity.sensitivity != Sensitivity.PERSONAL || entity.status != MemoryStatus.ACTIVE) return false
        val read = memories.readContent(memory.memoryId) as? FieldDecryptionResult.Plaintext ?: return false
        val clear = read.copyBytes()
        val expected = memory.canonicalNormalizedArguments.toByteArray(Charsets.UTF_8)
        return try { clear.contentEquals(expected) } finally { clear.fill(0); expected.fill(0) }
    }

    override fun create(memory: ValidatedCreateMemory, owner: MemoryExecutionOwner): OwnedMemoryWriteResult {
        if (!database.inTransaction()) return OwnedMemoryWriteResult.NOT_OWNED
        val row = database.actions().find(owner.actionId.toString()) ?: return OwnedMemoryWriteResult.NOT_OWNED
        if (row.status != ActionStatus.RUNNING || row.version != owner.version || row.executionId != owner.executionId.toString())
            return OwnedMemoryWriteResult.NOT_OWNED
        val lease = try {
            ActionExecutionLease(owner.executionId, row.claimedBy ?: return OwnedMemoryWriteResult.NOT_OWNED,
                BootSessionId(row.leaseBootSessionId ?: return OwnedMemoryWriteResult.NOT_OWNED),
                row.leaseStartedElapsedRealtime ?: return OwnedMemoryWriteResult.NOT_OWNED,
                row.leaseExpiresElapsedRealtime ?: return OwnedMemoryWriteResult.NOT_OWNED)
        } catch (_: IllegalArgumentException) { return OwnedMemoryWriteResult.NOT_OWNED }
        if (lease.statusAt(clock.monotonicNow()) != ActionLeaseStatus.ACTIVE) return OwnedMemoryWriteResult.NOT_OWNED
        val stored = when (val read = actions.read(owner.actionId)) {
            is ActionReadResult.Ready -> read.action
            is ActionReadResult.Blocked -> return if (read.reason == ActionStoreOutcome.KEY_UNAVAILABLE ||
                read.reason == ActionStoreOutcome.KEY_INVALIDATED) OwnedMemoryWriteResult.KEY_UNAVAILABLE else OwnedMemoryWriteResult.UNREADABLE
        }
        val descriptor = OrionCreateMemoryDescriptor.value
        val expected = NormalizedActionRequest(owner.actionId, descriptor.name, memory.canonicalNormalizedArguments,
            memory.normalizedTarget, descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
            descriptor.recoveryStrategy, descriptor.idempotencyMode, stored.request.policyVersion)
        if (stored.request != expected || !stored.requiresConfirmation || row.idempotencyScope != "memory.create" ||
            row.idempotencyKey != memory.idempotencyKey.toString()) return OwnedMemoryWriteResult.PAYLOAD_MISMATCH
        val confirmation = stored.confirmation ?: return OwnedMemoryWriteResult.NOT_OWNED
        if (ActionConfirmationBinding(clock).validate(stored.request, confirmation) != ConfirmationBindingResult.VALID_BINDING)
            return OwnedMemoryWriteResult.NOT_OWNED
        if (database.memories().forgotten(memory.memoryId.toString()) != 0) return OwnedMemoryWriteResult.MEMORY_ID_CONFLICT
        val content = memory.canonicalNormalizedArguments.toByteArray(Charsets.UTF_8)
        try {
            val previousRow = database.memories().find(memory.memoryId.toString())
            // Bound permanent privacy tombstones together with active memory storage.
            if (previousRow == null && database.memories().retainedMemoryCount() >= 10_000)
                return OwnedMemoryWriteResult.MEMORY_ID_CONFLICT
            if (previousRow != null && (previousRow.origin != MemoryOrigin.USER_DECLARED || !previousRow.userConfirmed ||
                previousRow.sensitivity != Sensitivity.PERSONAL || previousRow.status != MemoryStatus.ACTIVE))
                return OwnedMemoryWriteResult.MEMORY_ID_CONFLICT
            val previous = memories.readContent(memory.memoryId)
            if (previous != null) {
                if (previous is FieldDecryptionResult.Failure) return if (previous.reason == FieldCryptoFailure.KEY_UNAVAILABLE ||
                    previous.reason == FieldCryptoFailure.KEY_INVALIDATED) OwnedMemoryWriteResult.KEY_UNAVAILABLE else OwnedMemoryWriteResult.UNREADABLE
                val clear = (previous as FieldDecryptionResult.Plaintext).copyBytes()
                return try {
                    if (clear.contentEquals(content)) OwnedMemoryWriteResult.DEDUP else OwnedMemoryWriteResult.MEMORY_ID_CONFLICT
                } finally { clear.fill(0) }
            }
            return when (val created = memories.create(memory.memoryId, content, Sensitivity.PERSONAL, MemoryOrigin.USER_DECLARED, 1.0,
                emptyList(), memory.correlationId, userConfirmed = true)) {
                is MemoryWriteResult.Stored -> OwnedMemoryWriteResult.CREATED
                is MemoryWriteResult.Blocked -> if (created.reason == FieldCryptoFailure.KEY_UNAVAILABLE ||
                    created.reason == FieldCryptoFailure.KEY_INVALIDATED) OwnedMemoryWriteResult.KEY_UNAVAILABLE else OwnedMemoryWriteResult.UNREADABLE
                is MemoryWriteResult.Rejected -> OwnedMemoryWriteResult.MEMORY_ID_CONFLICT
            }
        } finally { content.fill(0) }
    }
}
