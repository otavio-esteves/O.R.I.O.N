package network.orion.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import network.orion.core.health.OrionHealthAggregator
import network.orion.core.health.OrionHealthReport
import network.orion.core.health.OrionHealthStatus
import network.orion.core.health.OrionSubsystem
import network.orion.core.time.WallClock

/** Slow optional observations may update only their own fields, never republish a stale Core snapshot. */
internal fun MutableStateFlow<OrionHealthReport>.publishSubsystemObservations(
    clock: WallClock, observations: Map<OrionSubsystem, OrionHealthStatus>,
) {
    val selected = observations.toMap()
    update { latest ->
        OrionHealthAggregator.aggregate(latest.subsystems + selected, clock.wallNow(), latest.lastRecovery)
    }
}

/**
 * Ephemeral ownership of one optional subsystem's observation source. Replacing
 * the source and publishing its bounded local health update share this monitor:
 * a callback already in progress finishes before replacement returns, and a late
 * callback cannot publish after a newer recovery has invalidated its source.
 * This fence grants no execution authority and never encloses Binder/storage work.
 */
internal class OrionHealthObservationOwner(
    private val health: MutableStateFlow<OrionHealthReport>,
    private val clock: WallClock,
) {
    class Source internal constructor()
    private var current: Source? = null

    @Synchronized fun replaceSource(): Source = Source().also { current = it }

    @Synchronized fun publish(source: Source, observations: Map<OrionSubsystem, OrionHealthStatus>): Boolean {
        if (source !== current) return false
        health.publishSubsystemObservations(clock, observations)
        return true
    }
}
