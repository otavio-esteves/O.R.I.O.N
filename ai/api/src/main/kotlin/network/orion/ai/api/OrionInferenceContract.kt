package network.orion.ai.api

import network.orion.core.ipc.OrionIpcProtocol
import network.orion.core.ipc.ProtocolInfo
import network.orion.core.ipc.IpcCapabilityId
import network.orion.core.time.MonotonicDeadline

/** Uses the existing baseline major/minor negotiation; no subsystem-specific authority. */
object OrionCortexProtocol {
    val ISSUED_HISTORY = IpcCapabilityId("orion_issued_request_history_v1")
    val CURRENT = ProtocolInfo(OrionIpcProtocol.CURRENT.protocolMajor, 1, setOf(ISSUED_HISTORY))
    const val SCHEMA_VERSION = 1
}

enum class OrionInferencePriority { INTERACTIVE, USER_COMMAND, REMINDER, PROACTIVE, BACKGROUND }
enum class OrionInferenceTerminal { SUCCEEDED, CANCELLED, TIMED_OUT, FAILED, PROCESS_DIED }
enum class OrionInferenceStage { CREATED, LOADING, DECODING, TERMINAL }
enum class OrionInferenceFailure {
    SCHEMA_MISMATCH, INVALID_TIME_REFERENCE, REORDER_LIMIT, CONFLICTING_CHUNK, CHUNK_TOO_LARGE, REMOTE_ERROR, TRANSPORT_FAILURE,
}

/** In-memory API value. The Binder adapter must separately validate its complete encoded envelope. */
class OrionInferenceRequest(
    val requestId: String,
    val schemaVersion: Int,
    val modelId: String,
    val prompt: String,
    val context: String,
    val maxTokens: Int,
    val temperature: Double,
    val priority: OrionInferencePriority,
    val deadline: MonotonicDeadline,
    val correlationId: String,
) {
    init {
        requireInferenceId(requestId)
        requireInferenceId(modelId)
        requireInferenceId(correlationId)
        require(schemaVersion > 0 && maxTokens > 0)
        require(temperature.isFinite() && temperature in 0.0..2.0)
    }

    // Content and caller-supplied identifiers must not enter implicit diagnostic logging.
    override fun toString() = "OrionInferenceRequest(schemaVersion=$schemaVersion, content=REDACTED)"
}

/** Chunk sequence begins at zero. A final chunk declares exactly one terminal reason. */
class OrionInferenceChunk(
    val requestId: String,
    val schemaVersion: Int,
    val sequence: Long,
    val textDelta: String? = null,
    val toolDelta: String? = null,
    val isFinal: Boolean = false,
    val finishReason: OrionInferenceTerminal? = null,
    val errorCode: OrionInferenceFailure? = null,
) {
    init {
        requireInferenceId(requestId)
        require(schemaVersion > 0 && sequence >= 0)
        require(isFinal == (finishReason != null))
        require(errorCode == null || finishReason == OrionInferenceTerminal.FAILED)
    }

    internal fun samePayload(other: OrionInferenceChunk) = requestId == other.requestId &&
        schemaVersion == other.schemaVersion && sequence == other.sequence &&
        textDelta == other.textDelta && toolDelta == other.toolDelta &&
        isFinal == other.isFinal && finishReason == other.finishReason && errorCode == other.errorCode

    /** Bound character count before allocating a UTF-8 copy; this is not encoded Parcel size. */
    internal fun boundedContentBytes(limit: Int): Int? {
        val chars = (textDelta?.length?.toLong() ?: 0L) + (toolDelta?.length?.toLong() ?: 0L)
        if (chars > limit) return null
        val bytes = (textDelta?.toByteArray(Charsets.UTF_8)?.size ?: 0) +
            (toolDelta?.toByteArray(Charsets.UTF_8)?.size ?: 0)
        return bytes.takeIf { it <= limit }
    }

    override fun toString() = "OrionInferenceChunk(schemaVersion=$schemaVersion, sequence=$sequence, isFinal=$isFinal, content=REDACTED)"
}

internal fun requireInferenceId(value: String) {
    require(value.length in 1..128 && value == value.trim() && value.none(Char::isISOControl)) {
        "Inference identifier is invalid."
    }
}
