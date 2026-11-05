package network.orion.core.skills.metadata.memories

import network.orion.core.model.*
import network.orion.core.skills.metadata.SkillDescriptor

/** Forgetting user memory is destructive and always requires exact confirmation. */
object OrionMemoryStatusDescriptors {
    val delete = SkillDescriptor("memory.delete", AuthorizationLevel.DESTRUCTIVE,
        RecoveryStrategy.MANUAL_REVIEW, SideEffectClass.DESTRUCTIVE, IdempotencyMode.KEYED,
        setOf(OrionCreateMemoryDescriptor.WRITE_CAPABILITY), emptySet(), true,
        ConfirmationPolicy.ALWAYS, false, 1)
    fun forName(name: String): SkillDescriptor? = delete.takeIf { name == it.name }
}
