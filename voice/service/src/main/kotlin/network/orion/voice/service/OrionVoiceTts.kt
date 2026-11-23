package network.orion.voice.service

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Plays bundled Portuguese replies and uses local Android synthesis for other text. */
internal suspend fun speakOrionVoiceReply(context: Context, text: String, sessionId: String) {
    if (playOrionBundledVoiceReply(context, text)) return

    val initialized = CompletableDeferred<Int>()
    val tts = TextToSpeech(context) { initialized.complete(it) }
    try {
        if (withTimeoutOrNull(2_000) { initialized.await() } != TextToSpeech.SUCCESS) return
        val voice = tts.voices?.filter { it.locale.language == "pt" && !it.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            ?.sortedByDescending { it.locale.country == "BR" }?.firstOrNull() ?: return
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) return
        val done = CompletableDeferred<Unit>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == sessionId) done.complete(Unit) }
            @Deprecated("Required by Android's progress listener.")
            override fun onError(utteranceId: String?) = onDone(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = onDone(utteranceId)
        })
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), sessionId) == TextToSpeech.SUCCESS) {
            withTimeoutOrNull(7_000) { done.await() }
        }
    } finally {
        tts.stop()
        tts.shutdown()
    }
}
