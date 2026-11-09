package network.orion.ai.api

import java.util.UUID
import network.orion.core.time.MonotonicClock
import network.orion.core.time.MonotonicInstant

enum class OrionCortexOperation { BIND, LOAD, INFER }
enum class OrionCortexHealth { DISCONNECTED, CONNECTING, UNLOADED, LOADING, READY, INFERENCING, UNLOADING, DEGRADED, UNAVAILABLE }
enum class OrionCortexRequestStatus { QUEUED, RUNNING, SUCCEEDED, CANCELLED, TIMED_OUT, FAILED, PROCESS_DIED;
    val terminal: Boolean get() = this != QUEUED && this != RUNNING
}
enum class OrionCortexAdmission { ACCEPTED, DUPLICATE_REQUEST, BUSY, INVALID, UNAVAILABLE }
enum class OrionCortexPriority(val value: Int) { BACKGROUND(0), PROACTIVE(1), REMINDER(2), USER_COMMAND(3), INTERACTIVE(4) }

/** A short-lived complete resource envelope supplied by trusted MAIN code, never by an LLM. */
data class OrionCortexApproval(
    val modelId: String,
    val contextSize: Int,
    val threads: Int,
    val maxTokens: Int,
    val sampledAt: MonotonicInstant,
    val validForMillis: Long,
    val keepAliveMs: Long = 0,
) {
    init {
        OrionCortexBounds.requireModelId(modelId)
        require(contextSize in 32..OrionCortexBounds.MAX_CONTEXT)
        require(threads in 1..OrionCortexBounds.MAX_THREADS)
        require(maxTokens in 1..OrionCortexBounds.MAX_TOKENS)
        require(validForMillis in 1..5_000)
        require(keepAliveMs in 0..60_000)
    }
    fun current(clock: MonotonicClock): Boolean = clock.monotonicNow().elapsedSince(sampledAt)
        ?.toMillis()?.let { it < validForMillis } == true
}

fun interface OrionCortexAdmissionGuard {
    fun approve(modelId: String, operation: OrionCortexOperation): OrionCortexApproval?
}

/** Text is inference data. A terminal callback carries no Android/business authorization. */
data class OrionCortexChunk(
    val requestId: UUID,
    val sequence: Long,
    val textDelta: String? = null,
    val terminal: OrionCortexRequestStatus? = null,
    val schemaVersion: Int = 1,
) {
    init {
        require(schemaVersion == 1 && sequence in 0..OrionCortexBounds.MAX_CHUNKS.toLong())
        require((textDelta != null) != (terminal != null))
        require(terminal == null || terminal.terminal)
        require(textDelta == null || textDelta.isNotEmpty() && textDelta.length <= OrionCortexBounds.MAX_DELTA_CHARS && validUnicode(textDelta))
    }
    override fun toString() = "OrionCortexChunk(<redacted>)"
}

/** Protocol caps, not calibrated hardware budgets. Qualified resource plans can only lower them. */
object OrionCortexBounds {
    const val MAX_CONTEXT = 32_768
    const val MAX_THREADS = 16
    const val MAX_TOKENS = 8_192
    const val MAX_PROMPT_CHARS = 32_768
    const val MAX_DELTA_CHARS = 4_096
    const val MAX_OUTPUT_CHARS = 262_144
    const val MAX_CHUNKS = 16_384
    const val MAX_MODEL_BYTES = 16L * 1024 * 1024 * 1024
    const val MAX_REQUESTS = 1_024
    const val MAX_PENDING = 4
    const val MAX_TIMEOUT_MILLIS = 120_000L
    fun requireModelId(value: String) {
        require(value.length in 1..128 && value.all { it.isLetterOrDigit() && it.code < 128 || it in "_.:-" })
    }
    fun validPrompt(value: String) = value.isNotEmpty() && value.length <= MAX_PROMPT_CHARS && validUnicode(value)
}

internal fun validUnicode(value: String): Boolean {
    var i = 0
    while (i < value.length) {
        val char = value[i++]
        if (char.isHighSurrogate()) {
            if (i == value.length || !value[i++].isLowSurrogate()) return false
        } else if (char.isLowSurrogate()) return false
    }
    return true
}

/** Never split a supplementary Unicode code point across streamed parcels. */
fun orionCortexTextParts(text: String): List<String> {
    require(text.length <= OrionCortexBounds.MAX_OUTPUT_CHARS && validUnicode(text))
    val result = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(text.length, start + OrionCortexBounds.MAX_DELTA_CHARS)
        if (text[end - 1].isHighSurrogate()) end--
        result += text.substring(start, end)
        start = end
    }
    return result
}
