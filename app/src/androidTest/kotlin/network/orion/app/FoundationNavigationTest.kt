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
        compose.onNodeWithText("Core: READY").assertExists()
        compose.onNodeWithText("Diagnóstico").performClick()
        compose.onNodeWithText("Contrato IPC: 1.0").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Contrato IPC: 1.0").assertExists()
        compose.onNodeWithText("Ajustes").performClick()
        compose.onNodeWithText("Backup do app: desativado").assertExists()
        compose.onNodeWithText("Início").performClick()
        compose.onNodeWithText("Core: READY").assertExists()
    }
}
