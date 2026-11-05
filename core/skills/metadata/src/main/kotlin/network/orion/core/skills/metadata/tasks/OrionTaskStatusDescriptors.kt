package network.orion.core.skills.metadata.tasks

import network.orion.core.model.*
import network.orion.core.skills.metadata.SkillDescriptor

/** Closed static allowlist. Producer content never supplies authorization or recovery metadata. */
object OrionTaskStatusDescriptors {
    private fun descriptor(name: String) = SkillDescriptor(name, AuthorizationLevel.SAFE_WRITE,
        RecoveryStrategy.MANUAL_REVIEW, SideEffectClass.LOCAL_MUTATION, IdempotencyMode.KEYED,
        setOf(OrionCreateTaskDescriptor.WRITE_CAPABILITY), emptySet(), true,
        ConfirmationPolicy.ALWAYS, false, 1)
    val complete = descriptor("task.complete")
    val cancel = descriptor("task.cancel")
    fun forName(name: String): SkillDescriptor? = when (name) {
        "task.complete" -> complete
        "task.cancel" -> cancel
        else -> null
    }
}
