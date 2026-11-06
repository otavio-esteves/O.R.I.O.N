package network.orion.skills.memory

import network.orion.core.commands.memories.MemoryStatusOperation
import network.orion.core.commands.memories.ValidatedMemoryStatusChange
import network.orion.core.model.OrionIntent
import network.orion.core.skills.metadata.memories.OrionMemoryStatusDescriptors
import network.orion.data.memory.*
import network.orion.skills.api.*

/** One static descriptor per instance/operation. No suspension, Android side effects or authority from intent metadata. */
class OrionMemoryStatusSkill(private val operation: MemoryStatusOperation, private val writer: OrionOwnedMemoryStatusWriter) : OrionSkill {
    override val descriptor get() = OrionMemoryStatusDescriptors.delete
    override fun canHandle(intent: OrionIntent<*>) = intent.intentType == descriptor.name &&
        (intent.arguments as? ValidatedMemoryStatusChange)?.operation == operation
    override suspend fun execute(intent: OrionIntent<*>, context: SkillContext) = executeInTransaction(intent, context)
    fun executeInTransaction(intent: OrionIntent<*>, context: SkillContext): SkillResult {
        if (!canHandle(intent)) return SkillResult.Rejected(SkillFailure.UNSUPPORTED_REQUEST)
        val change = intent.arguments as ValidatedMemoryStatusChange
        return when (writer.change(change, MemoryExecutionOwner(context.actionId, context.executionId, context.actionVersion))) {
            OwnedMemoryStatusResult.APPLIED -> SkillResult.Completed(change.memoryId)
            OwnedMemoryStatusResult.DEDUP -> SkillResult.Completed(change.memoryId, true)
            OwnedMemoryStatusResult.NOT_OWNED -> SkillResult.Rejected(SkillFailure.EXECUTION_NOT_OWNED)
            OwnedMemoryStatusResult.PAYLOAD_MISMATCH -> SkillResult.Rejected(SkillFailure.PAYLOAD_MISMATCH)
            OwnedMemoryStatusResult.CONFLICT -> SkillResult.Rejected(SkillFailure.STATE_CONFLICT)
            OwnedMemoryStatusResult.KEY_UNAVAILABLE, OwnedMemoryStatusResult.UNREADABLE -> SkillResult.Rejected(SkillFailure.STORAGE_UNAVAILABLE)
        }
    }
}
