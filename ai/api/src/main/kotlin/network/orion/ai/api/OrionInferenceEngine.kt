package network.orion.ai.api

import java.io.FileDescriptor

/** Disposable inference mechanics only. Implementations never execute Skills or open business storage. */
interface OrionInferenceEngine : AutoCloseable {
    fun load(model: FileDescriptor, contextSize: Int, threads: Int, shouldCancel: () -> Boolean)
    fun infer(prompt: String, maxTokens: Int, temperature: Float, emit: (String) -> Boolean, shouldCancel: () -> Boolean)
    fun unload()
    override fun close() = unload()
}

fun interface OrionInferenceEngineFactory { fun create(): OrionInferenceEngine }

/** Application construction stays lightweight; the service creates the engine only for an admitted load. */
interface OrionCortexEngineProvider { val orionInferenceEngineFactory: OrionInferenceEngineFactory }
