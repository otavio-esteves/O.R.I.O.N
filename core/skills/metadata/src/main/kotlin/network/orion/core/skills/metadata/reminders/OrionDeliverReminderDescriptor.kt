package network.orion.core.skills.metadata.reminders

import network.orion.core.model.*
import network.orion.core.skills.metadata.SkillDescriptor

/** A separately confirmed one-shot generic notification; no existing creation consent is reused. */
object OrionDeliverReminderDescriptor {
    const val POLICY_VERSION = "reminder.delivery.v1"
    const val POST_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    val DELIVERY_CAPABILITY = Capability("orion_reminders_deliver")
    val value = SkillDescriptor(
        "reminder.deliver", AuthorizationLevel.SAFE_WRITE, RecoveryStrategy.MANUAL_REVIEW,
        SideEffectClass.EXTERNAL_REVERSIBLE, IdempotencyMode.KEYED, setOf(DELIVERY_CAPABILITY),
        setOf(POST_PERMISSION), false, ConfirmationPolicy.ALWAYS, false, 1,
    )
}
