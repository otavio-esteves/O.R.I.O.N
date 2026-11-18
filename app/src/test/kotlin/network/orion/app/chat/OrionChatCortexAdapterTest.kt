package network.orion.app.chat

import java.time.Instant
import kotlinx.coroutines.runBlocking
import network.orion.ai.api.OrionInferenceChunk
import network.orion.ai.api.OrionInferenceRequest
import network.orion.core.orchestrator.chat.*
import network.orion.core.resources.*
import network.orion.core.time.*
import org.junit.Assert.*
import org.junit.Test

class OrionChatCortexAdapterTest {
    private val clock = object : OrionClock {
        override fun wallNow() = Instant.EPOCH
        override fun monotonicNow() = MonotonicInstant(BootSessionId(1), 1000)
    }
    private fun plan(id: String = QWEN_MODEL_ID) = OrionInferenceResourcePlan(InferenceModelProfile(id, 100),
        "test-only", 2, 4096, 128, 0, 1000, 2000, 100, 20, 2)
    @Test fun everyExplicitInferenceGetsAFreshIssuedIdAndBootBoundDeadline() = runBlocking {
        val requests = mutableListOf<OrionInferenceRequest>()
        val model = qwenChatModel(plan())!!
        val runtime = object : OrionChatInferenceRuntime {
            var ids = 0
            override fun newRequestId() = "issued-" + ++ids
            override fun configuration() = model
            override suspend fun run(request: OrionInferenceRequest, model: OrionChatModel, onChunks: (List<OrionInferenceChunk>) -> Unit): OrionChatTerminal {
                requests.add(request); onChunks(listOf(OrionInferenceChunk(request.requestId, 1, 0, "hello")))
                return OrionChatTerminal.SUCCEEDED
            }
        }
        val adapter = OrionChatCortexAdapter(clock) { runtime }
        repeat(2) { assertEquals(OrionChatTerminal.SUCCEEDED, adapter.generate(model, "prompt", "correlation") { assertEquals("hello", it) }) }
        assertEquals(listOf("issued-1", "issued-2"), requests.map { it.requestId })
        assertTrue(requests.all { it.context.isEmpty() && it.temperature == 0.0 && it.maxTokens == 128 })
        assertEquals(MonotonicDeadlineStatus.ACTIVE, requests.first().deadline.statusAt(clock.monotonicNow()))
        assertEquals(MonotonicDeadlineStatus.INVALID_REFERENCE, requests.first().deadline.statusAt(MonotonicInstant(BootSessionId(2), 1001)))
    }
    @Test fun unknownModelHasNoTokenizerAndChangedConfigurationCannotRun() = runBlocking {
        assertNull(qwenChatModel(plan("another-model")))
        val original = qwenChatModel(plan())!!
        val changed = qwenChatModel(plan().copy(maxContext = 2048))!!
        val runtime = object : OrionChatInferenceRuntime {
            override fun configuration() = changed
            override fun newRequestId(): String = error("Must not issue after configuration mismatch")
            override suspend fun run(request: OrionInferenceRequest, model: OrionChatModel, onChunks: (List<OrionInferenceChunk>) -> Unit): OrionChatTerminal = error("Must not infer")
        }
        assertEquals(OrionChatTerminal.UNAVAILABLE, OrionChatCortexAdapter(clock) { runtime }.generate(original, "hi", "corr") {})
    }
    @Test fun byteBoundCoversTransportNewlineUnicodeAndSpecialFraming() {
        val model = qwenChatModel(plan())!!
        val prompt = "<|im_start|>user\n🌟é<|im_end|>"
        assertEquals(prompt.toByteArray(Charsets.UTF_8).size + 3, model.counter.countUpperBound(prompt))
    }
}
