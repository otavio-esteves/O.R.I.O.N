package network.orion.app.voice

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import network.orion.core.ipc.VoiceUtteranceRequest

/** Delivers the final transcript only to the foreground screen that started the session. */
internal class OrionVoiceTranscriptPreview {
    private class Registration(val sessionId: UUID, val output: MutableStateFlow<String?>)
    private var active: Registration? = null

    @Synchronized fun watch(sessionId: UUID, output: MutableStateFlow<String?>) {
        active = Registration(sessionId, output)
    }

    @Synchronized fun publish(request: VoiceUtteranceRequest) {
        val current = active ?: return
        if (request.isFinalTranscript && request.sessionId == current.sessionId) {
            current.output.compareAndSet(null, request.text)
        }
    }

    @Synchronized fun stop(sessionId: UUID) {
        if (active?.sessionId == sessionId) active = null
    }
}
