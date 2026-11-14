package network.orion.ai.llama

import java.util.concurrent.atomic.AtomicBoolean

internal const val ORION_NATIVE_METRIC_SLOTS = 20
internal const val ORION_NATIVE_METRICS_PUBLISHED = 1 shl 8
internal const val ORION_NATIVE_FINISH_MASK = 0xff

internal fun llamaGenerationFinish(code: Int, observed: Boolean): OrionLlamaFinish = when (
    if (observed) code and ORION_NATIVE_FINISH_MASK else code) {
    0 -> OrionLlamaFinish.SUCCEEDED
    1 -> OrionLlamaFinish.CANCELLED
    2 -> OrionLlamaFinish.TOKEN_LIMIT
    else -> error("Invalid native completion.")
}

internal enum class OrionLlamaObservationState {
    NOT_STARTED, IN_PROGRESS, LOCAL_JNI_RETURNED, UNAVAILABLE_GENERATION,
    UNAVAILABLE_EXPORT, UNAVAILABLE_FORMAT, UNAVAILABLE_RECORDING,
}

internal enum class OrionLlamaMetricClock { STEADY_CLOCK_NS }

/** Durations are API wall-call sums in STEADY_CLOCK_NS, not synchronized compute/worker time. */
internal data class OrionLlamaNativeWork(
    val callsAttempted: Int, val callsSucceeded: Int,
    val tokensSubmitted: Int, val tokensInSuccessfulCalls: Int,
    val partialWorkUnknown: Boolean, val apiWallNanos: Long?,
)

internal data class OrionLlamaGenerationMetrics(
    val finish: OrionLlamaFinish, val promptTokens: Int?, val tokenizerCalls: Int,
    val sampledTokens: Int, val eogSamples: Int,
    val callbacksAttempted: Int, val callbacksReturnedNormally: Int,
    val tokenizeApiWallNanos: Long?, val prefill: OrionLlamaNativeWork, val decode: OrionLlamaNativeWork,
) {
    val clockSource: OrionLlamaMetricClock get() = OrionLlamaMetricClock.STEADY_CLOCK_NS
}

internal data class OrionLlamaGenerationObservation(
    val state: OrionLlamaObservationState, val metrics: OrionLlamaGenerationMetrics? = null,
)

/** One holder per attempted invocation. No last-result global, user callback, native handle or content. */
internal class OrionLlamaGenerationCapture {
    private val claimed = AtomicBoolean()
    private val notStarted = OrionLlamaGenerationObservation(OrionLlamaObservationState.NOT_STARTED)
    private val inProgress = OrionLlamaGenerationObservation(OrionLlamaObservationState.IN_PROGRESS)
    private val recordingFailed = OrionLlamaGenerationObservation(OrionLlamaObservationState.UNAVAILABLE_RECORDING)
    @Volatile var observation: OrionLlamaGenerationObservation = notStarted
        private set

    internal fun claim() {
        check(claimed.compareAndSet(false, true)) { "Generation capture already used." }
        observation = inProgress
    }

    internal fun complete(raw: LongArray?, code: Int?, maxTokens: Int) {
        try { observation = decodeLlamaGenerationObservation(raw, code, maxTokens) }
        catch (_: Throwable) { observation = recordingFailed }
    }
}

/** Strict fixed primitive schema; malformed diagnostics cannot replace the generation's finish. */
internal fun decodeLlamaGenerationObservation(raw: LongArray?, code: Int?, maxTokens: Int): OrionLlamaGenerationObservation {
    fun unavailable(state: OrionLlamaObservationState) = OrionLlamaGenerationObservation(state)
    if (code == null) return unavailable(OrionLlamaObservationState.UNAVAILABLE_GENERATION)
    return try {
        require(code >= 0 && code and (ORION_NATIVE_METRICS_PUBLISHED or ORION_NATIVE_FINISH_MASK).inv() == 0)
        val finish = llamaGenerationFinish(code, true)
        if (code and ORION_NATIVE_METRICS_PUBLISHED == 0 || raw == null)
            return unavailable(OrionLlamaObservationState.UNAVAILABLE_EXPORT)
        require(raw.size == ORION_NATIVE_METRIC_SLOTS && raw[0] == 1L && maxTokens in 1..32768)
        fun count(index: Int, maximum: Int = 32768): Int {
            require(raw[index] in 0..maximum.toLong())
            return raw[index].toInt()
        }
        fun duration(index: Int, calls: Int): Long? {
            require(raw[index] >= -1 && (calls > 0 || raw[index] == -1L))
            return raw[index].takeIf { it >= 0 }
        }
        val prompt = if (raw[1] == -1L) null else count(1).also { require(it > 0) }
        require(prompt == null || prompt <= 32768 - maxTokens)
        val tokenizers = count(2, 2)
        require(tokenizers == if (prompt == null) 0 else 2)
        val sampled = count(3, maxTokens)
        val eog = count(4, 1)
        val attempted = count(5, maxTokens)
        val returned = count(6, attempted)
        // Recorded returns cannot contain a pending callback exception; no delivery is inferred.
        require(attempted == returned && sampled == attempted + eog)
        fun work(first: Int, unknownIndex: Int, nanos: Int, maximum: Int): OrionLlamaNativeWork {
            val calls = count(first, maximum)
            val succeeded = count(first + 1, calls)
            val submitted = count(first + 2, maximum)
            val successful = count(first + 3, submitted)
            require(calls <= submitted && succeeded <= successful && (calls == 0) == (submitted == 0) &&
                (succeeded == 0) == (successful == 0) && calls - succeeded in 0..1)
            val unknown = count(unknownIndex, 1) == 1
            require(unknown == (calls != succeeded))
            require(if (unknown) successful < submitted else successful == submitted)
            return OrionLlamaNativeWork(calls, succeeded, submitted, successful, unknown, duration(nanos, calls))
        }
        val prefill = work(7, 15, 18, prompt ?: 0)
        val decode = work(11, 16, 19, minOf(returned, maxTokens - 1))
        require(decode.callsAttempted == decode.tokensSubmitted && decode.callsSucceeded == decode.tokensInSuccessfulCalls)
        require(sampled == 0 || prompt != null && prefill.tokensInSuccessfulCalls == prompt && !prefill.partialWorkUnknown)
        if (finish == OrionLlamaFinish.SUCCEEDED) {
            require(eog == 1 && decode.tokensInSuccessfulCalls == returned && !decode.partialWorkUnknown)
        } else {
            require(eog == 0)
            if (finish == OrionLlamaFinish.TOKEN_LIMIT) require(sampled == maxTokens &&
                decode.tokensInSuccessfulCalls == maxTokens - 1 && !decode.partialWorkUnknown)
        }
        OrionLlamaGenerationObservation(OrionLlamaObservationState.LOCAL_JNI_RETURNED,
            OrionLlamaGenerationMetrics(finish, prompt, tokenizers, sampled, eog, attempted, returned,
                duration(17, tokenizers), prefill, decode))
    } catch (_: Throwable) { unavailable(OrionLlamaObservationState.UNAVAILABLE_FORMAT) }
}
