package network.orion.core.commands.memories

import java.util.UUID
import network.orion.core.commands.Command

enum class MemoryStatusOperation(val skillName: String) {
    DELETE("memory.delete"),
    CORRECT("memory.correct"),
    CONFIRM_CLAIM("memory.claim.confirm"),
    REJECT_CLAIM("memory.claim.reject"),
    ARCHIVE_CONVERSATION("conversation.archive"),
    FORGET_CONVERSATION("conversation.forget"),
}

enum class MemoryStatusResult { QUEUED, REJECTED, UNAVAILABLE }

/** Expected versions are concurrency data. The Core captures consent independently. */
sealed interface MemoryStatusCommand : Command<MemoryStatusResult> {
    val memoryId: UUID
    val expectedVersion: Long
    val idempotencyKey: UUID
    val correlationId: UUID
    val operation: MemoryStatusOperation
}

data class ForgetMemory(override val memoryId: UUID, override val expectedVersion: Long,
    override val idempotencyKey: UUID, override val correlationId: UUID) : MemoryStatusCommand {
    override val operation get() = MemoryStatusOperation.DELETE
    override fun toString() = "ForgetMemory(<redacted>)"
}

data class CorrectMemory(override val memoryId: UUID, override val expectedVersion: Long,
    override val idempotencyKey: UUID, override val correlationId: UUID,
    val replacementId: UUID, val content: String, val allowSearchIndex: Boolean = false) : MemoryStatusCommand {
    override val operation get() = MemoryStatusOperation.CORRECT
    override fun toString() = "CorrectMemory(<redacted>)"
}

enum class MemoryClaimDecision(val operation: MemoryStatusOperation) {
    CONFIRM(MemoryStatusOperation.CONFIRM_CLAIM),
    REJECT(MemoryStatusOperation.REJECT_CLAIM),
}

data class ReviewMemoryClaim(override val memoryId: UUID, override val expectedVersion: Long,
    override val idempotencyKey: UUID, override val correlationId: UUID,
    val claimId: UUID, val expectedClaimVersion: Long, val decision: MemoryClaimDecision) : MemoryStatusCommand {
    override val operation get() = decision.operation
    override fun toString() = "ReviewMemoryClaim(<redacted>)"
}

enum class MemoryStatusRejection { INVALID_VERSION, INVALID_REPLACEMENT, INVALID_TRANSCRIPT }

sealed interface MemoryStatusPreparation {
    class Ready internal constructor(val change: ValidatedMemoryStatusChange) : MemoryStatusPreparation {
        override fun toString() = "MemoryStatusPreparation.Ready(<redacted>)"
    }
    data class Rejected(val reason: MemoryStatusRejection) : MemoryStatusPreparation
}

/** Canonical schema v1. The static Skill name binds the operation in the ActionRequest. */
class ValidatedMemoryStatusChange internal constructor(
    val command: MemoryStatusCommand,
    val replacement: ValidatedCreateMemory? = null,
    private val encodedTranscript: String? = null,
) {
    val memoryId get() = command.memoryId
    val expectedVersion get() = command.expectedVersion
    val idempotencyKey get() = command.idempotencyKey
    val correlationId get() = command.correlationId
    val operation get() = command.operation
    val skillName get() = operation.skillName
    val argumentSchemaVersion get() = 1
    val normalizedTarget get() = when (command) {
        is ReviewMemoryClaim -> "memory/$memoryId/claim/${command.claimId}"
        else -> "memory/$memoryId"
    }
    val canonicalNormalizedArguments: String get() = buildString {
        append("{\"memoryId\":\"$memoryId\",\"expectedVersion\":$expectedVersion")
        when (command) {
            is ForgetMemory, is ForgetConversation -> Unit
            is ArchiveConversation -> append(",\"transcript\":\"").append(checkNotNull(encodedTranscript)).append('"')
            is CorrectMemory -> append(",\"replacement\":").append(checkNotNull(replacement).canonicalNormalizedArguments)
            is ReviewMemoryClaim -> append(",\"claimId\":\"${command.claimId}\",\"claimVersion\":${command.expectedClaimVersion}")
        }
        append('}')
    }
    override fun toString() = "ValidatedMemoryStatusChange(<redacted>)"
}

object OrionMemoryStatusPreparer {
    fun prepare(command: MemoryStatusCommand): MemoryStatusPreparation {
        if (command.expectedVersion !in 0 until Long.MAX_VALUE)
            return MemoryStatusPreparation.Rejected(MemoryStatusRejection.INVALID_VERSION)
        return when (command) {
            is ForgetMemory, is ForgetConversation -> MemoryStatusPreparation.Ready(ValidatedMemoryStatusChange(command))
            is ArchiveConversation -> try {
                MemoryStatusPreparation.Ready(ValidatedMemoryStatusChange(command, encodedTranscript = command.transcript.encode()))
            } catch (_: IllegalArgumentException) {
                MemoryStatusPreparation.Rejected(MemoryStatusRejection.INVALID_TRANSCRIPT)
            }
            is ReviewMemoryClaim -> {
                if (command.expectedClaimVersion !in 0 until Long.MAX_VALUE)
                    MemoryStatusPreparation.Rejected(MemoryStatusRejection.INVALID_VERSION)
                else MemoryStatusPreparation.Ready(ValidatedMemoryStatusChange(command))
            }
            is CorrectMemory -> {
                if (command.replacementId == command.memoryId)
                    return MemoryStatusPreparation.Rejected(MemoryStatusRejection.INVALID_REPLACEMENT)
                val ready = OrionCreateMemoryPreparer.prepare(CreateMemory(command.replacementId, command.content,
                    command.idempotencyKey, command.correlationId, command.allowSearchIndex)) as? CreateMemoryPreparation.Ready
                    ?: return MemoryStatusPreparation.Rejected(MemoryStatusRejection.INVALID_REPLACEMENT)
                MemoryStatusPreparation.Ready(ValidatedMemoryStatusChange(command.copy(content = ready.memory.content), ready.memory))
            }
        }
    }
}
