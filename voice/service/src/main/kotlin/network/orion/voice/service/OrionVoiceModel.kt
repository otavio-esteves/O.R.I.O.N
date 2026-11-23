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

/** Allows only the pinned multilingual Whisper models. Model bytes stay in app-private storage. */
object OrionVoiceModel {
    private enum class Model(val label: String, val bytes: Long, val sha256: String) {
        SMALL("small", 190_085_487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
        BASE("base", 59_707_625, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
        TINY("tiny", 32_152_673, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7");

        val asset get() = "orion_voice/ggml-$label-q5_1.bin"
        fun path(context: Context) = File(context.noBackupFilesDir, "orion_voice_${label}_q5_1.bin")
        fun present(context: Context) = path(context).length() == bytes
    }

    fun present(context: Context) = Model.entries.any { it.present(context) }
    fun bundled(context: Context) = bundledModel(context) != null

    private fun bundledModel(context: Context): Model? {
        val assets = context.assets.list("orion_voice").orEmpty()
        return Model.entries.firstOrNull { it.asset.substringAfterLast('/') in assets }
    }

    fun bundledUpgradeAvailable(context: Context): Boolean {
        val bundled = bundledModel(context) ?: return false
        if (bundled == Model.TINY) return false
        return Model.entries.take(bundled.ordinal + 1).none { it.present(context) }
    }

    suspend fun upgradeBundled(context: Context) {
        if (bundledUpgradeAvailable(context)) installBundled(context)
    }

    suspend fun installBundled(context: Context): Boolean {
        val model = bundledModel(context) ?: return false
        return install(context, model) { context.assets.open(model.asset, AssetManager.ACCESS_STREAMING) }
    }

    suspend fun install(context: Context, source: Uri): Boolean = install(context, null) {
        context.contentResolver.openInputStream(source)
    }

    private suspend fun install(context: Context, expected: Model?, open: () -> InputStream?): Boolean = withContext(Dispatchers.IO) {
        val staged = try { File.createTempFile("orion_voice_", ".part", context.cacheDir) }
            catch (_: java.io.IOException) { return@withContext false }
            catch (_: SecurityException) { return@withContext false }
        try {
            val input = open() ?: return@withContext false
            val hash = input.use { verifiedHash(it, staged, expected?.bytes ?: Model.SMALL.bytes) }
            val selected = Model.entries.firstOrNull { it.sha256 == hash && it.bytes == staged.length() }
                ?: return@withContext false
            if (expected != null && selected != expected) return@withContext false
            Files.move(staged.toPath(), selected.path(context).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: java.io.IOException) { false }
          catch (_: SecurityException) { false }
        finally { staged.delete() }
    }

    internal fun verified(context: Context): File? = Model.entries.firstNotNullOfOrNull { model ->
        val file = model.path(context)
        file.takeIf {
            file.isFile && file.length() == model.bytes && file.inputStream().use { verifiedHash(it, null, model.bytes) } == model.sha256
        }
    }

    private fun verifiedHash(input: InputStream, destination: File?, maximumBytes: Long): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        val output = destination?.outputStream()
        var bytes = 0L
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                if (bytes > maximumBytes) return null
                digest.update(buffer, 0, count)
                output?.write(buffer, 0, count)
            }
            output?.fd?.sync()
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally { output?.close() }
    }
}

internal object OrionWhisper {
    init { System.loadLibrary("orion_whisper") }
    external fun transcribe(id: Long, path: String, pcm: ShortArray): ByteArray?
    external fun cancel(id: Long)
}
