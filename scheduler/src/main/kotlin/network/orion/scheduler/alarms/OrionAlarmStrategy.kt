package network.orion.scheduler.alarms

/** A fresh capability observation, not a persisted permission grant. */
enum class ExactAlarmAvailability { AVAILABLE, DENIED, UNAVAILABLE }

fun interface ExactAlarmCapability {
    fun availability(): ExactAlarmAvailability
}

enum class AlarmPrecision { EXACT, APPROXIMATE }
enum class AlarmScheduleMethod { EXACT_ALARM, INEXACT_ALARM }
enum class AlarmSelectionReason {
    EXACT_AVAILABLE, USER_PREFERS_APPROXIMATE, NOT_PRECISE_USER_FACING,
    EXACT_ACCESS_DENIED, EXACT_CAPABILITY_UNAVAILABLE,
}

data class AlarmRequirements(
    val userEnabledExactReminders: Boolean,
    val userFacing: Boolean,
    val requiresPreciseTime: Boolean,
)

data class AlarmSelection(
    val precision: AlarmPrecision,
    val method: AlarmScheduleMethod,
    val reason: AlarmSelectionReason,
)

/**
 * Pure strategy selection for Architecture §§41–42. The future MAIN Android
 * adapter uses optional SCHEDULE_EXACT_ALARM access, never assumes USE_EXACT_ALARM,
 * and revalidates immediately before scheduling. No decision grants execution
 * authority or promises delivery. The scheduling owner must persist the selected
 * precision/method with the reminder, whose database remains the source of truth.
 */
class OrionAlarmStrategy(private val capability: ExactAlarmCapability) {
    fun select(requirements: AlarmRequirements): AlarmSelection {
        if (!requirements.userEnabledExactReminders) {
            return approximate(AlarmSelectionReason.USER_PREFERS_APPROXIMATE)
        }
        if (!requirements.userFacing || !requirements.requiresPreciseTime) {
            return approximate(AlarmSelectionReason.NOT_PRECISE_USER_FACING)
        }
        return when (capability.availability()) {
            ExactAlarmAvailability.AVAILABLE -> AlarmSelection(
                AlarmPrecision.EXACT, AlarmScheduleMethod.EXACT_ALARM, AlarmSelectionReason.EXACT_AVAILABLE,
            )
            ExactAlarmAvailability.DENIED -> approximate(AlarmSelectionReason.EXACT_ACCESS_DENIED)
            ExactAlarmAvailability.UNAVAILABLE -> approximate(AlarmSelectionReason.EXACT_CAPABILITY_UNAVAILABLE)
        }
    }

    private fun approximate(reason: AlarmSelectionReason) =
        AlarmSelection(AlarmPrecision.APPROXIMATE, AlarmScheduleMethod.INEXACT_ALARM, reason)
}
