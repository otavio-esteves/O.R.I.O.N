package network.orion.app.chat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import network.orion.core.ipc.IpcSchemaVersion
import network.orion.core.ipc.OrionVoiceSource
import network.orion.core.ipc.OrionVoiceTerminalState
import network.orion.core.ipc.VoiceUtteranceRequest
import network.orion.core.time.AndroidOrionClock
import network.orion.voice.ipc.OrionBoundVoiceCoreClient
import network.orion.voice.ipc.OrionVoiceAdmission
import network.orion.voice.ipc.VoiceCoreConnectionState
import network.orion.voice.service.OrionVoiceControlService
import network.orion.voice.service.OrionVoiceSessionService
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
                val greeting = answer("Tudo bem?", history, adapter, model) { observed }
                check(greeting != second)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("environment", "PHYSICAL_DEVICE")
                    putInt("validatedReplies", 3)
                })
            }
        }
    }

    @Test fun finalVoiceRequestReachesTheInstalledCortexAndBatteryFastPath() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("orionS21PreviewVoice") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as OrionApplication
        check(OrionS21CortexPreview.enabled)
        val runtime = checkNotNull(app.mainRuntime)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            runBlocking(Dispatchers.IO) {
                val clock = AndroidOrionClock(app.contentResolver)
                withTimeout(30_000) {
                    runtime.health.first { it.core.readiness.isStartupBarrierOpen }
                    check(runtime.previewModel.first { it != OrionS21ModelStatus.CHECKING } == OrionS21ModelStatus.READY)
                }
                val client = OrionBoundVoiceCoreClient(app)
                val audioRoles = listOf(OrionVoiceControlService::class.java, OrionVoiceSessionService::class.java)
                val connected = CountDownLatch(audioRoles.size)
                val bindings = mutableListOf<ServiceConnection>()
                suspend fun voiceReply(text: String): String = withTimeout(190_000) {
                    val id = UUID.randomUUID()
                    val session = UUID.randomUUID()
                    val transcript = MutableStateFlow<String?>(null)
                    val request = VoiceUtteranceRequest(IpcSchemaVersion(1), id, session, text,
                        true, "pt-BR", clock.wallNow(), OrionVoiceSource.PUSH_TO_TALK, 0, UUID.randomUUID())
                    runtime.voiceTranscriptPreview.watch(session, transcript)
                    try {
                        check(client.submitUtterance(request) == OrionVoiceAdmission.ACCEPTED)
                        check(transcript.value == text)
                    } finally { runtime.voiceTranscriptPreview.stop(session) }
                    var sequence = -1L
                    var reply: String? = null
                    while (true) {
                        val result = client.status(id, sequence)
                        if (result != null) {
                            sequence = result.sequence
                            if (result.textDelta != null) reply = result.textDelta
                            if (result.terminalState != null) {
                                check(result.terminalState == OrionVoiceTerminalState.SUCCEEDED)
                                return@withTimeout checkNotNull(reply)
                            }
                        }
                        delay(250)
                    }
                    @Suppress("UNREACHABLE_CODE") error("No terminal voice response.")
                }
                try {
                    for (role in audioRoles) {
                        val binding = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName, service: IBinder) = connected.countDown()
                            override fun onServiceDisconnected(name: ComponentName) = Unit
                        }
                        check(app.bindService(Intent(app, role), binding, Context.BIND_AUTO_CREATE))
                        bindings += binding
                    }
                    check(connected.await(10, TimeUnit.SECONDS))
                    check(client.connect() == VoiceCoreConnectionState.CONNECTED)
                    check(Regex("Bateria em [0-9]{1,3} por cento\\.").matches(voiceReply("Qual a carga da bateria?")))
                    val reply = voiceReply("Qual é o contrário de quente?")
                    check(reply.contains("frio", ignoreCase = true)) {
                        OrionS21CortexPreview.notice.value ?: "The free voice request did not produce the expected local answer."
                    }
                    OrionS21CortexPreview.resourceFacts.drain()
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("environment", "PHYSICAL_DEVICE")
                        putInt("validatedVoiceReplies", 2)
                    })
                } finally {
                    client.disconnect()
                    bindings.forEach(app::unbindService)
                }
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
