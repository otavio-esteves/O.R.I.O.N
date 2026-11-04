package network.orion.core.skills.metadata.tasks

import network.orion.core.model.AuthorizationLevel
import network.orion.core.model.Capability
import network.orion.core.model.ConfirmationPolicy
import network.orion.core.model.IdempotencyMode
import network.orion.core.model.RecoveryStrategy
import network.orion.core.model.SideEffectClass
import network.orion.core.skills.metadata.SkillDescriptor

/** Static task.create schema-v1 metadata. This declaration activates no capability or Skill. */
object OrionCreateTaskDescriptor {
    val WRITE_CAPABILITY = Capability("orion_tasks_write")

    val value = SkillDescriptor(
        name = "task.create",
        authorizationLevel = AuthorizationLevel.SAFE_WRITE,
        // No replay-safe task creation adapter exists yet; metadata must not promise one.
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
