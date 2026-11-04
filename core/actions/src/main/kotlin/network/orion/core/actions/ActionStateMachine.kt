package network.orion.core.actions

/**
 * Structural validation of the basic Action Outbox lifecycle only.
 *
 * An allowed edge grants no authority: the transactional executor must separately
 * enforce policy, confirmation binding/expiry, capabilities, permissions, startup
 * readiness and execution ownership. PENDING -> CONFIRMED is available only when
 * those policy decisions permit proceeding without a user confirmation step.
 *
 * Apply this check with the persisted state/version inside the state transaction.
 * This object does not mutate, persist or execute anything. Retry, reconciliation
 * and review resolution require separate guarded contracts; UNKNOWN never receives
 * generic retry and an abandoned RUNNING lease never proves absence of an effect.
 */
object ActionStateMachine {
    fun isTransitionAllowed(from: ActionStatus, to: ActionStatus): Boolean = when (from) {
        ActionStatus.PENDING -> to == ActionStatus.NEEDS_CONFIRMATION ||
            to == ActionStatus.CONFIRMED || to == ActionStatus.CANCELLED
        ActionStatus.NEEDS_CONFIRMATION -> to == ActionStatus.CONFIRMED || to == ActionStatus.CANCELLED
        ActionStatus.CONFIRMED -> to == ActionStatus.RUNNING || to == ActionStatus.CANCELLED
        ActionStatus.RUNNING -> to == ActionStatus.SUCCEEDED || to == ActionStatus.FAILED ||
            to == ActionStatus.UNKNOWN || to == ActionStatus.MANUAL_REVIEW
        ActionStatus.UNKNOWN -> to == ActionStatus.MANUAL_REVIEW
        ActionStatus.SUCCEEDED, ActionStatus.FAILED, ActionStatus.CANCELLED,
        ActionStatus.MANUAL_REVIEW -> false
    }

    fun requireTransition(from: ActionStatus, to: ActionStatus) {
        require(isTransitionAllowed(from, to)) { "Illegal action transition: $from -> $to" }
    }
}
