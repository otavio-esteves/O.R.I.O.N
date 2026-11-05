package network.orion.skills.tasks

import network.orion.core.commands.tasks.TaskStatusOperation
import network.orion.core.commands.tasks.ValidatedTaskStatusChange
import network.orion.core.model.OrionIntent
import network.orion.core.skills.metadata.tasks.OrionTaskStatusDescriptors
import network.orion.data.tasks.*
import network.orion.skills.api.*

/** One static descriptor per instance/operation. No suspension, Android side effects or authority from intent metadata. */
class OrionTaskStatusSkill(private val operation: TaskStatusOperation, private val writer: OrionOwnedTaskStatusWriter) : OrionSkill {
    override val descriptor get() = when (operation) {
        TaskStatusOperation.COMPLETE -> OrionTaskStatusDescriptors.complete
        TaskStatusOperation.CANCEL -> OrionTaskStatusDescriptors.cancel
    }
    override fun canHandle(intent: OrionIntent<*>) = intent.intentType == descriptor.name &&
        (intent.arguments as? ValidatedTaskStatusChange)?.operation == operation
    override suspend fun execute(intent: OrionIntent<*>, context: SkillContext) = executeInTransaction(intent, context)
    fun executeInTransaction(intent: OrionIntent<*>, context: SkillContext): SkillResult {
        if (!canHandle(intent)) return SkillResult.Rejected(SkillFailure.UNSUPPORTED_REQUEST)
        val change = intent.arguments as ValidatedTaskStatusChange
        return when (writer.change(change, TaskExecutionOwner(context.actionId, context.executionId, context.actionVersion))) {
            OwnedTaskStatusResult.APPLIED -> SkillResult.Completed(change.taskId)
            OwnedTaskStatusResult.DEDUP -> SkillResult.Completed(change.taskId, true)
            OwnedTaskStatusResult.NOT_OWNED -> SkillResult.Rejected(SkillFailure.EXECUTION_NOT_OWNED)
            OwnedTaskStatusResult.PAYLOAD_MISMATCH -> SkillResult.Rejected(SkillFailure.PAYLOAD_MISMATCH)
            OwnedTaskStatusResult.CONFLICT -> SkillResult.Rejected(SkillFailure.STATE_CONFLICT)
            OwnedTaskStatusResult.KEY_UNAVAILABLE, OwnedTaskStatusResult.UNREADABLE -> SkillResult.Rejected(SkillFailure.STORAGE_UNAVAILABLE)
        }
    }
}
