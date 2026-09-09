package network.orion.core.health

/**
 * Observable Core readiness during bootstrap and recovery.
 *
 * The Startup Barrier remains closed while [STARTING], [RECOVERING] or [BLOCKED].
 * Only [READY] and [DEGRADED_SAFE] are eligible for barrier release (Architecture
 * V3.2 §§48, 124). Eligibility never authorizes or executes an action and does not
 * bypass recovery, policy, capability or permission checks.
 */
enum class CoreReadiness {
    STARTING,
    RECOVERING,
    READY,
    /** Reduced functionality with the central invariants still guaranteed. */
    DEGRADED_SAFE,
    /** Central invariants are not guaranteed; mutable actions remain blocked. */
    BLOCKED,
    ;

    /**
     * Declarative eligibility for barrier release, not an operational barrier state
     * or proof that recovery has run. The future coordinator owns actual release.
     */
    val isStartupBarrierOpen: Boolean
        get() = when (this) {
            STARTING, RECOVERING, BLOCKED -> false
            READY, DEGRADED_SAFE -> true
        }
}
