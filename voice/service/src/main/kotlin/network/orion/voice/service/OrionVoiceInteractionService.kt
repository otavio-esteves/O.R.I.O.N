package network.orion.voice.service

import android.content.Intent
import android.os.RemoteException
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Kept alive by Android in :voice. Audio work happens inside the on-demand session. */
class OrionVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        try {
            setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
            OrionAmbientCaptureQualificationService.startIfPackaged(this)
        } catch (_: SecurityException) {
            // The framework may have switched assistants while readiness was being delivered.
        }
    }

    override fun onShutdown() {
        OrionAmbientCaptureQualificationService.stopIfPackaged(this)
        super.onShutdown()
    }
}

/** Android requires a recognitionService name in assistant metadata. Capture uses the session UI. */
class OrionAssistantRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        try { listener.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: RemoteException) { }
    }

    override fun onCancel(listener: Callback) = Unit

    override fun onStopListening(listener: Callback) = Unit
}
