package network.orion.app

import network.orion.core.time.WallClock
import network.orion.data.database.OrionDatabase
import network.orion.data.database.RetentionResult
import network.orion.data.repository.OrionRetentionMaintenance
import network.orion.data.repository.OrionRetentionPolicy

/** Technical observation only. Long totals allow several bounded passes before a snapshot. */
internal data class OrionRetentionTotals(
    val events: Long = 0, val ingress: Long = 0, val actions: Long = 0,
    val audit: Long = 0, val technical: Long = 0,
) {
    operator fun plus(batch: RetentionResult) = OrionRetentionTotals(events + batch.events,
        ingress + batch.ingress, actions + batch.actions, audit + batch.audit, technical + batch.technical)
}

/**
 * MAIN Core serializes calls with its maintenance mutex. Recovery retains its
 * pre-drain cleanup opportunity using the same policy as later maintenance.
 * Unreported counts are ephemeral diagnostics, never recovery/execution authority.
 */
internal class OrionCoreRetentionMaintenance(db: OrionDatabase, clock: WallClock,
    val policy: OrionRetentionPolicy = OrionRetentionPolicy()) {
    private val maintenance = OrionRetentionMaintenance(db.retention(), clock, policy)
    private var unreported = OrionRetentionTotals()

    fun runBatch(): RetentionResult = maintenance.runBatch().also { unreported += it }

    /** Best-effort snapshot accounting; a process death never replays pruning to reconstruct a log. */
    fun takeReport(): OrionRetentionTotals = unreported.also { unreported = OrionRetentionTotals() }
}
