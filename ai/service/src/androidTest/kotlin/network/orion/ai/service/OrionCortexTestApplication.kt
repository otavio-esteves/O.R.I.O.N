package network.orion.ai.service

import android.app.Application
import java.io.FileDescriptor
import network.orion.ai.api.*

/** Library instrumentation fixture only. It never ships in the production application. */
class OrionCortexTestApplication : Application(), OrionCortexEngineProvider {
    override val orionInferenceEngineFactory = OrionInferenceEngineFactory {
        object : OrionInferenceEngine {
            override fun load(model: FileDescriptor, contextSize: Int, threads: Int, shouldCancel: () -> Boolean) { }
            override fun infer(prompt: String, maxTokens: Int, temperature: Float, emit: (String) -> Boolean, shouldCancel: () -> Boolean) {
                emit("synthetic")
                if (prompt == "WAIT_FOR_DEATH") while (!shouldCancel()) Thread.sleep(10)
            }
            override fun unload() { }
        }
    }
}
