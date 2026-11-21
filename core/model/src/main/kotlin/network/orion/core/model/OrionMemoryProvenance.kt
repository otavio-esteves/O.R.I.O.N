package network.orion.core.model

import java.util.UUID

enum class OrionMemoryOrigin { USER_DECLARED, SYSTEM_OBSERVED, SYSTEM_DERIVED, LLM_INFERRED, IMPORTED, EXTERNAL_SOURCE }

/** Evidence projected from MAIN storage. Inference provenance is independent of human confirmation. */
data class OrionMemoryProvenance(
    val origin: OrionMemoryOrigin,
    val confidence: Double,
    val userConfirmed: Boolean,
    val sensitivity: Sensitivity,
    val originEventId: UUID? = null,
) {
    init { require(confidence.isFinite() && confidence in 0.0..1.0) }

    val inferred: Boolean get() = origin == OrionMemoryOrigin.LLM_INFERRED

    override fun toString(): String = "OrionMemoryProvenance(<redacted>)"
}
