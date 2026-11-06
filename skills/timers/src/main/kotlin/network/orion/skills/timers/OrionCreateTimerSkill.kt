package network.orion.skills.timers

import network.orion.core.commands.timers.ValidatedCreateTimer
import network.orion.core.model.OrionIntent
import network.orion.core.skills.metadata.timers.OrionCreateTimerDescriptor
import network.orion.data.timers.*
import network.orion.skills.api.*

/** No Android side effects or suspension. MAIN owns the verified repository and execution transaction. */
class OrionCreateTimerSkill(private val writer: OrionOwnedTimerWriter) : OrionSkill {
    override val descriptor get() = OrionCreateTimerDescriptor.value
    override fun canHandle(intent: OrionIntent<*>) = intent.intentType == descriptor.name && intent.arguments is ValidatedCreateTimer
    override suspend fun execute(intent: OrionIntent<*>, context: SkillContext) = executeInTransaction(intent, context)

    /** Same implementation as the normative suspend port, for the bounded blocking local transaction. */
    fun executeInTransaction(intent: OrionIntent<*>, context: SkillContext): SkillResult {
        if (!canHandle(intent)) return SkillResult.Rejected(SkillFailure.UNSUPPORTED_REQUEST)
        val timer = intent.arguments as ValidatedCreateTimer
        return when (writer.create(timer, TimerExecutionOwner(context.actionId, context.executionId, context.actionVersion))) {
            OwnedTimerWriteResult.CREATED -> SkillResult.Completed(timer.timerId)
            OwnedTimerWriteResult.DEDUP -> SkillResult.Completed(timer.timerId, true)
            OwnedTimerWriteResult.NOT_OWNED -> SkillResult.Rejected(SkillFailure.EXECUTION_NOT_OWNED)
            OwnedTimerWriteResult.PAYLOAD_MISMATCH -> SkillResult.Rejected(SkillFailure.PAYLOAD_MISMATCH)
            OwnedTimerWriteResult.TIMER_ID_CONFLICT -> SkillResult.Rejected(SkillFailure.STATE_CONFLICT)
            OwnedTimerWriteResult.KEY_UNAVAILABLE, OwnedTimerWriteResult.UNREADABLE -> SkillResult.Rejected(SkillFailure.STORAGE_UNAVAILABLE)
        }
    }
}
