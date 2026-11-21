package network.orion.core.resources

/**
 * Software policy for optional work. Model admission still requires a qualified plan
 * and a separate decision over fresh inference and process-memory evidence.
 */
enum class OrionResourceProfile {
    NORMAL,
    ECONOMY,
    LOW_POWER,
    CRITICAL,
    THERMAL_LIMIT,
}

data class OrionResourcePolicy internal constructor(
    val profile: OrionResourceProfile,
    val allowsProactivity: Boolean,
) {
    internal fun limitKeepAlive(qualifiedMillis: Long): Long =
        if (profile == OrionResourceProfile.NORMAL) qualifiedMillis else 0
}

/** Thresholds for optional software work. These do not qualify a Cortex profile. */
object OrionResourceProfiles {
    private const val CRITICAL_BATTERY_PERCENT = 5
    private const val LOW_BATTERY_PERCENT = 20
    private const val THERMAL_MODERATE = 2
    private const val THERMAL_SEVERE = 3
    private const val THERMAL_SHUTDOWN = 6

    fun select(
        batteryPercent: Int?,
        thermalStatus: Int?,
        powerSave: Boolean?,
        memoryPressure: OrionMemoryPressure,
        backgroundRestricted: Boolean?,
    ): OrionResourcePolicy {
        val battery = batteryPercent?.takeIf { it in 0..100 }
        val thermal = thermalStatus?.takeIf { it in 0..THERMAL_SHUTDOWN }
        val profile = when {
            memoryPressure == OrionMemoryPressure.CRITICAL ||
                (battery != null && battery <= CRITICAL_BATTERY_PERCENT) -> OrionResourceProfile.CRITICAL
            thermal != null && thermal >= THERMAL_SEVERE -> OrionResourceProfile.THERMAL_LIMIT
            battery == null || thermal == null || powerSave == null ||
                memoryPressure == OrionMemoryPressure.UNKNOWN || battery <= LOW_BATTERY_PERCENT ->
                OrionResourceProfile.LOW_POWER
            powerSave || thermal == THERMAL_MODERATE || memoryPressure == OrionMemoryPressure.MODERATE ->
                OrionResourceProfile.ECONOMY
            else -> OrionResourceProfile.NORMAL
        }
        return OrionResourcePolicy(profile, profile == OrionResourceProfile.NORMAL && backgroundRestricted == false)
    }
}
