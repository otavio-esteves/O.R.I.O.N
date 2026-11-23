package network.orion.app

import android.content.ContextWrapper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import network.orion.voice.service.OrionVoiceModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun packagedRecognitionUpgradesAPreviousTinyInstallation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = Files.createTempDirectory(context.cacheDir.toPath(), "voice-upgrade").toFile()
        val install = object : ContextWrapper(context) {
            override fun getCacheDir() = sandbox
            override fun getNoBackupFilesDir() = sandbox
        }
        try {
            assumeTrue(OrionVoiceModel.bundledUpgradeAvailable(install))
            val previous = File(sandbox, "orion_voice_tiny_q5_1.bin")
            RandomAccessFile(previous, "rw").use { it.setLength(32_152_673L) }
            assertTrue(OrionVoiceModel.bundledUpgradeAvailable(install))

            OrionVoiceModel.upgradeBundled(install)

            assertFalse(OrionVoiceModel.bundledUpgradeAvailable(install))
            assertTrue(previous.isFile)
        } finally { sandbox.deleteRecursively() }
    }
}
