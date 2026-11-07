package network.orion.scheduler.alarms

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

/** Identifier-only OS delivery identity; a stale generation cannot replace a new alarm. */
data class OrionAlarmToken(val reminderId: UUID, val generation: Long, val requestCode: Int) {
    init { require(generation >= 0) }

    val identity: String get() = "orion://reminder/$reminderId/$generation"
}

sealed interface AlarmRegistrationResult {
    data class Registered(val selection: AlarmSelection) : AlarmRegistrationResult
    data object Unavailable : AlarmRegistrationResult
}

/** MAIN owns scheduling authority and persisted generation; this port does not grant it. */
interface OrionReminderAlarmPort {
    fun schedule(token: OrionAlarmToken, triggerAt: Instant, requirements: AlarmRequirements): AlarmRegistrationResult
    fun cancel(token: OrionAlarmToken): Boolean
}

/** Small platform seam permits deterministic permission-race and fallback tests. */
interface OrionAlarmBackend : ExactAlarmCapability {
    fun exact(token: OrionAlarmToken, triggerAtEpochMillis: Long)
    fun approximate(token: OrionAlarmToken, triggerAtEpochMillis: Long)
    fun cancel(token: OrionAlarmToken)
}

/**
 * A registration is replay-safe for the same token. Android owns only a delivery
 * artifact; the caller persists the returned strategy and rechecks domain state.
 * Exact access can disappear after selection, so its SecurityException degrades
 * to an inexact registration with an explicit diagnostic reason.
 */
class OrionReminderAlarms(private val backend: OrionAlarmBackend) : OrionReminderAlarmPort {
    override fun schedule(token: OrionAlarmToken, triggerAt: Instant, requirements: AlarmRequirements): AlarmRegistrationResult {
        val at = triggerAt.toEpochMilli()
        return try {
            val selected = OrionAlarmStrategy(backend).select(requirements)
            if (selected.precision == AlarmPrecision.EXACT) {
                try {
                    backend.exact(token, at)
                    AlarmRegistrationResult.Registered(selected)
                } catch (_: SecurityException) {
                    backend.approximate(token, at)
                    AlarmRegistrationResult.Registered(AlarmSelection(AlarmPrecision.APPROXIMATE,
                        AlarmScheduleMethod.INEXACT_ALARM, AlarmSelectionReason.EXACT_ACCESS_DENIED))
                }
            } else {
                backend.approximate(token, at)
                AlarmRegistrationResult.Registered(selected)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            // Exception messages may contain platform data. Return a technical result only.
            AlarmRegistrationResult.Unavailable
        }
    }

    override fun cancel(token: OrionAlarmToken): Boolean = try {
        backend.cancel(token)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    } catch (_: Exception) {
        false
    }
}
