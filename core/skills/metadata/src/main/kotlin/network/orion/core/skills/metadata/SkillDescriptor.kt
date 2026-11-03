package network.orion.core.skills.metadata

import network.orion.core.model.AuthorizationLevel
import network.orion.core.model.Capability
import network.orion.core.model.ConfirmationPolicy
import network.orion.core.model.IdempotencyMode
import network.orion.core.model.RecoveryStrategy
import network.orion.core.model.SideEffectClass

/**
 * Static metadata per Skill/version (Architecture §102), never supplied by a model.
 * Supply immutable sets from trusted code. Metadata is not execution authority;
 * policy and current capabilities/permissions must still be checked at execution.
 */
data class SkillDescriptor(
    val name: String,
    val authorizationLevel: AuthorizationLevel,
    val recoveryStrategy: RecoveryStrategy,
    val sideEffectClass: SideEffectClass,
    val idempotencyMode: IdempotencyMode,
    val requiredCapabilities: Set<Capability>,
    val requiredPermissions: Set<String>,
    val requiresUserPresence: Boolean,
    val confirmationPolicy: ConfirmationPolicy,
    val supportsDryRun: Boolean,
    val argumentSchemaVersion: Int,
) {
    init {
        require(name.isNotBlank()) { "Skill name must not be blank." }
        require(argumentSchemaVersion > 0) { "Argument schema version must be positive." }
        require(requiredPermissions.all { it.isNotBlank() && it == it.trim() }) {
            "Permission identifiers must not be blank or padded."
        }
        require((authorizationLevel != AuthorizationLevel.DESTRUCTIVE &&
            sideEffectClass != SideEffectClass.DESTRUCTIVE) || confirmationPolicy == ConfirmationPolicy.ALWAYS) {
            "Destructive Skills require mandatory confirmation."
        }
    }
}
