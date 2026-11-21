package network.orion.core.skills.metadata.memories

import network.orion.core.model.*
import network.orion.core.skills.metadata.SkillDescriptor

/** Forgetting user memory is destructive and always requires exact confirmation. */
object OrionMemoryStatusDescriptors {
    val delete = SkillDescriptor("memory.delete", AuthorizationLevel.DESTRUCTIVE,
        RecoveryStrategy.MANUAL_REVIEW, SideEffectClass.DESTRUCTIVE, IdempotencyMode.KEYED,
        setOf(OrionCreateMemoryDescriptor.WRITE_CAPABILITY), emptySet(), true,
        ConfirmationPolicy.ALWAYS, false, 1)
    val correct = revision("memory.correct")
    val confirmClaim = revision("memory.claim.confirm")
    val rejectClaim = revision("memory.claim.reject")

    fun forName(name: String): SkillDescriptor? = when (name) {
        delete.name -> delete
        correct.name -> correct
        confirmClaim.name -> confirmClaim
        rejectClaim.name -> rejectClaim
        else -> null
    }

    private fun revision(name: String) = SkillDescriptor(name, AuthorizationLevel.SAFE_WRITE,
        RecoveryStrategy.MANUAL_REVIEW, SideEffectClass.LOCAL_MUTATION, IdempotencyMode.KEYED,
        setOf(OrionCreateMemoryDescriptor.WRITE_CAPABILITY), emptySet(), true,
        ConfirmationPolicy.ALWAYS, false, 1)
}
