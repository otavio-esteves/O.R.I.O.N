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
