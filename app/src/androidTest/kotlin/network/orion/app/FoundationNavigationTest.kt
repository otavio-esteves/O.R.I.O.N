package network.orion.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class FoundationNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun navigationAndSelectedDestinationSurviveActivityRecreation() {
        compose.waitUntil(30_000) {
            checkNotNull((compose.activity.application as OrionApplication).mainRuntime)
                .health.value.core.readiness.isStartupBarrierOpen
        }
        compose.onNodeWithTag("orion_globe").assertIsDisplayed()
        compose.onNodeWithTag("orion_options").assertDoesNotExist()
        compose.onNodeWithText("Diagnóstico").assertDoesNotExist()
        compose.onNodeWithTag("orion_home").performTouchInput { swipeRight() }
        compose.onNodeWithTag("orion_globe").assertIsDisplayed()
        compose.onNodeWithTag("orion_options").assertDoesNotExist()
        compose.onNodeWithTag("orion_home").performTouchInput {
            swipeUp(startY = height * 0.80f, endY = height * 0.25f)
        }
        compose.onNodeWithTag("orion_options").assertIsDisplayed()
        compose.onNodeWithTag("orion_globe").assertDoesNotExist()
        val home = compose.onNodeWithTag("orion_home").getUnclippedBoundsInRoot()
        val options = compose.onNodeWithTag("orion_options").getUnclippedBoundsInRoot()
        org.junit.Assert.assertEquals(
            (home.top.value + home.bottom.value) / 2,
            (options.top.value + options.bottom.value) / 2,
            1f,
        )
        compose.onNodeWithTag("orion_options").performTouchInput { swipeUp() }
        compose.onNodeWithTag("orion_globe").assertIsDisplayed()
        compose.onNodeWithTag("orion_options").assertDoesNotExist()
        compose.onNodeWithTag("orion_globe").performClick()
        compose.onNodeWithTag("orion_options").performTouchInput { swipeDown() }
        compose.onNodeWithTag("orion_globe").assertIsDisplayed()
        compose.onNodeWithTag("orion_options").assertDoesNotExist()
        compose.onNodeWithTag("orion_globe").performClick()
        compose.onNodeWithText("Voltar").performClick()
        compose.onNodeWithTag("orion_globe").assertIsDisplayed()
        compose.openOrionDestination("Diagnóstico")
        compose.onNodeWithText("Core: DEGRADED_SAFE").assertExists()
        compose.onNodeWithText("Contrato IPC: 1.0").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Contrato IPC: 1.0").assertExists()
        compose.openOrionDestination("Ajustes")
        compose.onNodeWithText("Backup do app: desativado").assertExists()
        compose.onNodeWithText("Início").performClick()
        compose.onNodeWithTag("orion_globe").assertIsDisplayed()
        compose.onNodeWithTag("orion_options").assertDoesNotExist()
    }

    @Test fun readOnlyDestinationsAreReachableFromHomeAndRestoreAfterRecreation() {
        compose.openOrionDestination("Dispositivo")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Bateria").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Bateria").assertExists()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Bateria").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Bateria").assertExists()
        compose.onNodeWithText("Início").performClick()
        compose.openOrionDestination("Atividade")
        compose.onNodeWithText("Histórico local de pedidos e alterações.").assertExists()
    }

    @Test fun remindersDestinationRestoresWithoutClaimingAutomaticDelivery() {
        compose.openOrionDestination("Lembretes")
        compose.onNodeWithTag("orion_reminders").assertExists()
        compose.onNodeWithText("Lembretes ficam salvos. Ative e confirme um alerta único para receber uma notificação.").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("orion_reminders").assertExists()
    }

    @Test fun timersDestinationRestoresWithoutClaimingBackgroundAlerts() {
        compose.openOrionDestination("Timers")
        compose.onNodeWithTag("orion_timers").assertExists()
        compose.onNodeWithText("Contagem local. Alertas em segundo plano ainda não estão disponíveis.").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("orion_timers").assertExists()
    }

    @Test fun userMemoryDestinationRestoresWithExplicitProvenance() {
        compose.openOrionDestination("Memórias")
        compose.onNodeWithTag("orion_memories").assertExists()
        compose.onNodeWithText("Guarde uma informação pessoal declarada por você. Nenhuma memória é inferida automaticamente.").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("orion_memories").assertExists()
    }

    @Test fun notificationDestinationRestoresAndNeverPostsOnNavigation() {
        val manager = compose.activity.getSystemService(android.app.NotificationManager::class.java)
        val before = manager?.activeNotifications.orEmpty().map { "${it.tag}/${it.id}" }.sorted()
        compose.openOrionDestination("Notificações")
        compose.onNodeWithTag("orion_notifications").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("orion_notifications").assertExists()
        org.junit.Assert.assertEquals(before, manager?.activeNotifications.orEmpty().map { "${it.tag}/${it.id}" }.sorted())
    }
}
