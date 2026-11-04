package network.orion.core.actions

import java.util.UUID
import network.orion.core.model.RecoveryStrategy
import network.orion.core.model.SideEffectClass
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant

/** Persisted execution identity. This is ownership metadata, never effect authorization. */
data class ActionExecutionLease(
    val executionId: UUID,
    val claimedBy: String,
    val bootSessionId: BootSessionId,
    val startedElapsedRealtime: Long,
    val expiresElapsedRealtime: Long,
) {
    init {
        require(claimedBy.isNotBlank() && claimedBy.length <= 128 && claimedBy.none(Char::isISOControl))
        require(startedElapsedRealtime >= 0 && expiresElapsedRealtime > startedElapsedRealtime)
    }

    fun statusAt(now: MonotonicInstant): ActionLeaseStatus = when {
        now.bootSessionId != bootSessionId || now.elapsedRealtimeMillis < startedElapsedRealtime -> ActionLeaseStatus.INVALID
        now.elapsedRealtimeMillis >= expiresElapsedRealtime -> ActionLeaseStatus.EXPIRED
        else -> ActionLeaseStatus.ACTIVE
    }

    override fun toString(): String = "ActionExecutionLease(<redacted>)"
}

enum class ActionLeaseStatus { ACTIVE, EXPIRED, INVALID }
enum class AbandonedActionResolution { KEEP_RUNNING, UNKNOWN, MANUAL_REVIEW }

/**
 * Conservative F2 recovery only. An abandoned lease never proves nonexecution.
 * Safe replay/verification needs a later operation-specific contract; this policy
 * never schedules a retry, including when static metadata declares RETRY_SAFE.
 */
object AbandonedActionRecovery {
    fun resolve(lease: ActionExecutionLease?, now: MonotonicInstant,
        strategy: RecoveryStrategy, sideEffect: SideEffectClass): AbandonedActionResolution {
        if (lease?.statusAt(now) == ActionLeaseStatus.ACTIVE) return AbandonedActionResolution.KEEP_RUNNING
        if (strategy == RecoveryStrategy.MANUAL_REVIEW) return AbandonedActionResolution.MANUAL_REVIEW
        return when (sideEffect) {
            SideEffectClass.EXTERNAL_REVERSIBLE, SideEffectClass.EXTERNAL_IRREVERSIBLE,
            SideEffectClass.DESTRUCTIVE -> AbandonedActionResolution.UNKNOWN
            SideEffectClass.READ_ONLY, SideEffectClass.LOCAL_MUTATION -> AbandonedActionResolution.MANUAL_REVIEW
        }
    }
}
