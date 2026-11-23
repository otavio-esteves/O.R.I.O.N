package network.orion.app.chat

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.app.MainActivity
import network.orion.app.OrionApplication
import network.orion.app.ai.OrionCortexRunResult
import network.orion.app.ai.OrionS21CortexPreview
import network.orion.app.ai.OrionS21ModelStatus
import network.orion.core.orchestrator.chat.OrionChatContext
import network.orion.core.orchestrator.chat.OrionChatResponse
import network.orion.core.orchestrator.chat.OrionChatTerminal
import network.orion.core.orchestrator.chat.OrionStructuredResponse
import network.orion.core.time.AndroidOrionClock
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit one-turn smoke test using the installed model, runtime owner and device resource checks. */
class OrionS21PreviewChatTest {
    @Test fun installedPreviewGeneratesValidatedPortugueseText() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("orionS21PreviewChat") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as OrionApplication
        check(OrionS21CortexPreview.enabled)
        val runtime = checkNotNull(app.mainRuntime)
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                withTimeout(30_000) {
                    runtime.health.first { it.core.readiness.isStartupBarrierOpen }
                    check(runtime.previewModel.first { it != OrionS21ModelStatus.CHECKING } == OrionS21ModelStatus.READY)
                }
                val owner = checkNotNull(runtime.cortexRuntime())
                var observed: OrionCortexRunResult? = null
                val adapter = OrionChatCortexAdapter(AndroidOrionClock(app.contentResolver)) {
                    OrionOwnedChatRuntime(owner) { result ->
                        observed = result
                        runtime.recordPreviewInference(result)
                    }
                }
                val model = checkNotNull(adapter.configuration())
                val prompt = checkNotNull(OrionChatContext.build("Diga apenas olá.", emptyList(), model)).prompt
                val correlation = UUID.randomUUID()
                val output = StringBuffer()
                val terminal = adapter.generate(model, prompt, correlation.toString()) { output.append(it) }
                check(terminal == OrionChatTerminal.SUCCEEDED) {
                    val reason = when (val result = observed) {
                        is OrionCortexRunResult.Unavailable -> result.reason
                        is OrionCortexRunResult.Completed -> result.status.failure
                        null -> null
                    }
                    "Chat terminal=$terminal, reason=$reason"
                }
                val reply = OrionStructuredResponse.parse(output.toString(), UUID.randomUUID(), correlation)
                check(reply is OrionChatResponse.Message && reply.text.contains("olá", ignoreCase = true))
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("environment", "PHYSICAL_DEVICE")
                    putBoolean("validatedReply", true)
                })
            }
        }
    }
}
