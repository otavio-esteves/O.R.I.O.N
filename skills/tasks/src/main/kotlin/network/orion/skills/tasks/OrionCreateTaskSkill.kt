package network.orion.skills.tasks

import network.orion.core.commands.tasks.ValidatedCreateTask
import network.orion.core.model.OrionIntent
import network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor
import network.orion.data.tasks.*
import network.orion.skills.api.*

/** No Android side effects or suspension. MAIN owns the verified repository and execution transaction. */
class OrionCreateTaskSkill(private val writer: OrionOwnedTaskWriter) : OrionSkill {
    override val descriptor get() = OrionCreateTaskDescriptor.value
    override fun canHandle(intent: OrionIntent<*>) = intent.intentType == descriptor.name && intent.arguments is ValidatedCreateTask
    override suspend fun execute(intent: OrionIntent<*>, context: SkillContext) = executeInTransaction(intent, context)

    /** Same implementation as the normative suspend port, for the bounded blocking local transaction. */
    fun executeInTransaction(intent: OrionIntent<*>, context: SkillContext): SkillResult {
        if (!canHandle(intent)) return SkillResult.Rejected(SkillFailure.UNSUPPORTED_REQUEST)
        val task = intent.arguments as ValidatedCreateTask
        return when (writer.create(task, TaskExecutionOwner(context.actionId, context.executionId, context.actionVersion))) {
            OwnedTaskWriteResult.CREATED -> SkillResult.Completed(task.taskId)
            OwnedTaskWriteResult.DEDUP -> SkillResult.Completed(task.taskId, true)
            OwnedTaskWriteResult.NOT_OWNED -> SkillResult.Rejected(SkillFailure.EXECUTION_NOT_OWNED)
            OwnedTaskWriteResult.PAYLOAD_MISMATCH -> SkillResult.Rejected(SkillFailure.PAYLOAD_MISMATCH)
            OwnedTaskWriteResult.TASK_ID_CONFLICT -> SkillResult.Rejected(SkillFailure.STATE_CONFLICT)
            OwnedTaskWriteResult.KEY_UNAVAILABLE, OwnedTaskWriteResult.UNREADABLE -> SkillResult.Rejected(SkillFailure.STORAGE_UNAVAILABLE)
        }
    }
}
