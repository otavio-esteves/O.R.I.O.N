package network.orion.app.chat

import android.os.Bundle
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import network.orion.app.MainActivity
import network.orion.app.OrionApplication
import network.orion.app.ai.OrionCortexRunResult
import network.orion.app.ai.OrionS21CortexPreview
import network.orion.app.ai.OrionS21ModelStatus
import network.orion.app.resources.OrionCortexResourceFact
import network.orion.core.orchestrator.chat.OrionChatContext
import network.orion.core.orchestrator.chat.OrionChatModel
import network.orion.core.orchestrator.chat.OrionChatTerminal
import network.orion.core.orchestrator.chat.OrionChatTurn
import network.orion.core.orchestrator.chat.OrionStructuredResponse
import network.orion.core.resources.OrionRestartBlock
import network.orion.core.time.AndroidOrionClock
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs consecutive requests using the installed model, runtime owner and device resource checks. */
class OrionS21PreviewChatTest {
    @Test fun installedPreviewAnswersDifferentQuestionsInTheSameConversation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("orionS21PreviewChat") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as OrionApplication
        check(OrionS21CortexPreview.enabled)
        val runtime = checkNotNull(app.mainRuntime)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            runBlocking {
                withTimeout(30_000) {
                    runtime.health.first { it.core.readiness.isStartupBarrierOpen }
                    check(runtime.previewModel.first { it != OrionS21ModelStatus.CHECKING } == OrionS21ModelStatus.READY)
                }
                val owner = checkNotNull(runtime.cortexRuntime())
                withContext(Dispatchers.IO) {
                    withTimeout(70_000) {
                        while (owner.observePreviewExitHistory()?.block == OrionRestartBlock.COOLDOWN) delay(1_000)
                    }
                }
                var observed: OrionCortexRunResult? = null
                val adapter = OrionChatCortexAdapter(AndroidOrionClock(app.contentResolver)) {
                    OrionOwnedChatRuntime(owner) { result ->
                        observed = result
                        runtime.recordPreviewInference(result)
                    }
                }
                val model = checkNotNull(adapter.configuration())
                val history = mutableListOf<OrionChatTurn>()
                val first = answer("Qual é o contrário de quente?", history, adapter, model) { observed }
                check(first.contains("frio", ignoreCase = true))
                val second = answer("Dê dois exemplos de frutas.", history, adapter, model) { observed }
                check(second != first && Regex("maçã|banana|laranja|uva|mamão|pera|morango|abacaxi", RegexOption.IGNORE_CASE).containsMatchIn(second))
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("environment", "PHYSICAL_DEVICE")
                    putInt("validatedReplies", 2)
                })
            }
        }
    }

    private suspend fun answer(question: String, history: MutableList<OrionChatTurn>, adapter: OrionChatCortexAdapter,
        model: OrionChatModel, observed: () -> OrionCortexRunResult?): String {
        val prompt = checkNotNull(OrionChatContext.build(question, history, model)).prompt
        val correlation = UUID.randomUUID()
        val output = StringBuffer()
        val terminal = adapter.generate(model, prompt, correlation.toString()) { output.append(it) }
        val facts = OrionS21CortexPreview.resourceFacts.drain().facts
        val decisions = facts.filterIsInstance<OrionCortexResourceFact.Governor>()
        check(terminal == OrionChatTerminal.SUCCEEDED) {
            val reason = when (val result = observed()) {
                is OrionCortexRunResult.Unavailable -> result.reason
                is OrionCortexRunResult.Completed -> result.status.failure
                null -> null
            }
            val restart = facts.filterIsInstance<OrionCortexResourceFact.Restart>().lastOrNull()?.decision?.block
            val last = decisions.lastOrNull()
            val snapshot = last?.snapshot
            "Chat terminal=$terminal, reason=$reason, restart=$restart, resource=${last?.decision?.block}, " +
                "thermal=${snapshot?.thermalStatus}, battery=${snapshot?.batteryPercent}, " +
                "foreground=${snapshot?.foreground}, pressure=${snapshot?.memoryPressure}, " +
                "headroom=${last?.decision?.estimatedHeadroomBytes}, limitations=${snapshot?.limitations}"
        }
        check(decisions.count { it.decision.allowedAsReported } >= 2)
        val reply = checkNotNull(OrionStructuredResponse.plainTextMessage(output.toString(), question)) {
            "Expected a validated message for turn ${history.size / 2 + 1}."
        }
        history += OrionChatTurn(UUID.randomUUID(), true, question)
        history += OrionChatTurn(UUID.randomUUID(), false, reply.text)
        return reply.text
    }
}
