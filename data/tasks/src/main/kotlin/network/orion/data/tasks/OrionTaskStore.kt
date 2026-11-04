package network.orion.data.tasks

import java.util.UUID
import network.orion.core.model.Sensitivity
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.database.*

sealed interface TaskCreationResult {
    data class Created(val taskId: UUID) : TaskCreationResult
    data class Blocked(val reason: FieldCryptoFailure) : TaskCreationResult
}

/**
 * Main-owned encrypted local repository. Inputs have already passed trusted validation/authorization.
 * No public command, scheduler, Skill or UI wiring is introduced. Call outside the UI thread.
 */
class OrionTaskStore(private val database: OrionDatabase, private val encryption: OrionFieldEncryption, private val clock: WallClock) {
    fun create(taskId: UUID, canonicalContent: ByteArray, sensitivity: Sensitivity, correlationId: UUID): TaskCreationResult {
        val encrypted = when (val result = encryption.encrypt(canonicalContent, context(taskId))) {
            is FieldEncryptionResult.Encrypted -> FieldEnvelopeCodec.encode(result.envelope)
            is FieldEncryptionResult.Failure -> return TaskCreationResult.Blocked(result.reason)
        }
        val now = clock.wallNow().toEpochMilli()
        database.tasks().createWithEvent(TaskEntity(taskId.toString(), encrypted, sensitivity, now, now),
            DomainEventOutboxEntity(UUID.randomUUID().toString(), taskId.toString(), 0, "TASK_CREATED", 1,
                sensitivity, now, correlationId.toString()))
        return TaskCreationResult.Created(taskId)
    }

    /** Null means absent. Corrupt/key-unavailable content is a typed decryption failure, never plaintext fallback. */
    fun readContent(taskId: UUID): FieldDecryptionResult? {
        val task = database.tasks().find(taskId.toString()) ?: return null
        val decoded = FieldEnvelopeCodec.decode(task.contentEnvelope)
        if (decoded !is FieldEnvelopeDecodingResult.Decoded) return FieldDecryptionResult.Failure(FieldCryptoFailure.UNSUPPORTED_ENVELOPE)
        return encryption.decrypt(decoded.envelope, context(taskId))
    }

    fun changeStatus(taskId: UUID, expectedVersion: Long, status: TaskStatus, correlationId: UUID): ActionMutation {
        val current = database.tasks().find(taskId.toString()) ?: return ActionMutation.NOT_FOUND
        if (current.version != expectedVersion) return ActionMutation.CONFLICT
        val now = clock.wallNow().toEpochMilli()
        require(status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED)
        return database.tasks().changeStatusWithEvent(taskId.toString(), expectedVersion, status, now,
            DomainEventOutboxEntity(UUID.randomUUID().toString(), taskId.toString(), expectedVersion + 1,
                if (status == TaskStatus.COMPLETED) "TASK_COMPLETED" else "TASK_CANCELLED", 1,
                current.sensitivity, now, correlationId.toString()))
    }

    private fun context(taskId: UUID) = FieldEncryptionContext("task/$taskId", "content", 1)
}
