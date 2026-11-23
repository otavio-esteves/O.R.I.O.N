package network.orion.app.voice

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import network.orion.core.ipc.IpcSchemaVersion
import network.orion.core.ipc.OrionVoiceSource
import network.orion.core.ipc.VoiceUtteranceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrionVoiceTranscriptPreviewTest {
    @Test fun showsOnlyTheFirstFinalTranscriptForTheWatchedSession() {
        val preview = OrionVoiceTranscriptPreview()
        val watched = UUID.randomUUID()
        val output = MutableStateFlow<String?>(null)
        preview.watch(watched, output)
        preview.publish(request(UUID.randomUUID(), "Outra sessão"))
        preview.publish(request(watched, "Parcial", isFinal = false))
        assertNull(output.value)

        preview.publish(request(watched, "Como está a bateria?"))
        preview.publish(request(watched, "Outra transcrição"))

        assertEquals("Como está a bateria?", output.value)
    }

    @Test fun stoppedAndReplacedSessionsCannotPublish() {
        val preview = OrionVoiceTranscriptPreview()
        val old = UUID.randomUUID()
        val current = UUID.randomUUID()
        val oldOutput = MutableStateFlow<String?>(null)
        val currentOutput = MutableStateFlow<String?>(null)
        preview.watch(old, oldOutput)
        preview.watch(current, currentOutput)
        preview.stop(old)
        preview.publish(request(old, "Resposta atrasada"))
        preview.publish(request(current, "Tudo bem?"))
        assertNull(oldOutput.value)
        assertEquals("Tudo bem?", currentOutput.value)

        preview.stop(current)
        currentOutput.value = null
        preview.publish(request(current, "Sessão encerrada"))
        assertNull(currentOutput.value)
    }

    private fun request(session: UUID, text: String, isFinal: Boolean = true) = VoiceUtteranceRequest(
        IpcSchemaVersion(1), UUID.randomUUID(), session, text, isFinal, "pt-BR", Instant.EPOCH,
        OrionVoiceSource.PUSH_TO_TALK, 0, UUID.randomUUID(),
    )
}
