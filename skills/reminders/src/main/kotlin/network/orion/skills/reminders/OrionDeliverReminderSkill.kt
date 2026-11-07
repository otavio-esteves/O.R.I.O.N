package network.orion.skills.reminders

import java.time.Instant
import java.util.UUID
import network.orion.core.model.OrionIntent
import network.orion.core.skills.metadata.reminders.OrionDeliverReminderDescriptor
import network.orion.skills.api.*

/** Validated references identify the exact one-shot occurrence; they are never consent or execution authority. */
class ValidatedReminderDelivery internal constructor(val reminderId: UUID, val generation: Long, val scheduledAt: Instant) {
    override fun toString() = "ValidatedReminderDelivery(<redacted>)"
}

object OrionReminderDeliveryPreparer {
    fun prepare(reminderId: UUID, generation: Long, scheduledAt: Instant): ValidatedReminderDelivery {
        require(generation in 1 until Long.MAX_VALUE)
        scheduledAt.toEpochMilli() // The durable schema represents wall instants as epoch milliseconds.
        return ValidatedReminderDelivery(reminderId, generation, scheduledAt)
    }
}

enum class ReminderDeliveryEffectResult { POSTED, NOT_INVOKED, NOT_OWNED, UNKNOWN }

/** MAIN implementation verifies the persisted RUNNING owner, exact consent and live capability before invoking Android. */
fun interface OrionOwnedReminderDelivery {
    fun deliver(reminder: ValidatedReminderDelivery, owner: SkillContext): ReminderDeliveryEffectResult
}

class OrionDeliverReminderSkill(private val delivery: OrionOwnedReminderDelivery) : OrionSkill {
    override val descriptor get() = OrionDeliverReminderDescriptor.value
    override fun canHandle(intent: OrionIntent<*>) = intent.intentType == descriptor.name && intent.arguments is ValidatedReminderDelivery
    fun executeOwned(intent: OrionIntent<*>, context: SkillContext): ReminderDeliveryEffectResult =
        if (!canHandle(intent)) ReminderDeliveryEffectResult.NOT_OWNED
        else delivery.deliver(intent.arguments as ValidatedReminderDelivery, context)
    override suspend fun execute(intent: OrionIntent<*>, context: SkillContext): SkillResult = when (executeOwned(intent, context)) {
        ReminderDeliveryEffectResult.POSTED -> SkillResult.Completed((intent.arguments as ValidatedReminderDelivery).reminderId)
        ReminderDeliveryEffectResult.NOT_OWNED -> SkillResult.Rejected(SkillFailure.EXECUTION_NOT_OWNED)
        ReminderDeliveryEffectResult.NOT_INVOKED, ReminderDeliveryEffectResult.UNKNOWN -> SkillResult.Rejected(SkillFailure.STORAGE_UNAVAILABLE)
    }
}
