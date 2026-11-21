package network.orion.data.memory

import java.util.UUID
import network.orion.core.model.Sensitivity

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

    fun matchesExisting(change: ValidatedMemoryStatusChange): Boolean = when (val command = change.command) {
        is ForgetMemory -> database.memories().find(change.memoryId.toString()) == null &&
            database.memories().deletionCommitted(change.memoryId.toString(), change.expectedVersion + 1) > 0
        is CorrectMemory -> database.memories().find(command.replacementId.toString())?.supersedesId == change.memoryId.toString()
        is ReviewMemoryClaim -> database.records().memoryClaim(change.memoryId.toString(), command.claimId.toString())?.let {
            it.version >= command.expectedClaimVersion + 1 &&
                it.userConfirmed == (command.decision == MemoryClaimDecision.CONFIRM)
        } == true
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
        if (target.version != change.expectedVersion || target.status != MemoryStatus.ACTIVE ||
            target.origin != MemoryOrigin.USER_DECLARED || !target.userConfirmed || target.sensitivity != Sensitivity.PERSONAL)
            return OwnedMemoryStatusResult.CONFLICT
        return when (val command = change.command) {
            is ForgetMemory -> forget(command)
            is CorrectMemory -> correct(command, checkNotNull(change.replacement), target)
            is ReviewMemoryClaim -> review(command)
        }
    }

    private fun forget(command: ForgetMemory): OwnedMemoryStatusResult {
        if (memories.delete(command.memoryId, command.expectedVersion, command.correlationId) != ActionMutation.APPLIED)
            return OwnedMemoryStatusResult.CONFLICT
        database.records().appendAudit(AuditEntity(UUID.randomUUID().toString(), command.memoryId.toString(),
            command.expectedVersion + 1, "MEMORY_FORGOTTEN", clock.wallNow().toEpochMilli(),
            command.correlationId.toString(), preserveUntilReview = true))
        return OwnedMemoryStatusResult.APPLIED
    }

    private fun correct(command: CorrectMemory, replacement: ValidatedCreateMemory, target: MemoryEntity): OwnedMemoryStatusResult {
        val id = command.replacementId.toString()
        if (database.memories().find(id) != null || database.memories().forgotten(id) != 0 ||
            database.memories().retainedMemoryCount() >= 10_000 || !target.lifecycle().isValidAt(clock.wallNow()))
            return OwnedMemoryStatusResult.CONFLICT
        val bytes = replacement.canonicalNormalizedArguments.toByteArray(Charsets.UTF_8)
        return try {
            when (val result = memories.supersede(command.memoryId, command.expectedVersion, command.replacementId,
                bytes, target.sensitivity, MemoryOrigin.USER_DECLARED, 1.0, emptyList(), command.correlationId,
                userConfirmed = true)) {
                is MemoryWriteResult.Stored -> OwnedMemoryStatusResult.APPLIED
                is MemoryWriteResult.Rejected -> OwnedMemoryStatusResult.CONFLICT
                is MemoryWriteResult.Blocked -> if (result.reason in setOf(FieldCryptoFailure.KEY_UNAVAILABLE,
                    FieldCryptoFailure.KEY_INVALIDATED, FieldCryptoFailure.CRYPTO_UNAVAILABLE)) OwnedMemoryStatusResult.KEY_UNAVAILABLE
                    else OwnedMemoryStatusResult.UNREADABLE
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun review(command: ReviewMemoryClaim): OwnedMemoryStatusResult {
        val memoryId = command.memoryId.toString()
        val claimId = command.claimId.toString()
        val claim = database.records().memoryClaim(memoryId, claimId) ?: return OwnedMemoryStatusResult.CONFLICT
        if (claim.sensitivity !in setOf(Sensitivity.PUBLIC, Sensitivity.PERSONAL)) return OwnedMemoryStatusResult.CONFLICT
        val at = clock.wallNow().toEpochMilli()
        val confirmed = command.decision == MemoryClaimDecision.CONFIRM
        val type = if (confirmed) "MEMORY_CLAIM_CONFIRMED" else "MEMORY_CLAIM_REJECTED"
        val event = DomainEventOutboxEntity(UUID.randomUUID().toString(), claimId, command.expectedClaimVersion + 1,
            type, 1, claim.sensitivity, at, command.correlationId.toString())
        return if (database.records().reviewMemoryClaimWithEvent(memoryId, command.expectedVersion, claimId,
            command.expectedClaimVersion, confirmed, at, event) == ActionMutation.APPLIED) OwnedMemoryStatusResult.APPLIED
        else OwnedMemoryStatusResult.CONFLICT
    }
}
