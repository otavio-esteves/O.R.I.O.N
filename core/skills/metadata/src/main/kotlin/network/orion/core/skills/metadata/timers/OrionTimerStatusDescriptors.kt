package network.orion.core.skills.metadata.timers

import network.orion.core.model.*
import network.orion.core.skills.metadata.SkillDescriptor

/** Closed static allowlist. Producer content never supplies authorization or recovery metadata. */
object OrionTimerStatusDescriptors {
    private fun descriptor(name: String) = SkillDescriptor(name, AuthorizationLevel.SAFE_WRITE,
        RecoveryStrategy.MANUAL_REVIEW, SideEffectClass.LOCAL_MUTATION, IdempotencyMode.KEYED,
        setOf(OrionCreateTimerDescriptor.WRITE_CAPABILITY), emptySet(), true,
        ConfirmationPolicy.ALWAYS, false, 1)
    val cancel = descriptor("timer.cancel")
    fun forName(name: String): SkillDescriptor? = when (name) {
        "timer.cancel" -> cancel
        else -> null
    }
}
