package network.orion.skills.timers

import network.orion.core.commands.timers.TimerStatusOperation
import network.orion.core.commands.timers.ValidatedTimerStatusChange
import network.orion.core.model.OrionIntent
import network.orion.core.skills.metadata.timers.OrionTimerStatusDescriptors
import network.orion.data.timers.*
import network.orion.skills.api.*

/** One static descriptor per instance/operation. No suspension, Android side effects or authority from intent metadata. */
class OrionTimerStatusSkill(private val operation: TimerStatusOperation, private val writer: OrionOwnedTimerStatusWriter) : OrionSkill {
    override val descriptor get() = when (operation) {
        TimerStatusOperation.CANCEL -> OrionTimerStatusDescriptors.cancel
    }
    override fun canHandle(intent: OrionIntent<*>) = intent.intentType == descriptor.name &&
        (intent.arguments as? ValidatedTimerStatusChange)?.operation == operation
    override suspend fun execute(intent: OrionIntent<*>, context: SkillContext) = executeInTransaction(intent, context)
    fun executeInTransaction(intent: OrionIntent<*>, context: SkillContext): SkillResult {
        if (!canHandle(intent)) return SkillResult.Rejected(SkillFailure.UNSUPPORTED_REQUEST)
        val change = intent.arguments as ValidatedTimerStatusChange
        return when (writer.change(change, TimerExecutionOwner(context.actionId, context.executionId, context.actionVersion))) {
            OwnedTimerStatusResult.APPLIED -> SkillResult.Completed(change.timerId)
            OwnedTimerStatusResult.DEDUP -> SkillResult.Completed(change.timerId, true)
            OwnedTimerStatusResult.NOT_OWNED -> SkillResult.Rejected(SkillFailure.EXECUTION_NOT_OWNED)
            OwnedTimerStatusResult.PAYLOAD_MISMATCH -> SkillResult.Rejected(SkillFailure.PAYLOAD_MISMATCH)
            OwnedTimerStatusResult.CONFLICT -> SkillResult.Rejected(SkillFailure.STATE_CONFLICT)
            OwnedTimerStatusResult.KEY_UNAVAILABLE, OwnedTimerStatusResult.UNREADABLE -> SkillResult.Rejected(SkillFailure.STORAGE_UNAVAILABLE)
        }
    }
}
