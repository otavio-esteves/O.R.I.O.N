package network.orion.voice.service

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Supports the pinned multilingual Whisper tiny candidate. Model bytes stay in app-private storage. */
object OrionVoiceModel {
    private const val modelBytes = 32_152_673L
    private const val modelSha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
    private const val modelAsset = "orion_voice/ggml-tiny-q5_1.bin"

    internal fun path(context: Context) = File(context.noBackupFilesDir, "orion_voice_tiny_q5_1.bin")
    fun present(context: Context) = path(context).length() == modelBytes

    fun bundled(context: Context): Boolean = try {
        context.assets.open(modelAsset, AssetManager.ACCESS_STREAMING).use { true }
    } catch (_: java.io.IOException) { false }

    suspend fun installBundled(context: Context): Boolean = install(context) {
        context.assets.open(modelAsset, AssetManager.ACCESS_STREAMING)
    }

    suspend fun install(context: Context, source: Uri): Boolean = install(context) {
        context.contentResolver.openInputStream(source)
    }

    private suspend fun install(context: Context, open: () -> InputStream?): Boolean = withContext(Dispatchers.IO) {
        val target = path(context)
        val staged = try { File.createTempFile("orion_voice_", ".part", context.cacheDir) }
            catch (_: java.io.IOException) { return@withContext false }
            catch (_: SecurityException) { return@withContext false }
        try {
            val input = open() ?: return@withContext false
            val sha256 = input.use { verifiedHash(it, staged) }
            if (sha256 != modelSha256) return@withContext false
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: java.io.IOException) { false }
          catch (_: SecurityException) { false }
        finally { staged.delete() }
    }

    internal fun verified(context: Context): File? = path(context).takeIf { model ->
        model.isFile && model.length() == modelBytes && model.inputStream().use { verifiedHash(it, null) } == modelSha256
    }

    private fun verifiedHash(input: InputStream, destination: File?): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        val output = destination?.outputStream()
        var bytes = 0L
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                if (bytes > modelBytes) return null
                digest.update(buffer, 0, count)
                output?.write(buffer, 0, count)
            }
            output?.fd?.sync()
            return if (bytes == modelBytes) digest.digest().joinToString("") { "%02x".format(it) } else null
        } finally { output?.close() }
    }
}

internal object OrionWhisper {
    init { System.loadLibrary("orion_whisper") }
    external fun transcribe(id: Long, path: String, pcm: ShortArray): ByteArray?
    external fun cancel(id: Long)
}
