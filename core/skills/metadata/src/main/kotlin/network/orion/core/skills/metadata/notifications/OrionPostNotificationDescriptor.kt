package network.orion.core.skills.metadata.notifications

import network.orion.core.model.*
import network.orion.core.skills.metadata.SkillDescriptor

object OrionPostNotificationDescriptor {
    val WRITE_CAPABILITY=Capability("orion_notifications_post")
    const val POST_PERMISSION="android.permission.POST_NOTIFICATIONS"
    val value=SkillDescriptor("notification.post",AuthorizationLevel.SAFE_WRITE,RecoveryStrategy.MANUAL_REVIEW,
        SideEffectClass.EXTERNAL_REVERSIBLE,IdempotencyMode.KEYED,setOf(WRITE_CAPABILITY),setOf(POST_PERMISSION),
        true,ConfirmationPolicy.ALWAYS,false,1)
}
