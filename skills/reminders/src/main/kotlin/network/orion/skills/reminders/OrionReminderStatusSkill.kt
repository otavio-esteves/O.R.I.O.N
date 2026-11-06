package network.orion.skills.reminders

import network.orion.core.commands.reminders.ReminderStatusOperation
import network.orion.core.commands.reminders.ValidatedReminderStatusChange
import network.orion.core.model.OrionIntent
import network.orion.core.skills.metadata.reminders.OrionReminderStatusDescriptors
import network.orion.data.reminders.*
import network.orion.skills.api.*

/** One static descriptor per instance/operation. No suspension, Android side effects or authority from intent metadata. */
class OrionReminderStatusSkill(private val operation: ReminderStatusOperation, private val writer: OrionOwnedReminderStatusWriter) : OrionSkill {
    override val descriptor get() = when (operation) {
        ReminderStatusOperation.SNOOZE -> OrionReminderStatusDescriptors.snooze
        ReminderStatusOperation.CANCEL -> OrionReminderStatusDescriptors.cancel
    }
    override fun canHandle(intent: OrionIntent<*>) = intent.intentType == descriptor.name &&
        (intent.arguments as? ValidatedReminderStatusChange)?.operation == operation
    override suspend fun execute(intent: OrionIntent<*>, context: SkillContext) = executeInTransaction(intent, context)
    fun executeInTransaction(intent: OrionIntent<*>, context: SkillContext): SkillResult {
        if (!canHandle(intent)) return SkillResult.Rejected(SkillFailure.UNSUPPORTED_REQUEST)
        val change = intent.arguments as ValidatedReminderStatusChange
        return when (writer.change(change, ReminderExecutionOwner(context.actionId, context.executionId, context.actionVersion))) {
            OwnedReminderStatusResult.APPLIED -> SkillResult.Completed(change.reminderId)
            OwnedReminderStatusResult.DEDUP -> SkillResult.Completed(change.reminderId, true)
            OwnedReminderStatusResult.NOT_OWNED -> SkillResult.Rejected(SkillFailure.EXECUTION_NOT_OWNED)
            OwnedReminderStatusResult.PAYLOAD_MISMATCH -> SkillResult.Rejected(SkillFailure.PAYLOAD_MISMATCH)
            OwnedReminderStatusResult.CONFLICT -> SkillResult.Rejected(SkillFailure.STATE_CONFLICT)
            OwnedReminderStatusResult.KEY_UNAVAILABLE, OwnedReminderStatusResult.UNREADABLE -> SkillResult.Rejected(SkillFailure.STORAGE_UNAVAILABLE)
        }
    }
}
