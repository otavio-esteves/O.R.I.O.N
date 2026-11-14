package network.orion.ai.llama

import java.util.Collections

/** Explicit service-facing observation port. The private JNI frame/decoder remain private. */
enum class OrionLlamaServiceMetric(val key: String) {
    PROMPT_TOKENS("promptTokens"), SAMPLED_TOKENS("sampledTokens"), EOG_TOKENS("eogTokens"),
    CALLBACKS_ATTEMPTED("callbacksAttempted"), CALLBACKS_RETURNED("callbacksReturnedNormally"),
    PREFILL_CALLS("prefillCalls"), PREFILL_SUCCEEDED("prefillSuccessfulCalls"),
    PREFILL_SUBMITTED("prefillSubmittedTokens"), PREFILL_SUCCESSFUL_TOKENS("prefillTokensInSuccessfulCalls"),
    DECODE_CALLS("decodeCalls"), DECODE_SUCCEEDED("decodeSuccessfulCalls"),
    DECODE_SUBMITTED("decodeSubmittedTokens"), DECODE_SUCCESSFUL_TOKENS("decodeTokensInSuccessfulCalls"),
    TOKENIZATION_NANOS("tokenizationApiWallNanos"), PREFILL_NANOS("prefillApiWallNanos"), DECODE_NANOS("decodeApiWallNanos");
}

class OrionLlamaServiceGenerationReceipt internal constructor(
    val finish: OrionLlamaFinish, metrics: Map<OrionLlamaServiceMetric, Long>,
    val completedCallStagesMask: Int, val partialWorkPossible: Boolean,
) {
    val metrics: Map<OrionLlamaServiceMetric, Long> = Collections.unmodifiableMap(HashMap(metrics))
    override fun toString() = "OrionLlamaServiceGenerationReceipt(finish=$finish, stages=$completedCallStagesMask)"
}

/** One invocation only, owned by the calling service worker; no callback or native handle. */
class OrionLlamaServiceGenerationCapture {
    internal val local = OrionLlamaGenerationCapture()

    /** Return-time facts only. Null cannot prove no native work or authorize a retry. */
    fun receipt(): OrionLlamaServiceGenerationReceipt? {
        val observation = local.observation
        if (observation.state != OrionLlamaObservationState.LOCAL_JNI_RETURNED) return null
        return observation.metrics?.let(::serviceGenerationReceipt)
    }
}

internal fun serviceGenerationReceipt(value: OrionLlamaGenerationMetrics): OrionLlamaServiceGenerationReceipt {
    val metrics = mutableMapOf<OrionLlamaServiceMetric, Long>()
    fun put(metric: OrionLlamaServiceMetric, count: Int) { metrics[metric] = count.toLong() }
    value.promptTokens?.let { put(OrionLlamaServiceMetric.PROMPT_TOKENS, it) }
    put(OrionLlamaServiceMetric.SAMPLED_TOKENS, value.sampledTokens)
    put(OrionLlamaServiceMetric.EOG_TOKENS, value.eogSamples)
    put(OrionLlamaServiceMetric.CALLBACKS_ATTEMPTED, value.callbacksAttempted)
    put(OrionLlamaServiceMetric.CALLBACKS_RETURNED, value.callbacksReturnedNormally)
    put(OrionLlamaServiceMetric.PREFILL_CALLS, value.prefill.callsAttempted)
    put(OrionLlamaServiceMetric.PREFILL_SUCCEEDED, value.prefill.callsSucceeded)
    put(OrionLlamaServiceMetric.PREFILL_SUBMITTED, value.prefill.tokensSubmitted)
    put(OrionLlamaServiceMetric.PREFILL_SUCCESSFUL_TOKENS, value.prefill.tokensInSuccessfulCalls)
    put(OrionLlamaServiceMetric.DECODE_CALLS, value.decode.callsAttempted)
    put(OrionLlamaServiceMetric.DECODE_SUCCEEDED, value.decode.callsSucceeded)
    put(OrionLlamaServiceMetric.DECODE_SUBMITTED, value.decode.tokensSubmitted)
    put(OrionLlamaServiceMetric.DECODE_SUCCESSFUL_TOKENS, value.decode.tokensInSuccessfulCalls)
    value.tokenizeApiWallNanos?.let { metrics[OrionLlamaServiceMetric.TOKENIZATION_NANOS] = it }
    value.prefill.apiWallNanos?.let { metrics[OrionLlamaServiceMetric.PREFILL_NANOS] = it }
    value.decode.apiWallNanos?.let { metrics[OrionLlamaServiceMetric.DECODE_NANOS] = it }
    var stages = 0
    if (value.promptTokens != null) stages = stages or 1
    if (value.promptTokens != null && value.prefill.tokensInSuccessfulCalls == value.promptTokens && !value.prefill.partialWorkUnknown) stages = stages or 2
    if (value.finish in setOf(OrionLlamaFinish.SUCCEEDED, OrionLlamaFinish.TOKEN_LIMIT)) stages = stages or 4
    return OrionLlamaServiceGenerationReceipt(value.finish, metrics, stages,
        value.prefill.partialWorkUnknown || value.decode.partialWorkUnknown)
}
