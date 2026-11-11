package network.orion.data.repository

import java.time.Duration
import network.orion.core.time.WallClock
import network.orion.data.database.OrionRetentionDao
import network.orion.data.database.RetentionResult

/** Operational data only. Unresolved review data is preserved; admission caps prevent silent unlimited growth. */
data class OrionRetentionPolicy(
    val deliveredEvents: Duration = Duration.ofDays(7),
    val terminalIngress: Duration = Duration.ofDays(7),
    val terminalActions: Duration = Duration.ofDays(90),
    val audit: Duration = Duration.ofDays(90),
    val technicalEvents: Duration = Duration.ofDays(7),
    val maxTechnicalRows: Int = 10_000,
    val batchSize: Int = 50,
) {
    init {
        require(listOf(deliveredEvents, terminalIngress, terminalActions, audit, technicalEvents).all { !it.isNegative && !it.isZero })
        require(maxTechnicalRows in 100..100_000 && batchSize in 1..100)
    }
}

/** Core invokes bounded batches during recovery/maintenance and owns their diagnostic accounting. */
class OrionRetentionMaintenance(private val retention: OrionRetentionDao, private val clock: WallClock,
    private val policy: OrionRetentionPolicy = OrionRetentionPolicy()) {
    fun runBatch(): RetentionResult {
        val now = clock.wallNow()
        return retention.prune(now.toEpochMilli(), now.minus(policy.deliveredEvents).toEpochMilli(),
            now.minus(policy.terminalIngress).toEpochMilli(), now.minus(policy.terminalActions).toEpochMilli(),
            now.minus(policy.audit).toEpochMilli(), now.minus(policy.technicalEvents).toEpochMilli(),
            policy.maxTechnicalRows, policy.batchSize)
    }
}
