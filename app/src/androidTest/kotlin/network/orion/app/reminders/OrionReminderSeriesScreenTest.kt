package network.orion.app.reminders

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import network.orion.app.MainActivity
import network.orion.core.commands.reminders.*
import network.orion.core.model.OrionReminderRecurrenceFrequency
import network.orion.core.model.OrionReminderRecurrenceRule
import network.orion.core.orchestrator.reminders.*
import network.orion.core.queries.reminders.*
import network.orion.feature.reminders.RemindersScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native Compose UI with fake ports and display-only previews; no business operation is submitted. */
@RunWith(AndroidJUnit4::class)
class OrionReminderSeriesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun finiteDailyPreviewDisplaysEveryExactAlertAndOnlyFinalButtonConfirms() {
        val reminder = ReminderSummary(UUID.randomUUID(), "Revisão diária", ReminderState.PENDING, 0,
            ReminderContentState.READABLE, Instant.parse("2026-11-08T10:00:00Z"), "UTC", "UNSCHEDULED")
        val old = FakeReminderGateway(reminder)
        val delivery = FakeDeliveryGateway(reminder)
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { MaterialTheme { RemindersScreen(old, deliveryGateway = delivery) } }
        }
        ready()
        click("Repetir diariamente: 7 alertas")
        compose.waitUntil { delivery.preparations == 1 }
        ready()
        assertEquals(0, delivery.confirmations)
        compose.onNodeWithText("Confirmar estes 7 alertas?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cada notificação mostrará: Você tem um lembrete.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Solicitar horário exato quando permitido", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("usar entrega aproximada", substring = true).assertExists()
        compose.onNodeWithText("até 24 horas depois", substring = true).assertExists()
        for (day in 8..14) {
            val date = "${day.toString().padStart(2, '0')}/11/2026 10:00 Z [UTC]"
            compose.onNodeWithText(date).performScrollTo().assertIsDisplayed()
        }
        assertEquals(0, delivery.confirmations)
        click("Voltar")
        compose.onNodeWithTag("orion_reminder_series_confirmation").assertDoesNotExist()
        assertEquals(0, delivery.confirmations)
        assertEquals(0, old.mutations)

        click("Repetir diariamente: 7 alertas")
        compose.waitUntil { delivery.preparations == 2 }
        ready()
        assertEquals(0, delivery.confirmations)
        click("Confirmar todos os horários")
        compose.waitUntil { delivery.confirmations == 1 }
        ready()
        assertSame(delivery.prepared, delivery.confirmed)
        assertEquals(1, delivery.confirmations)
        assertEquals(0, old.mutations)
        compose.onNodeWithTag("orion_reminder_series_confirmation").assertDoesNotExist()
        compose.onNodeWithText("Série de alertas autorizada.").assertExists()
    }

    private fun ready() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("orion_reminders").fetchSemanticsNodes().singleOrNull()?.config
                ?.getOrNull(SemanticsProperties.StateDescription) == "Lembretes atualizados"
        }
    }
    private fun click(text: String) {
        ready()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsEnabled().performClick()
        compose.waitForIdle()
    }

    private class FakeDeliveryGateway(private val reminder: ReminderSummary) : ReminderDeliveryGateway {
        @Volatile var preparations = 0
        @Volatile var confirmations = 0
        @Volatile var prepared: ReminderSeriesPreview? = null
        @Volatile var confirmed: ReminderSeriesPreview? = null
        override suspend fun prepare(reminderId: UUID, expectedVersion: Long) =
            ReminderDeliveryPreparation.Blocked(ReminderDeliveryOutcome.UNAVAILABLE)
        override suspend fun confirm(preview: ReminderDeliveryPreview): ReminderDeliveryOutcome =
            error("Single-alert confirmation was not requested")
        override suspend fun prepareSeries(reminderId: UUID, expectedVersion: Long,
            rule: OrionReminderRecurrenceRule): ReminderSeriesPreparation {
            assertEquals(reminder.reminderId, reminderId)
            assertEquals(reminder.version, expectedVersion)
            assertEquals(OrionReminderRecurrenceFrequency.DAILY, rule.frequency)
            assertEquals(1, rule.interval)
            assertEquals(7, rule.count)
            assertEquals(LocalDateTime.parse("2026-11-08T10:00"), rule.startLocalDateTime)
            assertEquals(ZoneId.of("UTC"), rule.zoneId)
            val children = (0..6).map { index ->
                val local = rule.startLocalDateTime.plusDays(index.toLong())
                val instant = local.atZone(rule.zoneId).toInstant()
                val child = ReminderDeliveryPreview(reminderId, expectedVersion, index + 1L, instant,
                    UUID.randomUUID(), 1, "a".repeat(64), "fixture")
                ReminderSeriesOccurrence(child, local, local, 0, "NORMAL")
            }
            val preview = ReminderSeriesPreview(UUID.randomUUID(), rule, children, "b".repeat(64))
            prepared = preview
            preparations++
            return ReminderSeriesPreparation.Ready(preview)
        }
        override suspend fun confirmSeries(preview: ReminderSeriesPreview): ReminderDeliveryOutcome {
            confirmed = preview
            confirmations++
            return ReminderDeliveryOutcome.ENABLED
        }
    }

    private class FakeReminderGateway(private val reminder: ReminderSummary) : ReminderGateway {
        @Volatile var mutations = 0
        override fun prepareCreate(id: UUID, title: String, afterMinutes: Long, key: UUID, trace: UUID): CreateReminder? = null
        override fun prepareSnooze(id: UUID, version: Long, currentScheduledAt: Instant,
            afterMinutes: Long, key: UUID, trace: UUID): SnoozeReminder? = null
        override suspend fun create(command: CreateReminder): CreateReminderResult { mutations++; return CreateReminderResult.UNAVAILABLE }
        override suspend fun change(command: ReminderStatusCommand): ReminderStatusResult { mutations++; return ReminderStatusResult.UNAVAILABLE }
        override suspend fun list(request: GetReminders) = ReminderPage(listOf(reminder), null)
        override suspend fun pending() = emptyList<ReminderConfirmation>()
        override suspend fun confirm(actionId: UUID, displayedVersion: Long): ReminderOperationOutcome {
            mutations++; return ReminderOperationOutcome.UNAVAILABLE
        }
        override suspend fun dismiss(actionId: UUID, displayedVersion: Long): ReminderOperationOutcome {
            mutations++; return ReminderOperationOutcome.UNAVAILABLE
        }
        override suspend fun processPending() = 0
    }
}
