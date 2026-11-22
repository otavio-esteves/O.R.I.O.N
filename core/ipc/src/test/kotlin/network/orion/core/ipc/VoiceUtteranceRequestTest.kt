package network.orion.core.ipc

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceUtteranceRequestTest {
    private fun utterance(text: String, isFinal: Boolean = true, schema: Int = 1, locale: String = "pt-BR") =
        VoiceUtteranceRequest(IpcSchemaVersion(schema), UUID.randomUUID(), UUID.randomUUID(), text,
            isFinal, locale, Instant.EPOCH, OrionVoiceSource.PUSH_TO_TALK, 0, UUID.randomUUID())

    @Test fun onlyFinalTranscriptIsEligibleAndTextStaysOutOfDiagnostics() {
        val text = "transcrição privada"
        assertFalse(utterance(text, isFinal = false).eligibleForCommandAdmission)
        val request = utterance(text)
        assertTrue(request.eligibleForCommandAdmission)
        assertFalse(request.toString().contains(text))
    }

    @Test fun rejectsOversizedOrMalformedInputAndUnknownSchema() {
        assertThrows(IllegalArgumentException::class.java) { utterance("ã".repeat(8193)) }
        assertThrows(IllegalArgumentException::class.java) { utterance("\uD800") }
        assertThrows(IllegalArgumentException::class.java) { utterance("texto", schema = 2) }
        assertThrows(IllegalArgumentException::class.java) { utterance("texto", locale = "pt_BR") }
    }

    @Test fun responseRequiresConsistentTerminalStateAndOpaqueDiagnostics() {
        val id = UUID.randomUUID()
        val version = IpcSchemaVersion(1)
        val response = VoiceCoreResponse(version, id, 0, OrionVoiceResponseKind.TEXT_DELTA, "texto privado",
            false, null, null)
        assertFalse(response.toString().contains("texto privado"))
        val space = VoiceCoreResponse(version, id, 1, OrionVoiceResponseKind.TEXT_DELTA, " ", false, null, null)
        assertTrue(space.textDelta == " ")
        assertThrows(IllegalArgumentException::class.java) {
            VoiceCoreResponse(version, id, 1, OrionVoiceResponseKind.TERMINAL, null, false, null, null)
        }
    }
}
