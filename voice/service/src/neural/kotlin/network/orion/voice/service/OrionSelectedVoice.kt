package network.orion.voice.service

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

private const val KOKORO_ASSETS = "orion_voice/kokoro"
private const val ORION_SPEAKER = 43 // pm_alex, the locutor used for the bundled Portuguese replies.
private const val SAMPLE_RATE = 24_000
private val markup = Regex("[*`_#]+")
private val whitespace = Regex("\\s+")
private val bullet = Regex("^[-•]\\s+")

internal object OrionTtsNative {
    init { System.loadLibrary("orion_whisper") }
    external fun beginPrivateSynthesis(): Int
    external fun endPrivateSynthesis(saved: Int)
}

@Suppress("UNUSED_PARAMETER")
internal suspend fun speakOrionVoiceReply(context: Context, text: String, sessionId: String) {
    val spokenText = text.lineSequence().map { line ->
        line.replace(markup, "").trim().replaceFirst(bullet, "").replace(whitespace, " ")
    }.filter { it.any(Char::isLetterOrDigit) }.joinToString(" ") { phrase ->
        if (phrase.last() in ".!?:;…") phrase else "$phrase."
    }.let { if (it.startsWith("Bateria em ")) "A bateria está em " + it.removePrefix("Bateria em ") else it }
    if (spokenText.isEmpty()) return
    val bounded = if (spokenText.length <= 600) spokenText else spokenText.take(600).substringBeforeLast(' ', spokenText.take(600))
    val pcm = try { synthesizeOrionKokoro(context, bounded) }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    catch (_: LinkageError) { null }
    if (pcm == null) playOrionBundledVoiceReply(context, text)
    else try { playSpeech(pcm) } finally { pcm.fill(0) }
}

internal suspend fun synthesizeOrionKokoro(context: Context, text: String): ShortArray? = withContext(Dispatchers.IO) {
    val reservation = OrionVoiceMemoryEndpoint.acquireHeavy(context) ?: return@withContext null
    val watchdog = Handler(Looper.getMainLooper())
    val abort = Runnable {
        if (Application.getProcessName() == "${context.packageName}:voice_session") Process.killProcess(Process.myPid())
    }
    watchdog.postDelayed(abort, 25_000)
    try {
        val owner = currentCoroutineContext()
        owner.ensureActive()
        val pcm = renderOrionKokoro(context, text)
        if (!owner.isActive) pcm?.fill(0)
        owner.ensureActive()
        pcm
    } finally {
        watchdog.removeCallbacks(abort)
        reservation.close()
    }
}

private fun renderOrionKokoro(context: Context, text: String): ShortArray? {
    val pronunciation = installPronunciationData(context)
    val savedStderr = OrionTtsNative.beginPrivateSynthesis()
    if (savedStderr == -1) return null
    try {
        val engine = OfflineTts(context.assets, OfflineTtsConfig(model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(
                model = "$KOKORO_ASSETS/model.int8.onnx", voices = "$KOKORO_ASSETS/voices.bin",
                tokens = "$KOKORO_ASSETS/tokens.txt", dataDir = pronunciation.absolutePath, lang = "pt-br",
            ), numThreads = 2,
        ), silenceScale = 0.9f))
        try {
            if (engine.numSpeakers() <= ORION_SPEAKER) return null
            // The pinned JNI callback requires a descriptor that Kotlin lambdas do not expose.
            val audio = engine.generate(text, sid = ORION_SPEAKER, speed = 0.96f)
            val samples = audio.samples
            try {
                if (audio.sampleRate != SAMPLE_RATE || !validSpeech(samples)) return null
                return ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort() }
            } finally { samples.fill(0f) }
        } finally { engine.release() }
    } finally {
        OrionTtsNative.endPrivateSynthesis(savedStderr)
    }
}

private fun validSpeech(samples: FloatArray): Boolean {
    if (samples.size !in 1..SAMPLE_RATE * 45) return false
    var peak = 0f
    var clipped = 0
    for (sample in samples) {
        if (!sample.isFinite()) return false
        val magnitude = abs(sample)
        peak = maxOf(peak, magnitude)
        if (magnitude >= 0.99f) clipped++
    }
    return peak > 0.02f && clipped < samples.size / 20
}

private fun installPronunciationData(context: Context): File {
    val target = File(context.noBackupFilesDir, "orion_kokoro_pronunciation_v1")
    if (target.isDirectory) return target
    val staging = File(context.noBackupFilesDir, "orion_kokoro_pronunciation_v1.part")
    staging.deleteRecursively()
    fun copy(asset: String, destination: File) {
        val entries = context.assets.list(asset).orEmpty()
        if (entries.isEmpty()) {
            context.assets.open(asset).use { input -> destination.outputStream().use { input.copyTo(it) } }
        } else {
            check(destination.mkdirs())
            entries.forEach { copy("$asset/$it", File(destination, it)) }
        }
    }
    try {
        copy("$KOKORO_ASSETS/espeak-ng-data", staging)
        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return target
    } finally { staging.deleteRecursively() }
}

private suspend fun playSpeech(pcm: ShortArray) = withContext(Dispatchers.IO) {
    val player = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
    try {
        if (player.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) != pcm.size) return@withContext
        player.play()
        withTimeoutOrNull(pcm.size * 1_000L / SAMPLE_RATE + 2_000) {
            while (player.playbackHeadPosition < pcm.size) delay(25)
        }
    } finally { player.release() }
}
