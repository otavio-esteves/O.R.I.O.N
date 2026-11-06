package network.orion.core.skills.metadata.memories

import network.orion.core.model.AuthorizationLevel
import network.orion.core.model.Capability
import network.orion.core.model.ConfirmationPolicy
import network.orion.core.model.IdempotencyMode
import network.orion.core.model.RecoveryStrategy
import network.orion.core.model.SideEffectClass
import network.orion.core.skills.metadata.SkillDescriptor

/** Static memory.create schema-v1 metadata. This declaration activates no capability or Skill. */
object OrionCreateMemoryDescriptor {
    val WRITE_CAPABILITY = Capability("orion_memories_write")

    val value = SkillDescriptor(
        name = "memory.create",
        authorizationLevel = AuthorizationLevel.SAFE_WRITE,
        // Generic replay remains unavailable until operational recovery is qualified.
        recoveryStrategy = RecoveryStrategy.MANUAL_REVIEW,
        sideEffectClass = SideEffectClass.LOCAL_MUTATION,
        idempotencyMode = IdempotencyMode.KEYED,
        requiredCapabilities = setOf(WRITE_CAPABILITY),
        requiredPermissions = emptySet(),
        requiresUserPresence = true,
        confirmationPolicy = ConfirmationPolicy.POLICY_DEPENDENT,
        supportsDryRun = false,
        argumentSchemaVersion = 1,
    )
}
