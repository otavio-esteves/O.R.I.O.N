package network.orion.data.tasks

import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.tasks.ValidatedCreateTask
import network.orion.core.model.Sensitivity
import network.orion.core.security.*
import network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor
import network.orion.core.time.*
import network.orion.data.actions.*
import network.orion.data.database.OrionDatabase

data class TaskExecutionOwner(val actionId: UUID, val executionId: UUID, val version: Long) {
    override fun toString() = "TaskExecutionOwner(<redacted>)"
}

enum class OwnedTaskWriteResult { CREATED, DEDUP, NOT_OWNED, PAYLOAD_MISMATCH, TASK_ID_CONFLICT, KEY_UNAVAILABLE, UNREADABLE }

/** MAIN provides the real implementation. A test port or IDs are not authorization credentials. */
fun interface OrionOwnedTaskWriter {
    fun create(task: ValidatedCreateTask, owner: TaskExecutionOwner): OwnedTaskWriteResult
}

/**
 * Called only inside the Action Engine's admitted writer transaction. Neither
 * this adapter nor the Skill starts/finishes an execution or grants authority.
 * A direct call without that transaction and active exact ownership cannot write.
 */
class OrionOwnedTaskStore(private val database: OrionDatabase, encryption: OrionFieldEncryption,
    private val clock: OrionClock) : OrionOwnedTaskWriter {
    private val actions = OrionActionStore(database, encryption, clock)
    private val tasks = OrionTaskStore(database, encryption, clock)

    /** Read-only verification before returning a previously committed action result. */
    fun matchesExisting(task: ValidatedCreateTask): Boolean {
        val read = tasks.readContent(task.taskId) as? FieldDecryptionResult.Plaintext ?: return false
        val clear = read.copyBytes()
        val expected = task.canonicalNormalizedArguments.toByteArray(Charsets.UTF_8)
        return try { clear.contentEquals(expected) } finally { clear.fill(0); expected.fill(0) }
    }

    override fun create(task: ValidatedCreateTask, owner: TaskExecutionOwner): OwnedTaskWriteResult {
        if (!database.inTransaction()) return OwnedTaskWriteResult.NOT_OWNED
        val row = database.actions().find(owner.actionId.toString()) ?: return OwnedTaskWriteResult.NOT_OWNED
        if (row.status != ActionStatus.RUNNING || row.version != owner.version || row.executionId != owner.executionId.toString())
            return OwnedTaskWriteResult.NOT_OWNED
        val lease = try {
            ActionExecutionLease(owner.executionId, row.claimedBy ?: return OwnedTaskWriteResult.NOT_OWNED,
                BootSessionId(row.leaseBootSessionId ?: return OwnedTaskWriteResult.NOT_OWNED),
                row.leaseStartedElapsedRealtime ?: return OwnedTaskWriteResult.NOT_OWNED,
                row.leaseExpiresElapsedRealtime ?: return OwnedTaskWriteResult.NOT_OWNED)
        } catch (_: IllegalArgumentException) { return OwnedTaskWriteResult.NOT_OWNED }
        if (lease.statusAt(clock.monotonicNow()) != ActionLeaseStatus.ACTIVE) return OwnedTaskWriteResult.NOT_OWNED
        val stored = when (val read = actions.read(owner.actionId)) {
            is ActionReadResult.Ready -> read.action
            is ActionReadResult.Blocked -> return if (read.reason == ActionStoreOutcome.KEY_UNAVAILABLE ||
                read.reason == ActionStoreOutcome.KEY_INVALIDATED) OwnedTaskWriteResult.KEY_UNAVAILABLE else OwnedTaskWriteResult.UNREADABLE
        }
        val descriptor = OrionCreateTaskDescriptor.value
        val expected = NormalizedActionRequest(owner.actionId, descriptor.name, task.canonicalNormalizedArguments,
            task.normalizedTarget, descriptor.authorizationLevel, descriptor.argumentSchemaVersion, descriptor.sideEffectClass,
            descriptor.recoveryStrategy, descriptor.idempotencyMode, stored.request.policyVersion)
        if (stored.request != expected || !stored.requiresConfirmation || row.idempotencyScope != "task.create" ||
            row.idempotencyKey != task.idempotencyKey.toString()) return OwnedTaskWriteResult.PAYLOAD_MISMATCH
        val confirmation = stored.confirmation ?: return OwnedTaskWriteResult.NOT_OWNED
        if (ActionConfirmationBinding(clock).validate(stored.request, confirmation) != ConfirmationBindingResult.VALID_BINDING)
            return OwnedTaskWriteResult.NOT_OWNED
        val content = task.canonicalNormalizedArguments.toByteArray(Charsets.UTF_8)
        try {
            val previous = tasks.readContent(task.taskId)
            if (previous != null) {
                if (previous is FieldDecryptionResult.Failure) return if (previous.reason == FieldCryptoFailure.KEY_UNAVAILABLE ||
                    previous.reason == FieldCryptoFailure.KEY_INVALIDATED) OwnedTaskWriteResult.KEY_UNAVAILABLE else OwnedTaskWriteResult.UNREADABLE
                val clear = (previous as FieldDecryptionResult.Plaintext).copyBytes()
                return try {
                    if (clear.contentEquals(content)) OwnedTaskWriteResult.DEDUP else OwnedTaskWriteResult.TASK_ID_CONFLICT
                } finally { clear.fill(0) }
            }
            return when (val created = tasks.create(task.taskId, content, Sensitivity.SENSITIVE, task.correlationId)) {
                is TaskCreationResult.Created -> OwnedTaskWriteResult.CREATED
                is TaskCreationResult.Blocked -> if (created.reason == FieldCryptoFailure.KEY_UNAVAILABLE ||
                    created.reason == FieldCryptoFailure.KEY_INVALIDATED) OwnedTaskWriteResult.KEY_UNAVAILABLE else OwnedTaskWriteResult.UNREADABLE
            }
        } finally { content.fill(0) }
    }
}
