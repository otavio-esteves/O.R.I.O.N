package network.orion.core.skills.metadata.reminders

import network.orion.core.model.AuthorizationLevel
import network.orion.core.model.Capability
import network.orion.core.model.ConfirmationPolicy
import network.orion.core.model.IdempotencyMode
import network.orion.core.model.RecoveryStrategy
import network.orion.core.model.SideEffectClass
import network.orion.core.skills.metadata.SkillDescriptor

object OrionAmbientReminderDescriptor {
    const val NAME = "reminder.ambient.speak"
    const val POLICY_VERSION = "orion_ambient_reminder_v1"
    const val RECORD_PERMISSION = "android.permission.RECORD_AUDIO"
    val CAPABILITY = Capability("orion_ambient_reminder_speech")

    val value = SkillDescriptor(
        name = NAME,
        authorizationLevel = AuthorizationLevel.SAFE_WRITE,
        recoveryStrategy = RecoveryStrategy.NO_RETRY,
        sideEffectClass = SideEffectClass.EXTERNAL_IRREVERSIBLE,
        idempotencyMode = IdempotencyMode.KEYED,
        requiredCapabilities = setOf(CAPABILITY),
        requiredPermissions = setOf(RECORD_PERMISSION),
        requiresUserPresence = false,
        confirmationPolicy = ConfirmationPolicy.POLICY_DEPENDENT,
        supportsDryRun = false,
        argumentSchemaVersion = 1,
    )
}
