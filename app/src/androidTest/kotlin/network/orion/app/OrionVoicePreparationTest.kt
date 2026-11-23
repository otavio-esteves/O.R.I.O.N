package network.orion.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import network.orion.voice.service.OrionVoiceModel
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class OrionVoicePreparationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun includedModelCanBeInstalledWithoutOpeningTheFilePicker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(OrionVoiceModel.bundled(context))
        assumeFalse(OrionVoiceModel.present(context))

        compose.openOrionDestination("Voz")
        compose.onNodeWithText("Instalar modelo local").assertIsDisplayed()
        compose.onNodeWithTag("orion_voice_install").performClick()
        compose.waitUntil(25_000) { compose.onAllNodesWithTag("orion_voice_talk").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Modelo de voz instalado.").assertIsDisplayed()
        compose.onNodeWithTag("orion_voice_talk").assertIsEnabled()
    }
}
