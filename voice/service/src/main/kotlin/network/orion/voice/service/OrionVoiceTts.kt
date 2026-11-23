package network.orion.voice.service

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Plays bundled Portuguese replies and uses local Android synthesis for other text. */
internal suspend fun speakOrionPlatformVoiceReply(context: Context, text: String, sessionId: String) {
    if (playOrionBundledVoiceReply(context, text)) return
    val engines = context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
        .map { it.serviceInfo.packageName }.distinct()
    for (engine in (listOf<String?>(null) + engines).take(3)) {
        val handled = try { speakWithLocalEngine(context, text, sessionId, engine) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { false }
        if (handled) return
    }
}

private suspend fun speakWithLocalEngine(context: Context, text: String, sessionId: String, engine: String?): Boolean {
    val initialized = CompletableDeferred<Int>()
    val tts = TextToSpeech(context, { initialized.complete(it) }, engine)
    try {
        if (withTimeoutOrNull(2_000) { initialized.await() } != TextToSpeech.SUCCESS) return false
        val voice = tts.voices?.filter { it.locale.language == "pt" && !it.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            ?.sortedByDescending { it.locale.country == "BR" }?.firstOrNull() ?: return false
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) return false
        tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        val started = java.util.concurrent.atomic.AtomicBoolean()
        val done = CompletableDeferred<Boolean>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { if (utteranceId == sessionId) started.set(true) }
            override fun onDone(utteranceId: String?) { if (utteranceId == sessionId) done.complete(true) }
            @Deprecated("Required by Android's progress listener.")
            override fun onError(utteranceId: String?) { if (utteranceId == sessionId) done.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
        })
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), sessionId) != TextToSpeech.SUCCESS) return false
        return withTimeoutOrNull(15_000) { done.await() } == true || started.get()
    } finally {
        tts.stop()
        tts.shutdown()
    }
}
