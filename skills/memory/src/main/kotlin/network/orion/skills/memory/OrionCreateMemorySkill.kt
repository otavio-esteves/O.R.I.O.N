package network.orion.skills.memory

import network.orion.core.commands.memories.ValidatedCreateMemory
import network.orion.core.model.OrionIntent
import network.orion.core.skills.metadata.memories.OrionCreateMemoryDescriptor
import network.orion.data.memory.*
import network.orion.skills.api.*

/** No Android side effects or suspension. MAIN owns the verified repository and execution transaction. */
class OrionCreateMemorySkill(private val writer: OrionOwnedMemoryWriter) : OrionSkill {
    override val descriptor get() = OrionCreateMemoryDescriptor.value
    override fun canHandle(intent: OrionIntent<*>) = intent.intentType == descriptor.name && intent.arguments is ValidatedCreateMemory
    override suspend fun execute(intent: OrionIntent<*>, context: SkillContext) = executeInTransaction(intent, context)

    /** Same implementation as the normative suspend port, for the bounded blocking local transaction. */
    fun executeInTransaction(intent: OrionIntent<*>, context: SkillContext): SkillResult {
        if (!canHandle(intent)) return SkillResult.Rejected(SkillFailure.UNSUPPORTED_REQUEST)
        val memory = intent.arguments as ValidatedCreateMemory
        return when (writer.create(memory, MemoryExecutionOwner(context.actionId, context.executionId, context.actionVersion))) {
            OwnedMemoryWriteResult.CREATED -> SkillResult.Completed(memory.memoryId)
            OwnedMemoryWriteResult.DEDUP -> SkillResult.Completed(memory.memoryId, true)
            OwnedMemoryWriteResult.NOT_OWNED -> SkillResult.Rejected(SkillFailure.EXECUTION_NOT_OWNED)
            OwnedMemoryWriteResult.PAYLOAD_MISMATCH -> SkillResult.Rejected(SkillFailure.PAYLOAD_MISMATCH)
            OwnedMemoryWriteResult.MEMORY_ID_CONFLICT -> SkillResult.Rejected(SkillFailure.STATE_CONFLICT)
            OwnedMemoryWriteResult.KEY_UNAVAILABLE, OwnedMemoryWriteResult.UNREADABLE -> SkillResult.Rejected(SkillFailure.STORAGE_UNAVAILABLE)
        }
    }
}
