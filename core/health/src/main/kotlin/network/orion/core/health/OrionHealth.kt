package network.orion.core.health

import java.time.Instant

/**
 * Health classification published by the Core without exposing diagnostic payloads.
 */
enum class OrionHealthStatus {
    UNAVAILABLE,
    HEALTHY,
    DEGRADED,
    RECOVERING,
    FAILED,
}

/**
 * A point-in-time Core health snapshot.
 *
 * The producer supplies [observedAt] through its time boundary. This contract neither
 * persists the snapshot nor reads a platform clock. Detailed diagnostics, recovery
 * orchestration and subsystem-specific health are intentionally outside this F1
 * foundation contract.
 *
 * STARTING is UNAVAILABLE, RECOVERING is RECOVERING, READY is HEALTHY,
 * DEGRADED_SAFE is DEGRADED and BLOCKED is FAILED. FAILED describes central
 * invariants not being guaranteed; it does not imply permanent failure or rule
 * out safe reads. Health classification never grants action authority.
 */
data class OrionHealth(
    val readiness: CoreReadiness,
    val status: OrionHealthStatus,
    val observedAt: Instant,
) {
    init {
        require(status.isConsistentWith(readiness)) { "Health status must match Core readiness." }
    }

    private fun OrionHealthStatus.isConsistentWith(readiness: CoreReadiness): Boolean = when (readiness) {
        CoreReadiness.STARTING -> this == OrionHealthStatus.UNAVAILABLE
        CoreReadiness.RECOVERING -> this == OrionHealthStatus.RECOVERING
        CoreReadiness.READY -> this == OrionHealthStatus.HEALTHY
        CoreReadiness.DEGRADED_SAFE -> this == OrionHealthStatus.DEGRADED
        CoreReadiness.BLOCKED -> this == OrionHealthStatus.FAILED
    }
}
