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
    facts: OrionInferenceLifecycleFactBuffer? = null,
) {
    private val observations = try { facts?.claim() } catch (_: Throwable) { null }
    private var current = OrionInferenceStatus(OrionInferenceStage.CREATED, null, null)
    private var nextSequence = 0L
    private val pending = TreeMap<Long, OrionInferenceChunk>()
    private var pendingBytes = 0

    init {
        if (request.schemaVersion != SCHEMA_VERSION) finish(OrionInferenceTerminal.FAILED, OrionInferenceFailure.SCHEMA_MISMATCH,
            OP_INITIALIZE, ORIGIN_INIT_SCHEMA)
        else checkDeadline(OP_INITIALIZE)
    }

    @Synchronized fun status(): OrionInferenceStatus { checkDeadline(OP_STATUS); return current }

    @Synchronized fun markLoading(): Boolean {
        checkDeadline(OP_MARK_LOADING)
        if (current.stage != OrionInferenceStage.CREATED) return false
        current = current.copy(stage = OrionInferenceStage.LOADING)
        return true
    }

    @Synchronized fun markDecoding(): Boolean {
        checkDeadline(OP_MARK_DECODING)
        if (current.stage != OrionInferenceStage.LOADING) return false
        current = current.copy(stage = OrionInferenceStage.DECODING)
        return true
    }

    @Synchronized fun cancel(): OrionInferenceStatus {
        val sample = checkDeadline(OP_CANCEL)
        finish(OrionInferenceTerminal.CANCELLED, operation = OP_CANCEL, origin = ORIGIN_CANCELLATION, sample = sample)
        return current
    }

    @Synchronized fun failTransport(): OrionInferenceStatus {
        val sample = checkDeadline(OP_FAIL_TRANSPORT)
        finish(OrionInferenceTerminal.FAILED, OrionInferenceFailure.TRANSPORT_FAILURE, OP_FAIL_TRANSPORT, ORIGIN_TRANSPORT_FAILURE, sample)
        return current
    }

    @Synchronized fun processDied(): OrionInferenceStatus {
        val sample = checkDeadline(OP_PROCESS_DIED)
        finish(OrionInferenceTerminal.PROCESS_DIED, operation = OP_PROCESS_DIED, origin = ORIGIN_PROCESS_DEATH, sample = sample)
        return current
    }

    @Synchronized fun accept(chunk: OrionInferenceChunk): OrionInferenceUpdate {
        fun update(receipt: OrionInferenceReceipt, chunks: List<OrionInferenceChunk> = emptyList(),
            sample: OrionLifecycleDeadlineSample?, bytes: Int = -1, releases: OrionLifecycleReleasedMetadata? = null,
            byteParts: Long = -1L): OrionInferenceUpdate {
            val result = OrionInferenceUpdate(receipt, chunks, current)
            // No original update/list is retained or replaced. Diagnostic publication follows construction.
            observations?.update(receipt, result.status, sample, chunk.sequence, chunk.isFinal, chunk.finishReason, chunk.errorCode,
                bytes, releases, byteParts)
            return result
        }
        fun fail(reason: OrionInferenceFailure, sample: OrionLifecycleDeadlineSample?, bytes: Int = -1,
            byteParts: Long = -1L): OrionInferenceUpdate {
            finish(OrionInferenceTerminal.FAILED, reason, OP_ACCEPT, ORIGIN_REJECTED_CHUNK, sample)
            return update(OrionInferenceReceipt.FAILED, sample = sample, bytes = bytes, byteParts = byteParts)
        }
        val sample = checkDeadline(OP_ACCEPT)
        if (current.terminal != null) return update(OrionInferenceReceipt.IGNORED_TERMINAL, sample = sample)
        if (chunk.requestId != request.requestId) return update(OrionInferenceReceipt.WRONG_REQUEST, sample = sample)
        if (chunk.schemaVersion != SCHEMA_VERSION) return fail(OrionInferenceFailure.SCHEMA_MISMATCH, sample)
        if (chunk.sequence < nextSequence) return update(OrionInferenceReceipt.DUPLICATE, sample = sample)
        // Subtract nonnegative ordered operands; Long.MAX_VALUE cannot overflow this comparison.
        if (chunk.sequence - nextSequence > MAX_REORDER_GAP) return fail(OrionInferenceFailure.REORDER_LIMIT, sample)
        val observedBytes = observations?.byteParts()
        val bytes = chunk.boundedContentBytes(MAX_CHUNK_CONTENT_BYTES, observedBytes)
            ?: return fail(OrionInferenceFailure.CHUNK_TOO_LARGE, sample)
        val submittedByteParts = observedBytes?.packedParts ?: -1L
        pending[chunk.sequence]?.let { previous ->
            return if (previous.samePayload(chunk)) update(OrionInferenceReceipt.DUPLICATE, sample = sample, bytes = bytes,
                byteParts = submittedByteParts)
            else fail(OrionInferenceFailure.CONFLICTING_CHUNK, sample, bytes, submittedByteParts)
        }
        if (chunk.sequence != nextSequence) {
            if (pending.size >= MAX_REORDER_CHUNKS || bytes > MAX_REORDER_CONTENT_BYTES - pendingBytes)
                return fail(OrionInferenceFailure.REORDER_LIMIT, sample, bytes, submittedByteParts)
            pending[chunk.sequence] = chunk
            pendingBytes += bytes
            return update(OrionInferenceReceipt.BUFFERED, sample = sample, bytes = bytes, byteParts = submittedByteParts)
        }
        val delivered = mutableListOf<OrionInferenceChunk>()
        val releasedMetadata = observations?.releases()
        var next: OrionInferenceChunk? = chunk
        var nextBytes = bytes
        var nextByteParts = submittedByteParts
        while (next != null) {
            delivered.add(next)
            releasedMetadata?.released(next.sequence, next.isFinal, next.finishReason, next.errorCode, nextBytes, nextByteParts)
            current = current.copy(stage = OrionInferenceStage.DECODING)
            if (next.isFinal) {
                finish(checkNotNull(next.finishReason), next.errorCode, OP_ACCEPT, ORIGIN_FINAL_CHUNK_TRANSITION, sample)
                break
            }
            if (nextSequence == Long.MAX_VALUE) return fail(OrionInferenceFailure.REORDER_LIMIT, sample, bytes, submittedByteParts)
            nextSequence++
            next = pending.remove(nextSequence)
            if (next != null) {
                // Exactly the original revalidation, reused for both accounting and optional metadata.
                val removedBytes = checkNotNull(next.boundedContentBytes(MAX_CHUNK_CONTENT_BYTES, observedBytes))
                pendingBytes -= removedBytes
                nextBytes = removedBytes
                nextByteParts = observedBytes?.packedParts ?: -1L
            }
        }
        return update(OrionInferenceReceipt.ACCEPTED, delivered, sample, bytes, releasedMetadata, submittedByteParts)
    }

    private fun checkDeadline(operation: Int): OrionLifecycleDeadlineSample? {
        if (current.terminal != null) return null
        val deadline = request.deadline
        val at = clock.monotonicNow() // Original call outside any diagnostic catch.
        val classification = deadline.statusAt(at) // Original classification, once and in the same order.
        val sample = observations?.deadline(operation, at, classification)
        when (classification) {
            MonotonicDeadlineStatus.ACTIVE -> Unit
            MonotonicDeadlineStatus.EXPIRED -> finish(OrionInferenceTerminal.TIMED_OUT, operation = operation,
                origin = ORIGIN_DEADLINE_EXPIRED, sample = sample)
            MonotonicDeadlineStatus.INVALID_REFERENCE -> finish(OrionInferenceTerminal.FAILED, OrionInferenceFailure.INVALID_TIME_REFERENCE,
                operation, ORIGIN_INVALID_TIME, sample)
        }
        return sample
    }

    private fun finish(terminal: OrionInferenceTerminal, failure: OrionInferenceFailure? = null,
        operation: Int, origin: Int, sample: OrionLifecycleDeadlineSample? = null) {
        if (current.terminal != null) return
        current = OrionInferenceStatus(OrionInferenceStage.TERMINAL, terminal, failure)
        pending.clear()
        pendingBytes = 0
        observations?.firstTerminal(operation, origin, current, sample)
    }

    companion object {
        const val SCHEMA_VERSION = OrionCortexProtocol.SCHEMA_VERSION
        const val MAX_REORDER_GAP = 8L
        const val MAX_REORDER_CHUNKS = 8
        const val MAX_CHUNK_CONTENT_BYTES = 64 * 1024
        const val MAX_REORDER_CONTENT_BYTES = 256 * 1024
        private const val OP_INITIALIZE = 0
        private const val OP_STATUS = 1
        private const val OP_MARK_LOADING = 2
        private const val OP_MARK_DECODING = 3
        private const val OP_CANCEL = 4
        private const val OP_FAIL_TRANSPORT = 5
        private const val OP_PROCESS_DIED = 6
        private const val OP_ACCEPT = 7
        private const val ORIGIN_INIT_SCHEMA = 0
        private const val ORIGIN_DEADLINE_EXPIRED = 1
        private const val ORIGIN_INVALID_TIME = 2
        private const val ORIGIN_CANCELLATION = 3
        private const val ORIGIN_TRANSPORT_FAILURE = 4
        private const val ORIGIN_PROCESS_DEATH = 5
        private const val ORIGIN_FINAL_CHUNK_TRANSITION = 6
        private const val ORIGIN_REJECTED_CHUNK = 7
    }
}
