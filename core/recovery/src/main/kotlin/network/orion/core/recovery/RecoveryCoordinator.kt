package network.orion.core.recovery

import network.orion.core.health.CoreReadiness

/**
 * Main-owned recovery port. Implementations preserve Architecture §49 ordering;
 * no unsafe effect may resume before recovery and actual barrier release.
 */
fun interface RecoveryCoordinator {
    suspend fun recover(): CoreReadiness
}
