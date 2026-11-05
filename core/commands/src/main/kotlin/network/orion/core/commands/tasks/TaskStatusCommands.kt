package network.orion.core.commands.tasks

import java.util.UUID
import network.orion.core.commands.Command

enum class TaskStatusOperation { COMPLETE, CANCEL }
enum class TaskStatusResult { UNAVAILABLE }

/** Expected version is domain concurrency data, never authorization or a confirmation flag. */
sealed interface TaskStatusCommand : Command<TaskStatusResult> {
    val taskId: UUID
    val expectedVersion: Long
    val idempotencyKey: UUID
    val correlationId: UUID
    val operation: TaskStatusOperation
}

data class CompleteTask(override val taskId: UUID, override val expectedVersion: Long,
    override val idempotencyKey: UUID, override val correlationId: UUID) : TaskStatusCommand {
    override val operation get() = TaskStatusOperation.COMPLETE
    override fun toString() = "CompleteTask(<redacted>)"
}

data class CancelTask(override val taskId: UUID, override val expectedVersion: Long,
    override val idempotencyKey: UUID, override val correlationId: UUID) : TaskStatusCommand {
    override val operation get() = TaskStatusOperation.CANCEL
    override fun toString() = "CancelTask(<redacted>)"
}

enum class TaskStatusRejection { INVALID_VERSION }

sealed interface TaskStatusPreparation {
    class Ready internal constructor(val change: ValidatedTaskStatusChange) : TaskStatusPreparation {
        override fun toString() = "TaskStatusPreparation.Ready(<redacted>)"
    }
    data class Rejected(val reason: TaskStatusRejection) : TaskStatusPreparation
}

/** Fixed schema-v1 material: taskId then expectedVersion. Operation is bound by static Skill name. */
class ValidatedTaskStatusChange internal constructor(
    val taskId: UUID, val expectedVersion: Long, val idempotencyKey: UUID,
    val correlationId: UUID, val operation: TaskStatusOperation,
) {
    val skillName get() = when (operation) {
        TaskStatusOperation.COMPLETE -> "task.complete"
        TaskStatusOperation.CANCEL -> "task.cancel"
    }
    val argumentSchemaVersion get() = 1
    val normalizedTarget get() = "task/$taskId"
    val canonicalNormalizedArguments get() = "{\"taskId\":\"$taskId\",\"expectedVersion\":$expectedVersion}"
    override fun toString() = "ValidatedTaskStatusChange(<redacted>)"
}

object OrionTaskStatusPreparer {
    fun prepare(command: TaskStatusCommand): TaskStatusPreparation =
        if (command.expectedVersion < 0 || command.expectedVersion == Long.MAX_VALUE)
            TaskStatusPreparation.Rejected(TaskStatusRejection.INVALID_VERSION)
        else TaskStatusPreparation.Ready(ValidatedTaskStatusChange(command.taskId, command.expectedVersion,
            command.idempotencyKey, command.correlationId, command.operation))
}
