package network.orion.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class OrionPlatformDiagnosticsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun nativeQualificationAndLimiterEvidenceAreVisible() {
        val app = compose.activity.application as OrionApplication
        compose.waitUntil(30_000) { app.mainRuntime?.health?.value?.lastRecovery != null }
        compose.onNodeWithText("Diagnóstico", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Compatibilidade nativa: " + compose.activity.getString(R.string.orion_native_qualification_status))
            .assertExists()
        compose.onNode(hasText("Classificação do Memory Limiter:", substring = true)).assertExists()
        compose.onNode(hasText("Evidência recente de Memory Limiter:", substring = true)).assertExists()
    }
}
