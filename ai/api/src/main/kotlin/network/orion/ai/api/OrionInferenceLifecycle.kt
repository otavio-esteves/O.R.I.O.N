package network.orion.ai.api

import java.util.Collections
import java.util.TreeMap
import network.orion.core.time.MonotonicClock
import network.orion.core.time.MonotonicDeadlineStatus

data class OrionInferenceStatus(
    val stage: OrionInferenceStage,
    val terminal: OrionInferenceTerminal?,
    val failure: OrionInferenceFailure?,
)

enum class OrionInferenceReceipt { ACCEPTED, BUFFERED, DUPLICATE, WRONG_REQUEST, IGNORED_TERMINAL, FAILED }

class OrionInferenceUpdate internal constructor(
    val receipt: OrionInferenceReceipt,
    chunks: List<OrionInferenceChunk>,
    val status: OrionInferenceStatus,
) {
    val chunks: List<OrionInferenceChunk> = Collections.unmodifiableList(chunks.toList())
}

/**
 * Main-owned, synchronized lifecycle for one admitted request; no persistence, execution or authority.
 * Owners separately deduplicate admission and bound their request map and downstream output.
 * Cancellation terminates logical delivery immediately; native cancellation remains best effort.
 */
class OrionInferenceLifecycle(
    private val request: OrionInferenceRequest,
    private val clock: MonotonicClock,
) {
    private var current = OrionInferenceStatus(OrionInferenceStage.CREATED, null, null)
    private var nextSequence = 0L
    private val pending = TreeMap<Long, OrionInferenceChunk>()
    private var pendingBytes = 0

    init {
        if (request.schemaVersion != SCHEMA_VERSION) finish(OrionInferenceTerminal.FAILED, OrionInferenceFailure.SCHEMA_MISMATCH)
        else checkDeadline()
    }

    @Synchronized fun status(): OrionInferenceStatus { checkDeadline(); return current }

    @Synchronized fun markLoading(): Boolean {
        checkDeadline()
        if (current.stage != OrionInferenceStage.CREATED) return false
        current = current.copy(stage = OrionInferenceStage.LOADING)
        return true
    }

    @Synchronized fun markDecoding(): Boolean {
        checkDeadline()
        if (current.stage != OrionInferenceStage.LOADING) return false
        current = current.copy(stage = OrionInferenceStage.DECODING)
        return true
    }

    @Synchronized fun cancel(): OrionInferenceStatus {
        checkDeadline(); finish(OrionInferenceTerminal.CANCELLED); return current
    }

    @Synchronized fun failTransport(): OrionInferenceStatus {
        checkDeadline(); finish(OrionInferenceTerminal.FAILED, OrionInferenceFailure.TRANSPORT_FAILURE); return current
    }

    @Synchronized fun processDied(): OrionInferenceStatus {
        checkDeadline(); finish(OrionInferenceTerminal.PROCESS_DIED); return current
    }

    @Synchronized fun accept(chunk: OrionInferenceChunk): OrionInferenceUpdate {
        fun update(receipt: OrionInferenceReceipt, chunks: List<OrionInferenceChunk> = emptyList()) =
            OrionInferenceUpdate(receipt, chunks, current)
        fun fail(reason: OrionInferenceFailure): OrionInferenceUpdate {
            finish(OrionInferenceTerminal.FAILED, reason)
            return update(OrionInferenceReceipt.FAILED)
        }
        checkDeadline()
        if (current.terminal != null) return update(OrionInferenceReceipt.IGNORED_TERMINAL)
        if (chunk.requestId != request.requestId) return update(OrionInferenceReceipt.WRONG_REQUEST)
        if (chunk.schemaVersion != SCHEMA_VERSION) return fail(OrionInferenceFailure.SCHEMA_MISMATCH)
        if (chunk.sequence < nextSequence) return update(OrionInferenceReceipt.DUPLICATE)
        // Subtract nonnegative ordered operands; Long.MAX_VALUE cannot overflow this comparison.
        if (chunk.sequence - nextSequence > MAX_REORDER_GAP) return fail(OrionInferenceFailure.REORDER_LIMIT)
        val bytes = chunk.boundedContentBytes(MAX_CHUNK_CONTENT_BYTES)
            ?: return fail(OrionInferenceFailure.CHUNK_TOO_LARGE)
        pending[chunk.sequence]?.let { previous ->
            return if (previous.samePayload(chunk)) update(OrionInferenceReceipt.DUPLICATE)
            else fail(OrionInferenceFailure.CONFLICTING_CHUNK)
        }
        if (chunk.sequence != nextSequence) {
            if (pending.size >= MAX_REORDER_CHUNKS || bytes > MAX_REORDER_CONTENT_BYTES - pendingBytes)
                return fail(OrionInferenceFailure.REORDER_LIMIT)
            pending[chunk.sequence] = chunk
            pendingBytes += bytes
            return update(OrionInferenceReceipt.BUFFERED)
        }
        val delivered = mutableListOf<OrionInferenceChunk>()
        var next: OrionInferenceChunk? = chunk
        while (next != null) {
            delivered.add(next)
            current = current.copy(stage = OrionInferenceStage.DECODING)
            if (next.isFinal) {
                finish(checkNotNull(next.finishReason), next.errorCode)
                break
            }
            if (nextSequence == Long.MAX_VALUE) return fail(OrionInferenceFailure.REORDER_LIMIT)
            nextSequence++
            next = pending.remove(nextSequence)
            if (next != null) pendingBytes -= checkNotNull(next.boundedContentBytes(MAX_CHUNK_CONTENT_BYTES))
        }
        return update(OrionInferenceReceipt.ACCEPTED, delivered)
    }

    private fun checkDeadline() {
        if (current.terminal != null) return
        when (request.deadline.statusAt(clock.monotonicNow())) {
            MonotonicDeadlineStatus.ACTIVE -> Unit
            MonotonicDeadlineStatus.EXPIRED -> finish(OrionInferenceTerminal.TIMED_OUT)
            MonotonicDeadlineStatus.INVALID_REFERENCE -> finish(OrionInferenceTerminal.FAILED, OrionInferenceFailure.INVALID_TIME_REFERENCE)
        }
    }

    private fun finish(terminal: OrionInferenceTerminal, failure: OrionInferenceFailure? = null) {
        if (current.terminal != null) return
        current = OrionInferenceStatus(OrionInferenceStage.TERMINAL, terminal, failure)
        pending.clear()
        pendingBytes = 0
    }

    companion object {
        const val SCHEMA_VERSION = OrionCortexProtocol.SCHEMA_VERSION
        const val MAX_REORDER_GAP = 8L
        const val MAX_REORDER_CHUNKS = 8
        const val MAX_CHUNK_CONTENT_BYTES = 64 * 1024
        const val MAX_REORDER_CONTENT_BYTES = 256 * 1024
    }
}
