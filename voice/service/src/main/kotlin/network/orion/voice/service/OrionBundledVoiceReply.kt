package network.orion.voice.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val VOICE_ASSETS = "orion_voice/pt_br"
private val batteryReply = Regex("Bateria em ([0-9]{1,3}) por cento\\.")
private val taskCountReply = Regex("Você tem ([0-9]{1,3}) tarefas abertas\\.")
private val fixedReplies = mapOf(
    "Revise e confirme o pedido na tela Tarefas." to "confirmation",
    "Bateria indisponível." to "battery_unavailable",
    "Abra o O.R.I.O.N. e tente novamente." to "try_again",
    "Repita o pedido com o título da tarefa." to "task_title",
    "Use voz para criar ou listar tarefas e consultar a bateria." to "voice_help",
    "A resposta anterior ficou indisponível. Você pode repetir a consulta." to "previous_reply_unavailable",
)

internal fun orionBundledVoiceAsset(text: String): String? {
    val clip = fixedReplies[text]
        ?: numberedClip(batteryReply, text, "battery")
        ?: numberedClip(taskCountReply, text, "tasks")
        ?: return null
    return "$VOICE_ASSETS/$clip.mp3"
}

private fun numberedClip(pattern: Regex, text: String, folder: String): String? {
    val number = pattern.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return if (number in 0..100) "$folder/$number" else null
}

internal suspend fun playOrionBundledVoiceReply(context: Context, text: String): Boolean {
    val asset = orionBundledVoiceAsset(text) ?: return false

    return withContext(Dispatchers.Main.immediate) {
        val player = MediaPlayer()
        val finished = CompletableDeferred<Unit>()
        var started = false
        try {
            context.assets.openFd(asset).use {
                player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            player.setOnPreparedListener {
                try {
                    player.start()
                    started = true
                } catch (_: IllegalStateException) {
                    finished.complete(Unit)
                }
            }
            player.setOnCompletionListener { finished.complete(Unit) }
            player.setOnErrorListener { _, _, _ ->
                finished.complete(Unit)
                true
            }
            player.prepareAsync()
            withTimeoutOrNull(7_000) { finished.await() }
            started
        } catch (_: IOException) {
            false
        } catch (_: IllegalStateException) {
            started
        } finally {
            player.release()
        }
    }
}
