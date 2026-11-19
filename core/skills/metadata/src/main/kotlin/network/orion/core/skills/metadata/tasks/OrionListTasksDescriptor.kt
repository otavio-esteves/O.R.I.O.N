package network.orion.core.skills.metadata.tasks

import network.orion.core.model.*
import network.orion.core.skills.metadata.SkillDescriptor

object OrionListTasksDescriptor {
    val value = SkillDescriptor("task.list", AuthorizationLevel.SAFE_READ, RecoveryStrategy.RETRY_SAFE,
        SideEffectClass.READ_ONLY, IdempotencyMode.NATURAL, emptySet(), emptySet(), false,
        ConfirmationPolicy.POLICY_DEPENDENT, false, 1)
}
