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
        onPiece: OrionLlamaPieceCallback): OrionLlamaFinish = generateLocal(prompt, maxTokens, temperature, null, onPiece)

    /** Explicit opt-in service port; same runtime/handle and original finish/exception. */
    fun generateWithReceipt(prompt: String, maxTokens: Int, temperature: Float,
        capture: OrionLlamaServiceGenerationCapture, onPiece: OrionLlamaPieceCallback): OrionLlamaFinish =
        generateObserved(prompt, maxTokens, temperature, capture.local, onPiece)

    /** Local diagnostic only. A fresh one-shot capture is required; no IPC or admission changes. */
    internal fun generateObserved(prompt: String, maxTokens: Int, temperature: Float,
        capture: OrionLlamaGenerationCapture, onPiece: OrionLlamaPieceCallback): OrionLlamaFinish {
        capture.claim()
        return generateLocal(prompt, maxTokens, temperature, capture, onPiece)
    }

    private fun generateLocal(prompt: String, maxTokens: Int, temperature: Float,
        capture: OrionLlamaGenerationCapture?, onPiece: OrionLlamaPieceCallback): OrionLlamaFinish {
        var raw: LongArray? = null
        var returnedCode: Int? = null
        try {
            require(prompt.length in 1..MAX_PROMPT_BYTES && maxTokens in 1..32768)
            require(temperature.isFinite() && temperature in 0f..2f)
            val bytes = prompt.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_PROMPT_BYTES)
            // A diagnostic allocation failure cannot prevent the original generation.
            if (capture != null) raw = try { LongArray(ORION_NATIVE_METRIC_SLOTS) } catch (_: Throwable) { null }
            return operation { id ->
                val code = nativeGenerate(id, bytes, maxTokens, temperature, onPiece, raw)
                returnedCode = code
                // Only the observed private JNI representation can contain publication metadata.
                llamaGenerationFinish(code, capture != null)
            }
        } finally {
            // JNI has returned/unwound. Parsing and holder failures never replace its result/exception.
            capture?.complete(raw, returnedCode, maxTokens)
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
        callback: OrionLlamaPieceCallback, metrics: LongArray?): Int
    private external fun nativeUnload(handle: Long)

    companion object { const val MAX_PROMPT_BYTES = 1024 * 1024 }
}
