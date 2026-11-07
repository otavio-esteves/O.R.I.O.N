package network.orion.scheduler.alarms

import org.junit.Assert.assertEquals
import org.junit.Test

class OrionAlarmStrategyTest {
    private val preciseReminder = AlarmRequirements(true, true, true)

    @Test fun exactSelectionRequiresEveryEligibilityCondition() {
        for (enabled in listOf(false, true)) {
            for (facing in listOf(false, true)) {
                for (precise in listOf(false, true)) {
                    for (availability in ExactAlarmAvailability.entries) {
                        var observations = 0
                        val strategy = OrionAlarmStrategy { observations++; availability }
                        val selection = strategy.select(AlarmRequirements(enabled, facing, precise))
                        val eligible = enabled && facing && precise
                        val exact = eligible && availability == ExactAlarmAvailability.AVAILABLE
                        assertEquals(if (exact) AlarmPrecision.EXACT else AlarmPrecision.APPROXIMATE, selection.precision)
                        assertEquals(if (exact) AlarmScheduleMethod.EXACT_ALARM else AlarmScheduleMethod.INEXACT_ALARM, selection.method)
                        assertEquals(if (eligible) 1 else 0, observations)
                    }
                }
            }
        }
    }

    @Test fun capabilityIsResampledAfterRevocationAndGrant() {
        var availability = ExactAlarmAvailability.AVAILABLE
        val strategy = OrionAlarmStrategy { availability }
        assertEquals(AlarmSelectionReason.EXACT_AVAILABLE, strategy.select(preciseReminder).reason)
        availability = ExactAlarmAvailability.DENIED
        assertEquals(AlarmSelectionReason.EXACT_ACCESS_DENIED, strategy.select(preciseReminder).reason)
        availability = ExactAlarmAvailability.UNAVAILABLE
        assertEquals(AlarmSelectionReason.EXACT_CAPABILITY_UNAVAILABLE, strategy.select(preciseReminder).reason)
        availability = ExactAlarmAvailability.AVAILABLE
        assertEquals(AlarmSelectionReason.EXACT_AVAILABLE, strategy.select(preciseReminder).reason)
    }

    @Test fun fallbackExplainsUserPreferenceAndEventEligibility() {
        val strategy = OrionAlarmStrategy { error("Ineligible alarms must not request exact access") }
        assertEquals(AlarmSelectionReason.USER_PREFERS_APPROXIMATE,
            strategy.select(preciseReminder.copy(userEnabledExactReminders = false)).reason)
        assertEquals(AlarmSelectionReason.NOT_PRECISE_USER_FACING,
            strategy.select(preciseReminder.copy(userFacing = false)).reason)
        assertEquals(AlarmSelectionReason.NOT_PRECISE_USER_FACING,
            strategy.select(preciseReminder.copy(requiresPreciseTime = false)).reason)
    }
}
