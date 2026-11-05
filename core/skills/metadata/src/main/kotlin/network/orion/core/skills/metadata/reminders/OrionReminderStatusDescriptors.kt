package network.orion.core.skills.metadata.reminders

import network.orion.core.model.*
import network.orion.core.skills.metadata.SkillDescriptor

/** Closed static allowlist. Producer content never supplies authorization or recovery metadata. */
object OrionReminderStatusDescriptors {
    private fun descriptor(name: String) = SkillDescriptor(name, AuthorizationLevel.SAFE_WRITE,
        RecoveryStrategy.MANUAL_REVIEW, SideEffectClass.LOCAL_MUTATION, IdempotencyMode.KEYED,
        setOf(OrionCreateReminderDescriptor.WRITE_CAPABILITY), emptySet(), true,
        ConfirmationPolicy.ALWAYS, false, 1)
    val snooze = descriptor("reminder.snooze")
    val cancel = descriptor("reminder.cancel")
    fun forName(name: String): SkillDescriptor? = when (name) {
        "reminder.snooze" -> snooze
        "reminder.cancel" -> cancel
        else -> null
    }
}
