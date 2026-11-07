package network.orion.scheduler.alarms

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class OrionReminderAlarmsTest {
    private val token = OrionAlarmToken(UUID.fromString("00000000-0000-0000-0000-000000000001"), 3, 9)
    private val at = Instant.parse("2026-11-08T14:00:00Z")
    private val requirements = AlarmRequirements(true, true, true)

    private class Backend : OrionAlarmBackend {
        var capability = ExactAlarmAvailability.AVAILABLE
        var exactFailure: Exception? = null
        var approximateFailure: Exception? = null
        var cancelFailure: Exception? = null
        val exactCalls = mutableListOf<Pair<OrionAlarmToken, Long>>()
        val approximateCalls = mutableListOf<Pair<OrionAlarmToken, Long>>()
        val cancellations = mutableListOf<OrionAlarmToken>()
        override fun availability() = capability
        override fun exact(token: OrionAlarmToken, triggerAtEpochMillis: Long) {
            exactCalls += token to triggerAtEpochMillis
            exactFailure?.let { throw it }
        }
        override fun approximate(token: OrionAlarmToken, triggerAtEpochMillis: Long) {
            approximateCalls += token to triggerAtEpochMillis
            approximateFailure?.let { throw it }
        }
        override fun cancel(token: OrionAlarmToken) {
            cancelFailure?.let { throw it }
            cancellations += token
        }
    }

    @Test fun exactUsesPersistedIdentifierGenerationAndCivilInstant() {
        val backend = Backend()
        val result = OrionReminderAlarms(backend).schedule(token, at, requirements) as AlarmRegistrationResult.Registered
        assertEquals(AlarmPrecision.EXACT, result.selection.precision)
        assertEquals(listOf(token to at.toEpochMilli()), backend.exactCalls)
        assertTrue(backend.approximateCalls.isEmpty())
    }

    @Test fun revokedExactAccessDowngradesSameTokenWithoutAnotherIdentity() {
        val backend = Backend().apply { exactFailure = SecurityException() }
        val result = OrionReminderAlarms(backend).schedule(token, at, requirements) as AlarmRegistrationResult.Registered
        assertEquals(AlarmSelectionReason.EXACT_ACCESS_DENIED, result.selection.reason)
        assertEquals(AlarmPrecision.APPROXIMATE, result.selection.precision)
        assertEquals(listOf(token to at.toEpochMilli()), backend.approximateCalls)
        assertEquals(backend.exactCalls, backend.approximateCalls)
    }

    @Test fun missingCapabilitySelectsObservableApproximateFallback() {
        for (availability in listOf(ExactAlarmAvailability.DENIED, ExactAlarmAvailability.UNAVAILABLE)) {
            val backend = Backend().apply { capability = availability }
            val result = OrionReminderAlarms(backend).schedule(token, at, requirements) as AlarmRegistrationResult.Registered
            assertTrue(backend.exactCalls.isEmpty())
            assertEquals(1, backend.approximateCalls.size)
            assertEquals(AlarmPrecision.APPROXIMATE, result.selection.precision)
            assertEquals(if (availability == ExactAlarmAvailability.DENIED) AlarmSelectionReason.EXACT_ACCESS_DENIED
                else AlarmSelectionReason.EXACT_CAPABILITY_UNAVAILABLE, result.selection.reason)
        }
    }

    @Test fun exactOptOutNeverInvokesExactBackend() {
        val backend = Backend()
        val result = OrionReminderAlarms(backend).schedule(token, at,
            requirements.copy(userEnabledExactReminders = false)) as AlarmRegistrationResult.Registered
        assertEquals(AlarmSelectionReason.USER_PREFERS_APPROXIMATE, result.selection.reason)
        assertTrue(backend.exactCalls.isEmpty())
        assertEquals(1, backend.approximateCalls.size)
    }

    @Test fun failedFallbackIsUnavailableRatherThanSuccessfulRegistration() {
        val backend = Backend().apply {
            exactFailure = SecurityException()
            approximateFailure = IllegalStateException()
        }
        assertEquals(AlarmRegistrationResult.Unavailable, OrionReminderAlarms(backend).schedule(token, at, requirements))
    }

    @Test fun unknownPlatformFailureDoesNotAssertRegistrationOrRetry() {
        val backend = Backend().apply { exactFailure = IllegalStateException() }
        assertEquals(AlarmRegistrationResult.Unavailable, OrionReminderAlarms(backend).schedule(token, at, requirements))
        assertTrue(backend.approximateCalls.isEmpty())
    }

    @Test fun cancellationPropagatesRatherThanBeingDegradedToRegistrationFailure() {
        val backend = Backend().apply { exactFailure = CancellationException() }
        assertThrows(CancellationException::class.java) {
            OrionReminderAlarms(backend).schedule(token, at, requirements)
        }
        assertTrue(backend.approximateCalls.isEmpty())
        backend.cancelFailure = CancellationException()
        assertThrows(CancellationException::class.java) { OrionReminderAlarms(backend).cancel(token) }
    }

    @Test fun cancelTouchesOnlyRequestedGenerationAndReportsFailure() {
        val backend = Backend()
        val alarms = OrionReminderAlarms(backend)
        assertTrue(alarms.cancel(token))
        assertEquals(listOf(token), backend.cancellations)
        backend.cancelFailure = IllegalStateException()
        assertFalse(alarms.cancel(token.copy(generation = 4)))
        assertEquals(listOf(token), backend.cancellations)
    }

    @Test fun identityIsStableAndSeparatesIdentifiersAndGenerationsEvenWithRequestCodeCollision() {
        assertEquals(token.identity, token.copy().identity)
        assertNotEquals(token.identity, token.copy(generation = 4).identity)
        assertNotEquals(token.identity, token.copy(reminderId = UUID.randomUUID()).identity)
        assertEquals("orion://reminder/${token.reminderId}/3", token.identity)
        assertThrows(IllegalArgumentException::class.java) { token.copy(generation = -1) }
    }
}
