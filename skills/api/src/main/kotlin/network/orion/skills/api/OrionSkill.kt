package network.orion.skills.api

import java.util.UUID
import network.orion.core.model.OrionIntent
import network.orion.core.skills.metadata.SkillDescriptor

/** Ownership coordinates only. The repository must verify the actual RUNNING record and lease. */
data class SkillContext(val actionId: UUID, val executionId: UUID, val actionVersion: Long) {
    init { require(actionVersion >= 0 && actionVersion < Long.MAX_VALUE) }
    override fun toString() = "SkillContext(<redacted>)"
}

enum class SkillFailure { UNSUPPORTED_REQUEST, EXECUTION_NOT_OWNED, PAYLOAD_MISMATCH, STATE_CONFLICT, STORAGE_UNAVAILABLE }

sealed interface SkillResult {
    data class Completed(val entityId: UUID, val deduplicated: Boolean = false) : SkillResult {
        override fun toString() = "SkillResult.Completed(<redacted>)"
    }
    data class Rejected(val reason: SkillFailure) : SkillResult
}

/** Architecture §102. A structured intent and a context never grant execution authority. */
interface OrionSkill {
    val descriptor: SkillDescriptor
    fun canHandle(intent: OrionIntent<*>): Boolean
    suspend fun execute(intent: OrionIntent<*>, context: SkillContext): SkillResult
}
