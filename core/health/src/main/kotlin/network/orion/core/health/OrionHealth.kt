package network.orion.core.health

import java.time.Instant

/**
 * Health classification published by the Core without exposing diagnostic payloads.
 */
enum class OrionHealthStatus {
    UNAVAILABLE,
    HEALTHY,
    DEGRADED,
}

/**
 * A point-in-time Core health snapshot.
 *
 * The producer supplies [observedAt] through its time boundary. This contract neither
 * persists the snapshot nor reads a platform clock. Detailed diagnostics, recovery
 * orchestration and subsystem-specific health are intentionally outside this F1
 * foundation contract.
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
        CoreReadiness.STARTING,
        CoreReadiness.RECOVERING,
        -> this == OrionHealthStatus.UNAVAILABLE

        CoreReadiness.READY -> this == OrionHealthStatus.HEALTHY
        CoreReadiness.DEGRADED -> this == OrionHealthStatus.DEGRADED
    }
}
