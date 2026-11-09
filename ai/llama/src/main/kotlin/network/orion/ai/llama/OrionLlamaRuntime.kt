package network.orion.ai.llama

/** Called synchronously on the generation worker, never a native-created thread. */
fun interface OrionLlamaPieceCallback {
    /** UTF-8 token bytes may end midway through a code point. Returning false requests cancellation. */
    fun onPiece(bytes: ByteArray): Boolean
}

enum class OrionLlamaFinish { SUCCEEDED, CANCELLED, TOKEN_LIMIT }

/**
 * Owned exclusively by :ai. Caller obtains fresh governor approval before load/generation.
 * No loading occurs at class initialization. Keep the read-only model FD open through unload.
 * A single operation may run; cancel/close are safe concurrently. No native pointers escape.
 */
class OrionLlamaRuntime : AutoCloseable {
    private val lock = Any()
    private var handle: Long
    private var operating = false

    init {
        System.loadLibrary("orion_llama")
        handle = nativeCreate()
        check(handle != 0L) { "Native runtime unavailable." }
    }

    fun validateModel(modelPath: String): Boolean = operation { id ->
        nativeValidate(id, pathBytes(modelPath))
    }

    /** False means load was cancelled; failure throws with a content-free diagnostic. */
    fun load(modelPath: String, contextTokens: Int, threads: Int): Boolean {
        require(contextTokens in 32..32768 && threads in 1..32)
        return operation { nativeLoad(it, pathBytes(modelPath), contextTokens, threads) }
    }

    fun generate(prompt: String, maxTokens: Int, temperature: Float,
        onPiece: OrionLlamaPieceCallback): OrionLlamaFinish {
        require(prompt.length in 1..MAX_PROMPT_BYTES && maxTokens in 1..32768)
        require(temperature.isFinite() && temperature in 0f..2f)
        val bytes = prompt.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PROMPT_BYTES)
        return operation { id ->
            when (nativeGenerate(id, bytes, maxTokens, temperature, onPiece)) {
                0 -> OrionLlamaFinish.SUCCEEDED
                1 -> OrionLlamaFinish.CANCELLED
                2 -> OrionLlamaFinish.TOKEN_LIMIT
                else -> error("Invalid native completion.")
            }
        }
    }

    fun cancel() = synchronized(lock) {
        if (handle != 0L && operating) nativeCancel(handle)
    }

    fun unload() = operation { nativeUnload(it) }

    override fun close() = synchronized(lock) {
        if (handle != 0L) {
            nativeCancel(handle)
            nativeDestroy(handle)
            handle = 0
        }
    }

    private inline fun <T> operation(block: (Long) -> T): T {
        val id = synchronized(lock) {
            check(handle != 0L) { "Native runtime closed." }
            check(!operating) { "Native runtime busy." }
            nativeResetCancellation(handle)
            operating = true
            handle
        }
        return try { block(id) } finally { synchronized(lock) { operating = false } }
    }

    private fun pathBytes(path: String): ByteArray {
        require(path.startsWith('/') && path.length <= 4096 && '\u0000' !in path)
        return path.toByteArray(Charsets.UTF_8).also { require(it.size <= 4096) }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeResetCancellation(handle: Long)
    private external fun nativeCancel(handle: Long)
    private external fun nativeValidate(handle: Long, path: ByteArray): Boolean
    private external fun nativeLoad(handle: Long, path: ByteArray, contextTokens: Int, threads: Int): Boolean
    private external fun nativeGenerate(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float,
        callback: OrionLlamaPieceCallback): Int
    private external fun nativeUnload(handle: Long)

    companion object { const val MAX_PROMPT_BYTES = 1024 * 1024 }
}
