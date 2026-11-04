package network.orion.app

import network.orion.core.health.CoreReadiness
import network.orion.core.observability.OrionLogEvent

/** A degraded optional subsystem must not be mislabeled as fully healthy in logs. */
internal fun readyLogEvent(readiness: CoreReadiness): OrionLogEvent = when (readiness) {
    CoreReadiness.READY -> OrionLogEvent.CORE_READY
    CoreReadiness.DEGRADED_SAFE -> OrionLogEvent.CORE_DEGRADED
    else -> error("Core readiness has not completed recovery.")
}

/** A failed optional subsystem may leave the Core safely degraded rather than blocked. */
internal fun failedRecoveryLogEvent(readiness: CoreReadiness): OrionLogEvent = when (readiness) {
    CoreReadiness.DEGRADED_SAFE -> OrionLogEvent.CORE_DEGRADED
    CoreReadiness.BLOCKED -> OrionLogEvent.CORE_BLOCKED
    else -> error("Core readiness does not represent a recovery failure.")
}
