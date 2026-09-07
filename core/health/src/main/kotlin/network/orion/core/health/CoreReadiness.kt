package network.orion.core.health

/**
 * Observable Core readiness during bootstrap and recovery.
 *
 * The Startup Barrier remains closed while [STARTING] or [RECOVERING]. Opening the
 * barrier only makes durable ingress eligible for the future IngressCoordinator; it
 * never authorizes or executes a side effect by itself.
 */
enum class CoreReadiness {
    STARTING,
    RECOVERING,
    READY,
    DEGRADED,
    ;

    /** Whether recovery has published a terminal Core state and may release its barrier. */
    val isStartupBarrierOpen: Boolean
        get() = this == READY || this == DEGRADED
}
