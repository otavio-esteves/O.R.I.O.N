package network.orion.core.skills.metadata.notifications

import network.orion.core.model.AuthorizationLevel
import network.orion.core.model.Capability
import network.orion.core.model.ConfirmationPolicy
import network.orion.core.model.IdempotencyMode
import network.orion.core.model.RecoveryStrategy
import network.orion.core.model.SideEffectClass
import network.orion.core.skills.metadata.SkillDescriptor

object OrionProactiveNotificationDescriptor {
    const val POLICY_VERSION = "orion_proactive_notification_v1"
    const val SOURCE = "MAIN_SENTINEL"
    const val TITLE = "O.R.I.O.N."
    const val BODY = "Você tem uma sugestão de tarefa."

    val capability = Capability("orion_proactive_notification")
    val value = SkillDescriptor(
        name = "sentinel.notify",
        authorizationLevel = AuthorizationLevel.SAFE_WRITE,
        recoveryStrategy = RecoveryStrategy.NO_RETRY,
        sideEffectClass = SideEffectClass.EXTERNAL_REVERSIBLE,
        idempotencyMode = IdempotencyMode.KEYED,
        requiredCapabilities = setOf(capability),
        requiredPermissions = setOf(OrionPostNotificationDescriptor.POST_PERMISSION),
        requiresUserPresence = false,
        confirmationPolicy = ConfirmationPolicy.POLICY_DEPENDENT,
        supportsDryRun = false,
        argumentSchemaVersion = 1,
    )
}
