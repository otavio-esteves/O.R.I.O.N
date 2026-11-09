package network.orion.app

import network.orion.app.scheduling.OrionReconciliationResult
import network.orion.data.database.RetentionResult

internal data class OrionMaintenanceOutcome(val needsContinuation: Boolean, val healthy: Boolean)

/** Internal progress and subsystem health are independent: review data never creates a retry loop. */
internal fun maintenanceOutcome(pruned: RetentionResult, retentionBatchSize: Int,
    expiredProposals: Int, expirationBatchSize: Int, ingressNeedsContinuation: Boolean,
    registrations: OrionReconciliationResult, deliveries: OrionReconciliationResult,
    eventsHealthy: Boolean, hasDueEvents: Boolean): OrionMaintenanceOutcome {
    require(expirationBatchSize in 1..100 && expiredProposals in 0..expirationBatchSize)
    val continuation = pruned.needsContinuation(retentionBatchSize) || expiredProposals == expirationBatchSize ||
        ingressNeedsContinuation || !registrations.complete || !deliveries.complete || hasDueEvents
    return OrionMaintenanceOutcome(continuation,
        !continuation && registrations.healthy && deliveries.healthy && eventsHealthy)
}
