package network.orion.ai.llama

import android.app.Application
import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import network.orion.ai.api.OrionInferenceEngine

/** The only owner of native handles. Construction/bootstrap never loads JNI or a model. */
class OrionLlamaEngine : OrionInferenceEngine {
    private var handle = 0L
    @Synchronized override fun load(model: FileDescriptor, contextSize: Int, threads: Int, shouldCancel: () -> Boolean) {
        check(Application.getProcessName().endsWith(":ai")) { "Native inference belongs to AI." }
        require(contextSize in 32..131072 && threads in 1..64)
        unload()
        if (shouldCancel()) return
        ensureLibrary()
        ParcelFileDescriptor.dup(model).use { duplicate ->
            val created = nativeLoad(duplicate.fd, contextSize, threads, OrionNativeCallbacks(shouldCancel) { true })
            if (shouldCancel()) { if (created != 0L) nativeDestroy(created); return }
            check(created != 0L) { "Cortex model loading failed." }
            handle = created
        }
    }
    @Synchronized override fun infer(prompt: String, maxTokens: Int, temperature: Float,
        emit: (String) -> Boolean, shouldCancel: () -> Boolean) {
        check(handle != 0L) { "No Cortex model loaded." }
        require(maxTokens in 1..131072 && temperature.isFinite() && temperature in 0f..2f)
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(prompt))
        require(encoded.remaining() <= 128 * 1024)
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        val decoder = OrionUtf8Decoder()
        var stopped = false
        try {
            nativeInfer(handle, bytes, maxTokens, temperature, OrionNativeCallbacks(shouldCancel) { piece ->
                val text = decoder.append(piece)
                (!shouldCancel() && (text.isEmpty() || emit(text))).also { if (!it) stopped = true }
            })
            if (!stopped && !shouldCancel()) decoder.append(byteArrayOf(), end = true).takeIf { it.isNotEmpty() }?.let(emit)
        } finally { bytes.fill(0) }
    }
    @Synchronized override fun unload() { if (handle != 0L) { val previous = handle; handle = 0; nativeDestroy(previous) } }
    private external fun nativeLoad(fd: Int, context: Int, threads: Int, callbacks: OrionNativeCallbacks): Long
    private external fun nativeInfer(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, callbacks: OrionNativeCallbacks)
    private external fun nativeDestroy(handle: Long)
    private companion object {
        @Volatile var loaded = false
        @Synchronized fun ensureLibrary() { if (!loaded) { System.loadLibrary("orion_llama"); loaded = true } }
    }
}

/** JNI pins this short-lived object, never an Activity or the Application. */
internal class OrionNativeCallbacks(private val cancelled: () -> Boolean, private val sink: (ByteArray) -> Boolean) {
    fun shouldCancel(): Boolean = cancelled()
    fun emit(bytes: ByteArray): Boolean = sink(bytes)
}
