package network.orion.core.time

import java.time.Duration

/**
 * Milliseconds since boot, not Unix epoch. Null identity means unavailable and
 * cannot validate even another null-identity reading. Persist identity with time
 * when needed; this reference alone cannot schedule work across reboot.
 */
data class MonotonicInstant(
    val bootSessionId: BootSessionId?,
    val elapsedRealtimeMillis: Long,
) {
    init {
        require(elapsedRealtimeMillis >= 0) { "Elapsed realtime must be nonnegative." }
    }

    /** Millisecond-resolution duration, or null for unknown/different boot or regression. */
    fun elapsedSince(start: MonotonicInstant): Duration? {
        if (bootSessionId == null || bootSessionId != start.bootSessionId ||
            elapsedRealtimeMillis < start.elapsedRealtimeMillis
        ) return null
        return Duration.ofMillis(elapsedRealtimeMillis - start.elapsedRealtimeMillis)
    }

    /**
     * Nonnegative whole milliseconds; zero expires immediately. Negative duration
     * throws IllegalArgumentException; a deadline beyond Long.MAX_VALUE throws
     * ArithmeticException. An unavailable identity yields an invalid reference.
     */
    fun deadlineAfter(durationMillis: Long): MonotonicDeadline = MonotonicDeadline(this, durationMillis)
}

/** Invalid references are neither active nor expired and never authorize retry. */
enum class MonotonicDeadlineStatus {
    ACTIVE,
    EXPIRED,
    INVALID_REFERENCE,
}

/**
 * Boot-bound deadline retaining its start to detect monotonic regression.
 * This is time arithmetic only, not an execution lease or recovery policy.
 */
class MonotonicDeadline internal constructor(
    val start: MonotonicInstant,
    durationMillis: Long,
) {
    val expiresElapsedRealtimeMillis: Long

    init {
        require(durationMillis >= 0) { "Duration must be nonnegative." }
        expiresElapsedRealtimeMillis = Math.addExact(start.elapsedRealtimeMillis, durationMillis)
    }

    /** Equality with the deadline is expired; boot validity is checked first. */
    fun statusAt(now: MonotonicInstant): MonotonicDeadlineStatus = when {
        now.elapsedSince(start) == null -> MonotonicDeadlineStatus.INVALID_REFERENCE
        now.elapsedRealtimeMillis >= expiresElapsedRealtimeMillis -> MonotonicDeadlineStatus.EXPIRED
        else -> MonotonicDeadlineStatus.ACTIVE
    }
}
