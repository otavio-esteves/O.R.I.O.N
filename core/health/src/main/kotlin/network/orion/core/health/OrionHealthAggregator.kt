package network.orion.core.health

import java.time.Instant
import java.util.Collections

/** Includes configuration separately so a successful database open cannot mask config failure. */
enum class OrionSubsystem {
    DATABASE, CONFIGURATION, SCHEDULER, REMINDERS, ACTION_OUTBOX, EVENT_OUTBOX,
    VOICE, CORTEX, PERMISSIONS, NOTIFICATIONS, RESOURCES,
}

class OrionHealthReport internal constructor(
    val core: OrionHealth,
    subsystems: Map<OrionSubsystem, OrionHealthStatus>,
    val lastRecovery: Instant?,
) {
    val subsystems: Map<OrionSubsystem, OrionHealthStatus> = Collections.unmodifiableMap(subsystems.toMap())
}

/** F1 critical state is storage/config; absent optional engines are explicitly UNAVAILABLE. */
object OrionHealthAggregator {
    fun aggregate(
        subsystems: Map<OrionSubsystem, OrionHealthStatus>,
        observedAt: Instant,
        lastRecovery: Instant? = null,
    ): OrionHealthReport {
        require(subsystems.keys == OrionSubsystem.entries.toSet()) { "Every subsystem needs explicit health evidence." }
        val states = subsystems.toMap()
        val critical = listOf(states.getValue(OrionSubsystem.DATABASE), states.getValue(OrionSubsystem.CONFIGURATION))
        val readiness = when {
            critical.any { it == OrionHealthStatus.FAILED || it == OrionHealthStatus.DEGRADED } -> CoreReadiness.BLOCKED
            critical.any { it == OrionHealthStatus.RECOVERING } -> CoreReadiness.RECOVERING
            critical.any { it == OrionHealthStatus.UNAVAILABLE } -> CoreReadiness.STARTING
            states.values.any { it == OrionHealthStatus.FAILED || it == OrionHealthStatus.DEGRADED ||
                it == OrionHealthStatus.RECOVERING } -> CoreReadiness.DEGRADED_SAFE
            else -> CoreReadiness.READY
        }
        val status = when (readiness) {
            CoreReadiness.STARTING -> OrionHealthStatus.UNAVAILABLE
            CoreReadiness.RECOVERING -> OrionHealthStatus.RECOVERING
            CoreReadiness.BLOCKED -> OrionHealthStatus.FAILED
            CoreReadiness.DEGRADED_SAFE -> OrionHealthStatus.DEGRADED
            CoreReadiness.READY -> OrionHealthStatus.HEALTHY
        }
        return OrionHealthReport(OrionHealth(readiness, status, observedAt), states, lastRecovery)
    }
}
