package network.orion.core.sentinel

import network.orion.core.health.OrionHealthStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class OrionProactivityTopic { OPEN_TASKS, DUE_TASK, UPCOMING_REMINDER, CORE_HEALTH }

/** A stable source identifier and normalized relevance signals, without displayable content. */
data class OrionProactivityCandidate(
    val sourceId: UUID,
    val topic: OrionProactivityTopic,
    val urgency: Int,
    val priority: Int,
    val deadline: Instant?,
    val importance: Int,
    val contextRelevance: Int,
) {
    init {
        require(urgency in 0..100 && priority in 0..100 && importance in 0..100 && contextRelevance in 0..100)
    }
}

/**
 * MAIN supplies the history of committed notification reservations, including ambiguous delivery.
 * The rolling count and the last reservation must come from the same serialized snapshot.
 */
data class OrionProactivityHistory(
    val duplicate: Boolean,
    val lastAlertAt: Instant?,
    val lastTopicAlertAt: Instant?,
    val alertsInWindow: Int,
    val lastTopicDismissalAt: Instant?,
    val snoozedUntil: Instant? = null,
) {
    init {
        require(alertsInWindow >= 0)
    }
}

enum class OrionProactivityBand(val minimumScore: Int) {
    IGNORE(0),
    OBSERVE(40),
    NOTIFY(60),
    HIGH(80),
    CRITICAL(90),
}

enum class OrionProactivitySuppression {
    CORE_UNAVAILABLE,
    RESOURCE_PRESSURE,
    QUIET_HOURS,
    DUPLICATE,
    SNOOZED,
    CLOCK_ROLLBACK,
    COOLDOWN,
    TOPIC_COOLDOWN,
    NOTIFICATION_BUDGET,
    BELOW_THRESHOLD,
}

sealed interface OrionProactivityDecision {
    val score: Int

    /** A recommendation to submit to Core Policy, without side effect or execution authority. */
    data class Proposal(override val score: Int, val band: OrionProactivityBand) : OrionProactivityDecision
    data class Suppressed(override val score: Int, val reason: OrionProactivitySuppression) : OrionProactivityDecision
}

class OrionProactivityPolicy(
    val cooldown: Duration = Duration.ofHours(1),
    val topicCooldown: Duration = Duration.ofHours(24),
    val notificationWindow: Duration = Duration.ofHours(24),
    val maxNotificationsInWindow: Int = 3,
    val notificationBand: OrionProactivityBand = OrionProactivityBand.NOTIFY,
) {
    init {
        require(listOf(cooldown, topicCooldown, notificationWindow).all { !it.isZero && !it.isNegative })
        require(maxNotificationsInWindow > 0)
        require(notificationBand >= OrionProactivityBand.NOTIFY)
    }

    fun evaluate(
        context: OrionContextSnapshot,
        candidate: OrionProactivityCandidate,
        history: OrionProactivityHistory,
    ): OrionProactivityDecision {
        val now = context.observedAt
        val score = score(context, candidate, history)
        val band = OrionProactivityBand.entries.last { score >= it.minimumScore }
        val reason = when {
            context.coreHealth != OrionHealthStatus.HEALTHY &&
                context.coreHealth != OrionHealthStatus.DEGRADED -> OrionProactivitySuppression.CORE_UNAVAILABLE
            context.thermalState?.let { it >= OrionThermalState.SEVERE } == true ||
                (context.batteryPercent?.let { it <= CRITICAL_BATTERY_PERCENT } == true &&
                    context.charging != true) -> OrionProactivitySuppression.RESOURCE_PRESSURE
            context.isQuietHours -> OrionProactivitySuppression.QUIET_HOURS
            history.duplicate -> OrionProactivitySuppression.DUPLICATE
            history.snoozedUntil?.isAfter(now) == true -> OrionProactivitySuppression.SNOOZED
            listOfNotNull(history.lastAlertAt, history.lastTopicAlertAt, history.lastTopicDismissalAt)
                .any { it.isAfter(now) } -> OrionProactivitySuppression.CLOCK_ROLLBACK
            history.lastAlertAt.isWithin(now, cooldown) -> OrionProactivitySuppression.COOLDOWN
            history.lastTopicAlertAt.isWithin(now, topicCooldown) -> OrionProactivitySuppression.TOPIC_COOLDOWN
            history.alertsInWindow >= maxNotificationsInWindow -> OrionProactivitySuppression.NOTIFICATION_BUDGET
            band < notificationBand -> OrionProactivitySuppression.BELOW_THRESHOLD
            else -> null
        }
        return if (reason == null) OrionProactivityDecision.Proposal(score, band)
        else OrionProactivityDecision.Suppressed(score, reason)
    }

    private fun score(
        context: OrionContextSnapshot,
        candidate: OrionProactivityCandidate,
        history: OrionProactivityHistory,
    ): Int {
        val now = context.observedAt
        val inactivity = context.lastInteractionAt?.let { Duration.between(it, now) }
        val inactivityScore = when {
            inactivity == null || inactivity < INACTIVITY_START -> 0
            inactivity < INACTIVITY_HIGH -> 2
            else -> 5
        }
        val deadlineScore = candidate.deadline?.let { deadlineScore(Duration.between(now, it)) } ?: 0
        val fatigue = history.alertsInWindow.coerceAtMost(maxNotificationsInWindow) * 15L /
            maxNotificationsInWindow
        val dismissal = if (history.lastTopicDismissalAt.isWithin(now, DISMISSAL_PENALTY_WINDOW)) 20 else 0
        val quietHours = if (context.isQuietHours) 40 else 0
        val weightedSignals = (25 * candidate.urgency + 20 * candidate.priority + 15 * candidate.importance +
            15 * candidate.contextRelevance) / 100
        return (weightedSignals + deadlineScore + inactivityScore - fatigue - dismissal - quietHours)
            .coerceIn(0, 100).toInt()
    }

    private fun deadlineScore(remaining: Duration): Int = when {
        remaining <= Duration.ofHours(1) -> 20
        remaining <= Duration.ofHours(6) -> 15
        remaining <= Duration.ofHours(24) -> 10
        else -> 0
    }

    private fun Instant?.isWithin(now: Instant, window: Duration): Boolean =
        this != null && !isAfter(now) && Duration.between(this, now) < window

    private companion object {
        const val CRITICAL_BATTERY_PERCENT = 15
        val INACTIVITY_START: Duration = Duration.ofHours(3)
        val INACTIVITY_HIGH: Duration = Duration.ofHours(12)
        val DISMISSAL_PENALTY_WINDOW: Duration = Duration.ofHours(48)
    }
}
