package network.orion.app.chat

import java.time.Instant
import kotlinx.coroutines.runBlocking
import network.orion.ai.api.OrionInferenceChunk
import network.orion.ai.api.OrionInferenceFailure
import network.orion.ai.api.OrionInferenceRequest
import network.orion.ai.api.OrionInferenceStage
import network.orion.ai.api.OrionInferenceStatus
import network.orion.ai.api.OrionInferenceTerminal
import network.orion.app.ai.OrionCortexAdmissionBlock
import network.orion.app.ai.OrionCortexRunResult
import network.orion.core.orchestrator.chat.*
import network.orion.core.resources.*
import network.orion.core.time.*
import org.junit.Assert.*
import org.junit.Test

class OrionChatCortexAdapterTest {
    private var elapsedMillis = 1_000L
    private val clock = object : OrionClock {
        override fun wallNow() = Instant.EPOCH
        override fun monotonicNow() = MonotonicInstant(BootSessionId(1), elapsedMillis)
    }
    private fun plan(id: String = QWEN_MODEL_ID) = OrionInferenceResourcePlan(InferenceModelProfile(id, 100),
        "test-only", 2, 4096, 128, 0, 1000, 2000, 100, 20, 2)
    @Test fun everyExplicitInferenceGetsAFreshIssuedIdAndBootBoundDeadline() = runBlocking {
        val requests = mutableListOf<OrionInferenceRequest>()
        val model = qwenChatModel(plan())!!
        val runtime = object : OrionChatInferenceRuntime {
            var ids = 0
            var waits = 0
            override suspend fun awaitReadyForGeneration() {
                waits++
                elapsedMillis += 60_000
            }
            override fun newRequestId(): String {
                assertEquals(ids + 1, waits)
                return "issued-" + ++ids
            }
            override fun configuration() = model
            override suspend fun run(request: OrionInferenceRequest, model: OrionChatModel, onChunks: (List<OrionInferenceChunk>) -> Unit): OrionChatGeneration {
                requests.add(request); onChunks(listOf(OrionInferenceChunk(request.requestId, 1, 0, "hello")))
                return OrionChatGeneration(OrionChatTerminal.SUCCEEDED)
            }
        }
        val adapter = OrionChatCortexAdapter(clock) { runtime }
        repeat(2) { assertEquals(OrionChatTerminal.SUCCEEDED, adapter.generate(model, "prompt", "correlation") { assertEquals("hello", it) }.terminal) }
        assertEquals(listOf("issued-1", "issued-2"), requests.map { it.requestId })
        assertTrue(requests.all { it.context.isEmpty() && it.temperature == 0.0 && it.maxTokens == 128 })
        assertEquals(MonotonicDeadlineStatus.ACTIVE, requests.first().deadline.statusAt(clock.monotonicNow()))
        assertEquals(MonotonicDeadlineStatus.ACTIVE, requests.last().deadline.statusAt(
            MonotonicInstant(BootSessionId(1), elapsedMillis + 119_999)))
        assertEquals(MonotonicDeadlineStatus.INVALID_REFERENCE, requests.first().deadline.statusAt(MonotonicInstant(BootSessionId(2), 1001)))
    }
    @Test fun unknownModelHasNoTokenizerAndChangedConfigurationCannotRun() = runBlocking {
        assertNull(qwenChatModel(plan("another-model")))
        val original = qwenChatModel(plan())!!
        val changed = qwenChatModel(plan().copy(maxContext = 2048))!!
        val runtime = object : OrionChatInferenceRuntime {
            override fun configuration() = changed
            override fun newRequestId(): String = error("Must not issue after configuration mismatch")
            override suspend fun run(request: OrionInferenceRequest, model: OrionChatModel, onChunks: (List<OrionInferenceChunk>) -> Unit): OrionChatGeneration = error("Must not infer")
        }
        assertEquals(OrionChatTerminal.UNAVAILABLE, OrionChatCortexAdapter(clock) { runtime }.generate(original, "hi", "corr") {}.terminal)
    }

    @Test fun admissionRefusalIsDistinguishedFromATransportFailure() {
        val failed = OrionInferenceStatus(OrionInferenceStage.TERMINAL, OrionInferenceTerminal.FAILED,
            OrionInferenceFailure.TRANSPORT_FAILURE)
        val resource = OrionCortexRunResult.Completed(failed,
            OrionCortexAdmissionBlock.Resources(OrionResourceBlock.BATTERY)).chatGeneration()
        assertEquals(OrionChatBlock.BATTERY, resource.block)
        assertEquals(OrionChatBlock.BATTERY.message, resource.failureMessage)

        val restart = OrionCortexRunResult.Completed(failed,
            OrionCortexAdmissionBlock.Restart(OrionRestartBlock.FALLBACK_EXHAUSTED)).chatGeneration()
        assertEquals(OrionChatBlock.FALLBACK_EXHAUSTED, restart.block)
        assertNull(OrionCortexRunResult.Completed(failed).chatGeneration().block)
    }
    @Test fun constrainedChatProfileKeepsMandatoryRequestAndDropsWholeOptionalHistory() {
        val model = qwenChatModel(plan().copy(maxContext = 1024))!!
        val history = List(12) { network.orion.core.orchestrator.chat.OrionChatTurn(java.util.UUID.randomUUID(), false, "optional context ".repeat(100)) }
        for (request in listOf("Diga apenas olá.", "Crie uma tarefa com o título Comprar pão.", "Conte uma história longa sobre o espaço.")) {
            val context = network.orion.core.orchestrator.chat.OrionChatContext.build(request, history, model)
            assertNotNull("Mandatory request must fit in the constrained profile", context)
            assertTrue(context!!.promptTokenUpperBound + model.reservedOutputTokens <= 1024)
            assertTrue(context.omittedBlocks > 0)
            assertTrue(context.prompt.contains(network.orion.core.orchestrator.chat.OrionStructuredResponse.quote(request)))
        }
    }
    @Test fun byteBoundCoversTransportNewlineUnicodeAndSpecialFraming() {
        val model = qwenChatModel(plan())!!
        val prompt = "<|im_start|>user\n🌟é<|im_end|>"
        assertEquals(prompt.toByteArray(Charsets.UTF_8).size + 3, model.counter.countUpperBound(prompt))
    }
}
