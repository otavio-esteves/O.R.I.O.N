package network.orion.core.sentinel

import network.orion.core.health.OrionHealthStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

enum class OrionThermalState {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN,
}

data class OrionQuietHours(val start: LocalTime, val end: LocalTime) {
    init {
        require(start != end) { "Use an absent schedule to disable quiet hours." }
    }

    fun contains(time: LocalTime): Boolean = if (start < end) {
        time >= start && time < end
    } else {
        time >= start || time < end
    }
}

/** A local point-in-time observation with no task content, location, or external data. */
data class OrionContextSnapshot(
    val observedAt: Instant,
    val zoneId: ZoneId,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val thermalState: OrionThermalState?,
    val screenInteractive: Boolean?,
    val quietHours: OrionQuietHours?,
    val tasksDueSoon: Int,
    val coreHealth: OrionHealthStatus,
    val lastInteractionAt: Instant? = null,
    val upcomingReminders: Int = 0,
) {
    init {
        require(batteryPercent == null || batteryPercent in 0..100)
        require(tasksDueSoon >= 0 && upcomingReminders >= 0)
    }

    val localTime: LocalTime get() = observedAt.atZone(zoneId).toLocalTime()
    val dayOfWeek: DayOfWeek get() = observedAt.atZone(zoneId).dayOfWeek
    val isQuietHours: Boolean get() = quietHours?.contains(localTime) == true
}
