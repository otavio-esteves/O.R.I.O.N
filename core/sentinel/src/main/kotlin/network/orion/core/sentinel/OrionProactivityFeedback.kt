package network.orion.core.sentinel

import java.time.Duration
import java.time.Instant

enum class OrionProactivityFeedbackKind {
    SHOWN,
    OPENED,
    DISMISSED,
    IGNORED,
    SNOOZED,
    COMPLETED,
    ;

    val isUserAction: Boolean get() = this != SHOWN && this != IGNORED
}

data class OrionProactivityFeedback(
    val kind: OrionProactivityFeedbackKind,
    val occurredAt: Instant,
    val timeToAction: Duration?,
) {
    init {
        require(timeToAction == null || (kind.isUserAction && !timeToAction.isNegative))
    }

    companion object {
        /**
         * Civil timestamps may cross process death or reboots. Clock rollback leaves elapsed action
         * time unavailable and preserves the feedback itself.
         */
        fun from(
            kind: OrionProactivityFeedbackKind,
            occurredAt: Instant,
            shownAt: Instant?,
        ): OrionProactivityFeedback {
            val timeToAction = if (kind.isUserAction && shownAt != null && occurredAt >= shownAt)
                Duration.between(shownAt, occurredAt)
            else null
            return OrionProactivityFeedback(kind, occurredAt, timeToAction)
        }
    }
}
