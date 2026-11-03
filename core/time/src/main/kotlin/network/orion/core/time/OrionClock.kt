package network.orion.core.time

import java.time.Instant

/**
 * Civil UTC instants for persisted dates and audit. May jump forward or backward.
 * Zone/DST resolution belongs to the domain; never use this clock for durations.
 */
fun interface WallClock {
    fun wallNow(): Instant
}

/** Elapsed time since boot in milliseconds, including deep sleep, with boot identity. */
fun interface MonotonicClock {
    fun monotonicNow(): MonotonicInstant
}

/** Injectable time boundary. The two readings are independent, not an atomic snapshot. */
interface OrionClock : WallClock, MonotonicClock
