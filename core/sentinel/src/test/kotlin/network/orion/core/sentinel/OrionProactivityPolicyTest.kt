package network.orion.core.sentinel

import network.orion.core.health.OrionHealthStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class OrionProactivityPolicyTest {
    private val now = Instant.parse("2026-11-21T18:00:00Z")
    private val context = OrionContextSnapshot(
        observedAt = now,
        zoneId = ZoneOffset.UTC,
        batteryPercent = 80,
        charging = false,
        thermalState = OrionThermalState.NONE,
        screenInteractive = true,
        quietHours = null,
        tasksDueSoon = 3,
        coreHealth = OrionHealthStatus.HEALTHY,
    )
    private val candidate = OrionProactivityCandidate(
        sourceId = UUID.fromString("b1f1c2f1-d0cb-4cb9-8da2-965f56833e94"),
        topic = OrionProactivityTopic.DUE_TASK,
        urgency = 100,
        priority = 100,
        deadline = now.plusSeconds(300),
        importance = 100,
        contextRelevance = 100,
    )
    private val noHistory = OrionProactivityHistory(false, null, null, 0, null)
    private val policy = OrionProactivityPolicy()

    @Test
    fun scoreCombinesUrgencyPriorityDeadlineImportanceRelevanceAndInactivity() {
        assertEquals(OrionProactivityDecision.Proposal(95, OrionProactivityBand.CRITICAL), decide())
        assertEquals(
            OrionProactivityDecision.Proposal(100, OrionProactivityBand.CRITICAL),
            decide(context = context.copy(lastInteractionAt = now.minus(Duration.ofHours(12)))),
        )
        assertEquals(
            OrionProactivityDecision.Proposal(97, OrionProactivityBand.CRITICAL),
            decide(context = context.copy(lastInteractionAt = now.minus(Duration.ofHours(3)))),
        )
        assertEquals(95, decide(context = context.copy(lastInteractionAt = now.plusSeconds(1))).score)
        assertEquals(75, decide(candidate = candidate.copy(deadline = null)).score)
        assertEquals(85, decide(candidate = candidate.copy(deadline = now.plus(Duration.ofHours(24)))).score)
        assertEquals(90, decide(candidate = candidate.copy(deadline = now.plus(Duration.ofHours(6)))).score)
        assertEquals(70, decide(candidate = candidate.copy(urgency = 0)).score)
        assertEquals(75, decide(candidate = candidate.copy(priority = 0)).score)
        assertEquals(80, decide(candidate = candidate.copy(importance = 0)).score)
        assertEquals(80, decide(candidate = candidate.copy(contextRelevance = 0)).score)
        assertEquals(85, decide(history = noHistory.copy(alertsInWindow = 2)).score)
        assertEquals(75, decide(history = noHistory.copy(lastTopicDismissalAt = now)).score)
        assertEquals(
            95,
            decide(history = noHistory.copy(lastTopicDismissalAt = now.minus(Duration.ofHours(48)))).score,
        )
    }

    @Test
    fun scoreThresholdAndNotificationBandAreDeterministic() {
        val low = candidate.copy(urgency = 0, priority = 0, deadline = null)
        assertEquals(
            OrionProactivityDecision.Suppressed(30, OrionProactivitySuppression.BELOW_THRESHOLD),
            decide(candidate = low),
        )
        assertEquals(
            OrionProactivityDecision.Proposal(60, OrionProactivityBand.NOTIFY),
            decide(candidate = candidate.copy(urgency = 40, deadline = null)),
        )
        assertEquals(
            OrionProactivityDecision.Suppressed(60, OrionProactivitySuppression.BELOW_THRESHOLD),
            OrionProactivityPolicy(notificationBand = OrionProactivityBand.HIGH).evaluate(
                context,
                candidate.copy(urgency = 40, deadline = null),
                noHistory,
            ),
        )
    }

    @Test
    fun quietHoursAreLocalInclusiveAtStartExclusiveAtEndAndCoverBothDstOffsets() {
        val zone = ZoneId.of("America/New_York")
        val quiet = OrionQuietHours(LocalTime.of(22, 0), LocalTime.of(6, 0))
        val fallback = context.copy(observedAt = Instant.parse("2026-11-01T05:30:00Z"), zoneId = zone, quietHours = quiet)
        assertEquals(DayOfWeek.SUNDAY, fallback.dayOfWeek)
        assertEquals(LocalTime.of(1, 30), fallback.localTime)
        assertTrue(fallback.isQuietHours)
        assertTrue(fallback.copy(observedAt = fallback.observedAt.plus(Duration.ofHours(1))).isQuietHours)
        assertTrue(quiet.contains(LocalTime.of(22, 0)))
        assertFalse(quiet.contains(LocalTime.of(6, 0)))

        val daytime = OrionQuietHours(LocalTime.NOON, LocalTime.of(13, 0))
        assertTrue(daytime.contains(LocalTime.NOON))
        assertFalse(daytime.contains(LocalTime.of(13, 0)))

        val quietNow = context.copy(quietHours = daytime, zoneId = ZoneOffset.ofHours(-6))
        assertEquals(
            OrionProactivityDecision.Suppressed(55, OrionProactivitySuppression.QUIET_HOURS),
            decide(context = quietNow),
        )
    }

    @Test
    fun duplicateCooldownAndBudgetAreIndependent() {
        assertReason(OrionProactivitySuppression.DUPLICATE, noHistory.copy(duplicate = true))
        assertReason(
            OrionProactivitySuppression.COOLDOWN,
            noHistory.copy(lastAlertAt = now.minusSeconds(3599)),
        )
        assertReason(
            OrionProactivitySuppression.TOPIC_COOLDOWN,
            noHistory.copy(lastTopicAlertAt = now.minusSeconds(86399)),
        )
        assertReason(
            OrionProactivitySuppression.NOTIFICATION_BUDGET,
            noHistory.copy(alertsInWindow = policy.maxNotificationsInWindow),
        )
        assertReason(
            OrionProactivitySuppression.SNOOZED,
            noHistory.copy(snoozedUntil = now.plusSeconds(1)),
        )
        assertTrue(
            decide(
                history = noHistory.copy(
                    lastAlertAt = now.minus(policy.cooldown),
                    lastTopicAlertAt = now.minus(policy.topicCooldown),
                    alertsInWindow = policy.maxNotificationsInWindow - 1,
                    snoozedUntil = now,
                ),
            ) is OrionProactivityDecision.Proposal,
        )
    }

    @Test
    fun clockRollbackPreventsASecondNotification() {
        assertReason(OrionProactivitySuppression.CLOCK_ROLLBACK, noHistory.copy(lastAlertAt = now.plusSeconds(1)))
        assertReason(OrionProactivitySuppression.CLOCK_ROLLBACK, noHistory.copy(lastTopicAlertAt = now.plusSeconds(1)))
        assertReason(OrionProactivitySuppression.CLOCK_ROLLBACK, noHistory.copy(lastTopicDismissalAt = now.plusSeconds(1)))
    }

    @Test
    fun recoversOnlyAfterHealthAndKnownResourcePressureAllowProposals() {
        assertEquals(
            OrionProactivitySuppression.CORE_UNAVAILABLE,
            (decide(context = context.copy(coreHealth = OrionHealthStatus.RECOVERING)) as OrionProactivityDecision.Suppressed).reason,
        )
        assertEquals(
            OrionProactivitySuppression.RESOURCE_PRESSURE,
            (decide(context = context.copy(thermalState = OrionThermalState.SEVERE)) as OrionProactivityDecision.Suppressed).reason,
        )
        assertEquals(
            OrionProactivitySuppression.RESOURCE_PRESSURE,
            (decide(context = context.copy(batteryPercent = 15)) as OrionProactivityDecision.Suppressed).reason,
        )
        assertTrue(
            decide(context = context.copy(batteryPercent = 15, charging = true, coreHealth = OrionHealthStatus.DEGRADED))
                is OrionProactivityDecision.Proposal,
        )
    }

    @Test
    fun feedbackRecordsTechnicalTimeToActionOnlyForObservedUserActions() {
        val actionAt = now.plusSeconds(600)
        OrionProactivityFeedbackKind.entries.forEach { kind ->
            val event = OrionProactivityFeedback.from(kind, actionAt, now)
            assertEquals(actionAt, event.occurredAt)
            assertEquals(if (kind.isUserAction) Duration.ofMinutes(10) else null, event.timeToAction)
        }
        assertNull(OrionProactivityFeedback.from(OrionProactivityFeedbackKind.OPENED, now, actionAt).timeToAction)
        assertNull(OrionProactivityFeedback.from(OrionProactivityFeedbackKind.COMPLETED, now, null).timeToAction)
    }

    private fun assertReason(reason: OrionProactivitySuppression, history: OrionProactivityHistory) {
        assertEquals(reason, (decide(history = history) as OrionProactivityDecision.Suppressed).reason)
    }

    private fun decide(
        context: OrionContextSnapshot = this.context,
        candidate: OrionProactivityCandidate = this.candidate,
        history: OrionProactivityHistory = noHistory,
    ): OrionProactivityDecision = policy.evaluate(context, candidate, history)
}
