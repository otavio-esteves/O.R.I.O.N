package network.orion.app.chat

import network.orion.ai.api.*
import network.orion.app.ai.OrionCortexAdmissionBlock
import network.orion.app.ai.OrionCortexRuntimeOwner
import network.orion.app.ai.OrionCortexRunResult
import network.orion.app.ai.S21_CORTEX_PREVIEW_TIER
import network.orion.core.context.OrionTokenCounter
import network.orion.core.orchestrator.chat.*
import network.orion.core.resources.OrionInferenceResourcePlan
import network.orion.core.resources.OrionResourceBlock
import network.orion.core.resources.OrionRestartBlock
import network.orion.core.time.OrionClock

/** Internal technical port: test fixtures may use the existing client without configuring product profiles. */
internal interface OrionChatInferenceRuntime {
    fun configuration(): OrionChatModel?
    suspend fun awaitReadyForGeneration() = Unit
    fun newRequestId(): String
    suspend fun run(request: OrionInferenceRequest, model: OrionChatModel, onChunks: (List<OrionInferenceChunk>) -> Unit): OrionChatGeneration
}

/** The single pinned Qwen2 byte-BPE vocabulary has at most one token per UTF-8 byte, plus BOS/EOS.
 * This conservative bound includes rendered chat framing and the client transport newline. It is not a generic chars/token estimate.
 * Unknown vocabularies have no counter and fail closed until reviewed; native checks actual tokens too.
 */
internal fun qwenChatModel(plan: OrionInferenceResourcePlan): OrionChatModel? {
    if (plan.model.modelId != QWEN_MODEL_ID || plan.maxContext <= 1) return null
    val promptStyle = if (plan.tier == S21_CORTEX_PREVIEW_TIER) OrionChatPromptStyle.PLAIN_TEXT else OrionChatPromptStyle.STRUCTURED
    return OrionChatModel(plan.model.modelId, plan.tier, plan.maxContext, minOf(plan.maxTokens, 256), plan.maxThreads,
        OrionTokenCounter { prompt -> ("\n" + prompt).toByteArray(Charsets.UTF_8).size + 2 }, promptStyle)
}
internal const val QWEN_MODEL_ID = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"

internal class OrionOwnedChatRuntime(private val owner: OrionCortexRuntimeOwner,
    private val observeResult: (OrionCortexRunResult) -> Unit = {}) : OrionChatInferenceRuntime {
    override fun configuration() = owner.chatResourcePlan()?.let(::qwenChatModel)
    override suspend fun awaitReadyForGeneration() = owner.awaitPreviewExitCooldown()
    override fun newRequestId() = owner.newRequestId()
    override suspend fun run(request: OrionInferenceRequest, model: OrionChatModel, onChunks: (List<OrionInferenceChunk>) -> Unit): OrionChatGeneration =
        owner.run(model.modelId, request, model.maxContextTokens, model.threads, onChunks).also(observeResult).chatGeneration()
}

/** Keeps all load/inference authorization and cancellation in the existing MAIN Cortex owner/client. */
internal class OrionChatCortexAdapter(private val clock: OrionClock,
    private val runtime: suspend () -> OrionChatInferenceRuntime?) : OrionChatCortex {
    override suspend fun configuration() = runtime()?.configuration()
    override suspend fun generate(model: OrionChatModel, prompt: String, correlationId: String, onDelta: (String) -> Unit): OrionChatGeneration {
        val unavailable = OrionChatGeneration(OrionChatTerminal.UNAVAILABLE)
        val selected = runtime() ?: return unavailable
        selected.awaitReadyForGeneration()
        val current = selected.configuration() ?: return unavailable
        if (current.modelId != model.modelId || current.resourceProfile != model.resourceProfile ||
            current.maxContextTokens != model.maxContextTokens || current.reservedOutputTokens != model.reservedOutputTokens ||
            current.threads != model.threads || current.promptStyle != model.promptStyle) return unavailable
        val request = OrionInferenceRequest(selected.newRequestId(), 1, model.modelId, prompt, "", model.reservedOutputTokens,
            0.0, OrionInferencePriority.INTERACTIVE, clock.monotonicNow().deadlineAfter(120_000), correlationId)
        return selected.run(request, model) { chunks -> chunks.forEach { chunk ->
            require(chunk.toolDelta == null) // This schema travels in the native text stream, never a second action channel.
            chunk.textDelta?.let(onDelta)
        } }
    }
}

internal fun OrionCortexRunResult.chatGeneration(): OrionChatGeneration = when (this) {
    is OrionCortexRunResult.Unavailable -> OrionChatGeneration(OrionChatTerminal.UNAVAILABLE)
    is OrionCortexRunResult.Completed -> OrionChatGeneration(status.terminal.chatTerminal(), when (val block = admissionBlock) {
        is OrionCortexAdmissionBlock.Resources -> when (block.reason) {
            OrionResourceBlock.BATTERY -> OrionChatBlock.BATTERY
            OrionResourceBlock.POWER_SAVER -> OrionChatBlock.POWER_SAVER
            OrionResourceBlock.THERMAL -> OrionChatBlock.THERMAL
            OrionResourceBlock.MEMORY_PRESSURE, OrionResourceBlock.MEMORY_BUDGET -> OrionChatBlock.MEMORY
            OrionResourceBlock.BACKGROUND -> OrionChatBlock.BACKGROUND
            OrionResourceBlock.MEMORY_EXIT, OrionResourceBlock.RESOURCE_EXIT -> OrionChatBlock.COOLDOWN
            else -> OrionChatBlock.RESOURCES_UNVERIFIED
        }
        is OrionCortexAdmissionBlock.Restart -> when (block.reason) {
            OrionRestartBlock.COOLDOWN -> OrionChatBlock.COOLDOWN
            OrionRestartBlock.FALLBACK_EXHAUSTED -> OrionChatBlock.FALLBACK_EXHAUSTED
            else -> OrionChatBlock.RESOURCES_UNVERIFIED
        }
        null -> null
    })
}

internal fun OrionInferenceTerminal?.chatTerminal(): OrionChatTerminal = when (this) {
    OrionInferenceTerminal.SUCCEEDED -> OrionChatTerminal.SUCCEEDED
    OrionInferenceTerminal.CANCELLED -> OrionChatTerminal.CANCELLED
    OrionInferenceTerminal.TIMED_OUT -> OrionChatTerminal.TIMED_OUT
    OrionInferenceTerminal.PROCESS_DIED -> OrionChatTerminal.PROCESS_DIED
    else -> OrionChatTerminal.FAILED
}
