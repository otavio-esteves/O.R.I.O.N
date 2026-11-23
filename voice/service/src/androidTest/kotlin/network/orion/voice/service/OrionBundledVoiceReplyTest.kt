package network.orion.voice.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionBundledVoiceReplyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun packagedBatteryAndTaskCountsAreSeekable() {
        for (number in 0..100) {
            checkAsset("Bateria em $number por cento.")
            checkAsset("Você tem $number tarefas abertas.")
        }
        checkAsset("Revise e confirme o pedido na tela Tarefas.")
        checkAsset("A resposta anterior ficou indisponível. Você pode repetir a consulta.")
        assertNull(orionBundledVoiceAsset("Você tem 101 tarefas abertas."))
        assertNull(orionBundledVoiceAsset("Qualquer outro texto."))
    }

    @Test fun playsReplyUsingTheBundledAsset() = runBlocking {
        assertTrue(playOrionBundledVoiceReply(context, "Bateria em 42 por cento."))
        assertTrue(playOrionBundledVoiceReply(context, "Revise e confirme o pedido na tela Tarefas."))
    }

    private fun checkAsset(reply: String) {
        val path = orionBundledVoiceAsset(reply)
        assertNotNull(reply, path)
        context.assets.openFd(checkNotNull(path)).use { assertTrue(it.length > 0) }
    }
}
