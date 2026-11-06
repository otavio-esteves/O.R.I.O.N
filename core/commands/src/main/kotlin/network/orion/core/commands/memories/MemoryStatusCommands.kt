package network.orion.core.commands.memories

import java.util.UUID
import network.orion.core.commands.Command

enum class MemoryStatusOperation { DELETE }
enum class MemoryStatusResult { QUEUED, REJECTED, UNAVAILABLE }

/** Expected version is domain concurrency data, never authorization or a confirmation flag. */
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

enum class MemoryStatusRejection { INVALID_VERSION }

sealed interface MemoryStatusPreparation {
    class Ready internal constructor(val change: ValidatedMemoryStatusChange) : MemoryStatusPreparation {
        override fun toString() = "MemoryStatusPreparation.Ready(<redacted>)"
    }
    data class Rejected(val reason: MemoryStatusRejection) : MemoryStatusPreparation
}

/** Fixed schema-v1 material: memoryId then expectedVersion. Operation is bound by static Skill name. */
class ValidatedMemoryStatusChange internal constructor(
    val memoryId: UUID, val expectedVersion: Long, val idempotencyKey: UUID,
    val correlationId: UUID, val operation: MemoryStatusOperation,
) {
    val skillName get() = "memory.delete"
    val argumentSchemaVersion get() = 1
    val normalizedTarget get() = "memory/$memoryId"
    val canonicalNormalizedArguments get() = "{\"memoryId\":\"$memoryId\",\"expectedVersion\":$expectedVersion}"
    override fun toString() = "ValidatedMemoryStatusChange(<redacted>)"
}

object OrionMemoryStatusPreparer {
    fun prepare(command: MemoryStatusCommand): MemoryStatusPreparation =
        if (command.expectedVersion < 0 || command.expectedVersion == Long.MAX_VALUE)
            MemoryStatusPreparation.Rejected(MemoryStatusRejection.INVALID_VERSION)
        else MemoryStatusPreparation.Ready(ValidatedMemoryStatusChange(command.memoryId, command.expectedVersion,
            command.idempotencyKey, command.correlationId, command.operation))
}
