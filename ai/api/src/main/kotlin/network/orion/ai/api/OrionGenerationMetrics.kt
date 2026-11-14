package network.orion.ai.api

/** ADR-0003. Measurements convey no admission, execution or retry authority. */
enum class OrionGenerationMetric(val key: String) {
    PROMPT_TOKENS("promptTokens"), SAMPLED_TOKENS("sampledTokens"), EOG_TOKENS("eogTokens"),
    CALLBACKS_ATTEMPTED("callbacksAttempted"), CALLBACKS_RETURNED("callbacksReturnedNormally"),
    PREFILL_CALLS("prefillCalls"), PREFILL_SUCCEEDED("prefillSuccessfulCalls"),
    PREFILL_SUBMITTED("prefillSubmittedTokens"), PREFILL_SUCCESSFUL_TOKENS("prefillTokensInSuccessfulCalls"),
    DECODE_CALLS("decodeCalls"), DECODE_SUCCEEDED("decodeSuccessfulCalls"),
    DECODE_SUBMITTED("decodeSubmittedTokens"), DECODE_SUCCESSFUL_TOKENS("decodeTokensInSuccessfulCalls"),
    TOKENIZATION_NANOS("tokenizationApiWallNanos"), PREFILL_NANOS("prefillApiWallNanos"), DECODE_NANOS("decodeApiWallNanos");
    val bit: Long get() = 1L shl ordinal
    val duration: Boolean get() = ordinal >= 13
    companion object { const val ALL_MASK = 0xffffL }
}

enum class OrionGenerationNativeState { NOT_STARTED, RUNNING, JNI_RETURNED, NOT_EXECUTED, UNKNOWN }
enum class OrionGenerationNativeFinish { NONE, SUCCEEDED, CANCELLED, TOKEN_LIMIT, UNKNOWN }
enum class OrionGenerationSlotState { OWNED, RELEASED, UNKNOWN }
enum class OrionGenerationSnapshotState { UPDATING, FINAL, UNKNOWN }
enum class OrionGenerationPartialWork { NONE_OBSERVED, POSSIBLE, UNKNOWN }
enum class OrionGenerationTimeState { KNOWN_BOOT, UNAVAILABLE, DISCONTINUOUS }

/** Immutable primitive projection. No source array, Bundle, content or native handle escapes. */
class OrionGenerationMetricsSnapshot(
    val requestId: String,
    val servicePid: Int,
    val invocationId: Long?,
    val snapshotSequence: Long,
    val logicalTerminal: OrionInferenceTerminal?,
    val nativeState: OrionGenerationNativeState,
    val nativeFinish: OrionGenerationNativeFinish,
    val slotState: OrionGenerationSlotState,
    val snapshotState: OrionGenerationSnapshotState,
    val partialWorkState: OrionGenerationPartialWork,
    val completedCallStagesMask: Int,
    val timeState: OrionGenerationTimeState,
    val bootCount: Int? = null,
    val capturedElapsedMillis: Long? = null,
    val wrapperStartedElapsed: Long? = null,
    val wrapperFinishedElapsed: Long? = null,
    metrics: Map<OrionGenerationMetric, Long> = emptyMap(),
) {
    private val values = metrics.toMap()
    val availableMetricsMask: Long = values.keys.fold(0L) { mask, metric -> mask or metric.bit }
    fun metric(metric: OrionGenerationMetric): Long? = values[metric]

    init {
        requireInferenceId(requestId)
        require(OrionIssuedRequestHistory.isIssuedIdentity(requestId))
        require(servicePid > 0 && snapshotSequence > 0 && (invocationId == null || invocationId > 0))
        require(completedCallStagesMask and 7.inv() == 0)
        if (timeState == OrionGenerationTimeState.KNOWN_BOOT) {
            require(bootCount != null && bootCount >= 0 && capturedElapsedMillis != null && capturedElapsedMillis >= 0)
            require(wrapperStartedElapsed == null || wrapperStartedElapsed in 0..capturedElapsedMillis)
            require(wrapperFinishedElapsed == null || wrapperStartedElapsed != null &&
                wrapperFinishedElapsed in wrapperStartedElapsed..capturedElapsedMillis)
        } else require(bootCount == null && capturedElapsedMillis == null && wrapperStartedElapsed == null && wrapperFinishedElapsed == null)
        if (nativeState in setOf(OrionGenerationNativeState.RUNNING, OrionGenerationNativeState.JNI_RETURNED)) require(invocationId != null)
        if (nativeState in setOf(OrionGenerationNativeState.NOT_STARTED, OrionGenerationNativeState.NOT_EXECUTED)) {
            require(invocationId == null && nativeFinish == OrionGenerationNativeFinish.NONE && values.isEmpty() && completedCallStagesMask == 0)
        }
        if (nativeFinish !in setOf(OrionGenerationNativeFinish.NONE, OrionGenerationNativeFinish.UNKNOWN))
            require(nativeState == OrionGenerationNativeState.JNI_RETURNED)
        if (snapshotState == OrionGenerationSnapshotState.FINAL) require(logicalTerminal != null &&
            slotState == OrionGenerationSlotState.RELEASED && nativeState in
            setOf(OrionGenerationNativeState.JNI_RETURNED, OrionGenerationNativeState.NOT_EXECUTED))
        for ((metric, value) in values) {
            require(value >= 0)
            if (!metric.duration) require(value <= when (metric) {
                OrionGenerationMetric.EOG_TOKENS -> 1L
                OrionGenerationMetric.PROMPT_TOKENS,
                OrionGenerationMetric.PREFILL_CALLS, OrionGenerationMetric.PREFILL_SUCCEEDED,
                OrionGenerationMetric.PREFILL_SUBMITTED, OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS -> 32768L
                else -> 4096L
            })
        }
        require(values[OrionGenerationMetric.PROMPT_TOKENS] != 0L)
        checkRelations()
        checkCompletedCallStages()
    }

    /** Limits belong to the actual admitted request, never the metrics query. */
    fun requireAdmittedBounds(maxTokens: Int, contextTokens: Int) {
        require(maxTokens in 1..4096 && contextTokens in 32..32768)
        for ((metric, value) in values) if (metric.ordinal in 1..4 || metric.ordinal in 9..12)
            require(value <= if (metric.ordinal in 9..12) maxTokens - 1L else maxTokens.toLong())
        values[OrionGenerationMetric.PROMPT_TOKENS]?.let { require(it <= contextTokens.toLong() - maxTokens) }
        if (nativeFinish == OrionGenerationNativeFinish.TOKEN_LIMIT) {
            values[OrionGenerationMetric.SAMPLED_TOKENS]?.let { require(it == maxTokens.toLong()) }
            values[OrionGenerationMetric.CALLBACKS_RETURNED]?.let { require(it == maxTokens.toLong()) }
            values[OrionGenerationMetric.EOG_TOKENS]?.let { require(it == 0L) }
        }
    }

    private fun checkRelations() {
        fun le(a: OrionGenerationMetric, b: OrionGenerationMetric) {
            val x = values[a]; val y = values[b]; if (x != null && y != null) require(x <= y)
        }
        fun eq(a: OrionGenerationMetric, b: OrionGenerationMetric) {
            val x = values[a]; val y = values[b]; if (x != null && y != null) require(x == y)
        }
        fun sameZero(a: OrionGenerationMetric, b: OrionGenerationMetric) {
            val x = values[a]; val y = values[b]; if (x != null && y != null) require((x == 0L) == (y == 0L))
        }
        le(OrionGenerationMetric.EOG_TOKENS, OrionGenerationMetric.SAMPLED_TOKENS)
        le(OrionGenerationMetric.CALLBACKS_RETURNED, OrionGenerationMetric.CALLBACKS_ATTEMPTED)
        le(OrionGenerationMetric.CALLBACKS_ATTEMPTED, OrionGenerationMetric.SAMPLED_TOKENS)
        val sampled = values[OrionGenerationMetric.SAMPLED_TOKENS]; val eog = values[OrionGenerationMetric.EOG_TOKENS]
        val attempted = values[OrionGenerationMetric.CALLBACKS_ATTEMPTED]
        if (sampled != null && eog != null && attempted != null) require(attempted <= sampled - eog)
        le(OrionGenerationMetric.PREFILL_SUCCEEDED, OrionGenerationMetric.PREFILL_CALLS)
        le(OrionGenerationMetric.PREFILL_SUCCEEDED, OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS)
        sameZero(OrionGenerationMetric.PREFILL_CALLS, OrionGenerationMetric.PREFILL_SUBMITTED)
        sameZero(OrionGenerationMetric.PREFILL_SUCCEEDED, OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS)
        le(OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS, OrionGenerationMetric.PREFILL_SUBMITTED)
        le(OrionGenerationMetric.PREFILL_CALLS, OrionGenerationMetric.PREFILL_SUBMITTED)
        le(OrionGenerationMetric.PREFILL_SUBMITTED, OrionGenerationMetric.PROMPT_TOKENS)
        le(OrionGenerationMetric.DECODE_SUCCEEDED, OrionGenerationMetric.DECODE_CALLS)
        le(OrionGenerationMetric.DECODE_SUCCESSFUL_TOKENS, OrionGenerationMetric.DECODE_SUBMITTED)
        le(OrionGenerationMetric.DECODE_SUBMITTED, OrionGenerationMetric.CALLBACKS_RETURNED)
        eq(OrionGenerationMetric.DECODE_SUBMITTED, OrionGenerationMetric.DECODE_CALLS)
        eq(OrionGenerationMetric.DECODE_SUCCESSFUL_TOKENS, OrionGenerationMetric.DECODE_SUCCEEDED)
    }

    /** Stage claims require their actual operands; absent counts never imply completion. */
    private fun checkCompletedCallStages() {
        val tokenizer = completedCallStagesMask and 1 != 0
        val prefill = completedCallStagesMask and 2 != 0
        val generation = completedCallStagesMask and 4 != 0
        val prompt = values[OrionGenerationMetric.PROMPT_TOKENS]
        if (tokenizer) require(prompt != null)
        if (prefill) require(tokenizer && prompt != null &&
            values[OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS] == prompt)
        if (generation) require(prefill && nativeState == OrionGenerationNativeState.JNI_RETURNED &&
            nativeFinish in setOf(OrionGenerationNativeFinish.SUCCEEDED, OrionGenerationNativeFinish.TOKEN_LIMIT))
    }

    override fun toString() = "OrionGenerationMetricsSnapshot(sequence=$snapshotSequence, state=$snapshotState, correlation=REDACTED)"
}
