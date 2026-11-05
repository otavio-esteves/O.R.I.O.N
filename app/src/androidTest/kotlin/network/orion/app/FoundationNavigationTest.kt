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
        compose.onNodeWithText("Core: DEGRADED_SAFE").assertExists()
        compose.onNodeWithText("Diagnóstico").performClick()
        compose.onNodeWithText("Contrato IPC: 1.0").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Contrato IPC: 1.0").assertExists()
        compose.onNodeWithText("Ajustes").performClick()
        compose.onNodeWithText("Backup do app: desativado").assertExists()
        compose.onNodeWithText("Início").performClick()
        compose.onNodeWithText("Core: DEGRADED_SAFE").assertExists()
    }

    @Test fun readOnlyDestinationsAreReachableFromHomeAndRestoreAfterRecreation() {
        compose.onNodeWithText("Dispositivo").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Bateria").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Bateria").assertExists()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Bateria").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Bateria").assertExists()
        compose.onNodeWithText("Início").performClick()
        compose.onNodeWithText("Atividade").performScrollTo().performClick()
        compose.onNodeWithText("Histórico local de pedidos e alterações.").assertExists()
    }

    @Test fun remindersDestinationRestoresWithoutClaimingAutomaticDelivery() {
        compose.onNodeWithText("Lembretes").performScrollTo().performClick()
        compose.onNodeWithTag("orion_reminders").assertExists()
        compose.onNodeWithText("Lembretes ficam salvos. Alertas automáticos ainda não estão disponíveis.").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("orion_reminders").assertExists()
    }

    @Test fun timersDestinationRestoresWithoutClaimingBackgroundAlerts() {
        compose.onNodeWithText("Timers").performScrollTo().performClick()
        compose.onNodeWithTag("orion_timers").assertExists()
        compose.onNodeWithText("Contagem local. Alertas em segundo plano ainda não estão disponíveis.").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("orion_timers").assertExists()
    }

    @Test fun userMemoryDestinationRestoresWithExplicitProvenance() {
        compose.onNodeWithText("Memórias").performScrollTo().performClick()
        compose.onNodeWithTag("orion_memories").assertExists()
        compose.onNodeWithText("Guarde uma informação pessoal declarada por você. Nenhuma memória é inferida automaticamente.").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("orion_memories").assertExists()
    }

    @Test fun notificationDestinationRestoresAndNeverPostsOnNavigation() {
        val manager = compose.activity.getSystemService(android.app.NotificationManager::class.java)
        val before = manager?.activeNotifications.orEmpty().map { "${it.tag}/${it.id}" }.sorted()
        compose.onNodeWithText("Notificações").performScrollTo().performClick()
        compose.onNodeWithTag("orion_notifications").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("orion_notifications").assertExists()
        org.junit.Assert.assertEquals(before, manager?.activeNotifications.orEmpty().map { "${it.tag}/${it.id}" }.sorted())
    }
}
