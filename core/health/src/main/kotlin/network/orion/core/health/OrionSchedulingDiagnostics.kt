package network.orion.core.health

import java.time.Instant

/** Counts and capability evidence only; no reminder IDs, titles or consent hashes. */
data class OrionSchedulingDiagnostics(
    val pendingReminders: Int,
    val approximateReminders: Int,
    val deadLetterEvents: Int,
    val exactAlarmCapability: String,
    val heartbeatRegistered: Boolean,
    val backgroundRestricted: Boolean,
    val forceStopSignal: String,
    val lastForceStopDetectedAt: Instant?,
    val lastExitReason: Int?,
    val lastExitKind: String? = null,
)
