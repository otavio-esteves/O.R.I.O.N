package network.orion.app.voice

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import network.orion.core.ipc.VoiceUtteranceRequest

/** Delivers the final transcript only to the foreground screen that started the session. */
internal class OrionVoiceTranscriptPreview {
    private class Registration(
        val sessionId: UUID,
        val output: MutableStateFlow<String?>,
        val reminder: MutableStateFlow<OrionSpokenReminder?>?,
    )
    private var active: Registration? = null

    @Synchronized fun watch(
        sessionId: UUID,
        output: MutableStateFlow<String?>,
        reminder: MutableStateFlow<OrionSpokenReminder?>? = null,
    ) {
        active = Registration(sessionId, output, reminder)
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

    @Synchronized fun watchesReminders(sessionId: UUID): Boolean =
        active?.takeIf { it.sessionId == sessionId }?.reminder != null

    @Synchronized fun publishReminder(sessionId: UUID, request: OrionSpokenReminder): Boolean {
        val output = active?.takeIf { it.sessionId == sessionId }?.reminder ?: return false
        return output.compareAndSet(null, request) || output.value == request
    }
}
