package network.orion.skills.reminders

import network.orion.core.commands.reminders.ValidatedCreateReminder
import network.orion.core.model.OrionIntent
import network.orion.core.skills.metadata.reminders.OrionCreateReminderDescriptor
import network.orion.data.reminders.*
import network.orion.skills.api.*

/** No Android side effects or suspension. MAIN owns the verified repository and execution transaction. */
class OrionCreateReminderSkill(private val writer: OrionOwnedReminderWriter) : OrionSkill {
    override val descriptor get() = OrionCreateReminderDescriptor.value
    override fun canHandle(intent: OrionIntent<*>) = intent.intentType == descriptor.name && intent.arguments is ValidatedCreateReminder
    override suspend fun execute(intent: OrionIntent<*>, context: SkillContext) = executeInTransaction(intent, context)

    /** Same implementation as the normative suspend port, for the bounded blocking local transaction. */
    fun executeInTransaction(intent: OrionIntent<*>, context: SkillContext): SkillResult {
        if (!canHandle(intent)) return SkillResult.Rejected(SkillFailure.UNSUPPORTED_REQUEST)
        val reminder = intent.arguments as ValidatedCreateReminder
        return when (writer.create(reminder, ReminderExecutionOwner(context.actionId, context.executionId, context.actionVersion))) {
            OwnedReminderWriteResult.CREATED -> SkillResult.Completed(reminder.reminderId)
            OwnedReminderWriteResult.DEDUP -> SkillResult.Completed(reminder.reminderId, true)
            OwnedReminderWriteResult.NOT_OWNED -> SkillResult.Rejected(SkillFailure.EXECUTION_NOT_OWNED)
            OwnedReminderWriteResult.PAYLOAD_MISMATCH -> SkillResult.Rejected(SkillFailure.PAYLOAD_MISMATCH)
            OwnedReminderWriteResult.REMINDER_ID_CONFLICT -> SkillResult.Rejected(SkillFailure.STATE_CONFLICT)
            OwnedReminderWriteResult.KEY_UNAVAILABLE, OwnedReminderWriteResult.UNREADABLE -> SkillResult.Rejected(SkillFailure.STORAGE_UNAVAILABLE)
        }
    }
}
