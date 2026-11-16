package network.orion.ai.api

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import network.orion.core.time.MonotonicDeadlineStatus
import network.orion.core.time.MonotonicInstant

enum class OrionLifecycleOperation { INITIALIZE, STATUS, MARK_LOADING, MARK_DECODING, CANCEL, FAIL_TRANSPORT, PROCESS_DIED, ACCEPT }
enum class OrionLifecycleTerminalOrigin { INIT_SCHEMA, DEADLINE_EXPIRED, INVALID_TIME, CANCELLATION, TRANSPORT_FAILURE,
    PROCESS_DEATH, FINAL_CHUNK_TRANSITION, REJECTED_CHUNK }

/** An original deadline-check sample, not arrival/validation/transition/export/native time. */
data class OrionLifecycleDeadlineSample(val operation: OrionLifecycleOperation, val sampledAt: MonotonicInstant,
    val classification: MonotonicDeadlineStatus)

/** Actual first state transition, independent of whether an update/callback subsequently returns. */
data class OrionLifecycleFirstTerminal(val operation: OrionLifecycleOperation, val origin: OrionLifecycleTerminalOrigin,
    val status: OrionInferenceStatus, val associatedOriginalDeadlineSample: OrionLifecycleDeadlineSample?)

/** Validated original total and optional matching parts; null parts are unavailable, never guessed zeros. */
data class OrionLifecycleChunkMetadata(val sequence: Long, val isFinal: Boolean, val finishReason: OrionInferenceTerminal?,
    val failure: OrionInferenceFailure?, val validatedTotalContentUtf8Bytes: Int?,
    val validatedTextDeltaUtf8Bytes: Int? = null, val validatedToolDeltaUtf8Bytes: Int? = null) {
    init {
        require((validatedTextDeltaUtf8Bytes == null) == (validatedToolDeltaUtf8Bytes == null))
        if (validatedTextDeltaUtf8Bytes != null && validatedToolDeltaUtf8Bytes != null) {
            require(validatedTextDeltaUtf8Bytes >= 0 && validatedToolDeltaUtf8Bytes >= 0 &&
                validatedTotalContentUtf8Bytes != null &&
                validatedTextDeltaUtf8Bytes.toLong() + validatedToolDeltaUtf8Bytes == validatedTotalContentUtf8Bytes.toLong())
        }
    }
}

/** A constructed original update, never a downstream onChunks/delivery/native observation. */
data class OrionLifecycleUpdateFact(val receipt: OrionInferenceReceipt, val status: OrionInferenceStatus,
    val associatedOriginalDeadlineSample: OrionLifecycleDeadlineSample?, val submitted: OrionLifecycleChunkMetadata,
    val bufferedAdmission: OrionLifecycleChunkMetadata?, val orderedRelease: List<OrionLifecycleChunkMetadata>?,
    val orderedReleaseMetadataAvailable: Boolean)

data class OrionInferenceLifecycleFacts(val deadlineSamples: List<OrionLifecycleDeadlineSample>,
    val updates: List<OrionLifecycleUpdateFact>, val firstTerminal: OrionLifecycleFirstTerminal?,
    val firstTerminalRecordUnavailable: Boolean, val droppedAtLeast: Long, val recordingFailuresAtLeast: Long,
    val lossAccountingComplete: Boolean, val snapshotContended: Boolean)

/** One lifecycle only, final bounded passive collector. No callback, clock, IO, waiting or policy. */
class OrionInferenceLifecycleFactBuffer(private val capacity: Int = 32) {
    init { require(capacity in 1..64) }
    private val claimed = AtomicBoolean()
    private val lock = ReentrantLock()
    private val deadlines = ArrayDeque<OrionLifecycleDeadlineSample>(capacity)
    private val updates = ArrayDeque<OrionLifecycleUpdateFact>(capacity)
    private var terminal: OrionLifecycleFirstTerminal? = null
    @Volatile private var terminalUnavailable = false
    private val dropped = AtomicLong()
    private val failures = AtomicLong()
    private val accounting = AtomicBoolean(true)

    internal fun claim(): OrionInferenceLifecycleFactBuffer? = if (claimed.compareAndSet(false, true)) this else null

    private fun count(counter: AtomicLong) {
        val previous = counter.get()
        if (previous == Long.MAX_VALUE || !counter.compareAndSet(previous, previous + 1)) accounting.set(false)
    }
    internal fun failed() { count(dropped); count(failures); accounting.set(false) }

    private inline fun <T> isolate(operation: () -> T): T? = try { operation() } catch (_: Throwable) { failed(); null }

    internal fun deadline(operation: Int, at: MonotonicInstant, classification: MonotonicDeadlineStatus): OrionLifecycleDeadlineSample? = isolate {
        val value = OrionLifecycleDeadlineSample(OrionLifecycleOperation.entries[operation], at, classification)
        if (!lock.tryLock()) count(dropped)
        else try { if (deadlines.size < capacity) deadlines.addLast(value) else count(dropped) } finally { lock.unlock() }
        value // The actual operation-local sample remains known even if the general sample queue is full.
    }

    internal fun firstTerminal(operation: Int, origin: Int, status: OrionInferenceStatus, sample: OrionLifecycleDeadlineSample?) {
        try {
            if (!lock.tryLock()) { terminalUnavailable = true; count(dropped); return }
            try {
                if (terminal == null) terminal = OrionLifecycleFirstTerminal(OrionLifecycleOperation.entries[operation],
                    OrionLifecycleTerminalOrigin.entries[origin], status, sample)
            } finally { lock.unlock() }
        } catch (_: Throwable) { terminalUnavailable = true; failed() }
    }

    internal fun releases(): OrionLifecycleReleasedMetadata? = isolate { OrionLifecycleReleasedMetadata(this) }
    internal fun byteParts(): OrionLifecycleByteParts? = isolate { OrionLifecycleByteParts(this) }

    internal fun update(receipt: OrionInferenceReceipt, status: OrionInferenceStatus, sample: OrionLifecycleDeadlineSample?,
        sequence: Long, isFinal: Boolean, finish: OrionInferenceTerminal?, failure: OrionInferenceFailure?, bytes: Int,
        releases: OrionLifecycleReleasedMetadata?, byteParts: Long = -1L) {
        isolate {
            if (!lock.tryLock()) { count(dropped); return@isolate }
            try {
                if (updates.size >= capacity) { count(dropped); return@isolate }
                val input = chunk(sequence, isFinal, finish, failure, bytes, byteParts)
                val released = if (receipt == OrionInferenceReceipt.ACCEPTED) releases?.handoff() else emptyList()
                updates.addLast(OrionLifecycleUpdateFact(receipt, status, sample, input,
                    if (receipt == OrionInferenceReceipt.BUFFERED) input else null,
                    released, receipt != OrionInferenceReceipt.ACCEPTED || released != null))
            } finally { lock.unlock() }
        }
    }

    /** Only the separate manual consumer snapshots/copies these retained facts. Never drains. */
    fun snapshot(): OrionInferenceLifecycleFacts? = isolate {
        if (!lock.tryLock()) batch(emptyList(), emptyList(), null, true)
        else try { batch(Collections.unmodifiableList(ArrayList(deadlines)), Collections.unmodifiableList(ArrayList(updates)), terminal, false) }
        finally { lock.unlock() }
    }

    private fun batch(samples: List<OrionLifecycleDeadlineSample>, values: List<OrionLifecycleUpdateFact>,
        first: OrionLifecycleFirstTerminal?, busy: Boolean) = OrionInferenceLifecycleFacts(samples, values, first,
        terminalUnavailable, dropped.get(), failures.get(), accounting.get(), busy)

    internal fun chunk(sequence: Long, isFinal: Boolean, finish: OrionInferenceTerminal?, failure: OrionInferenceFailure?,
        bytes: Int, byteParts: Long = -1L): OrionLifecycleChunkMetadata {
        require(sequence >= 0 && bytes in -1..OrionInferenceLifecycle.MAX_CHUNK_CONTENT_BYTES)
        val text = (byteParts ushr 32).toInt()
        val tool = byteParts.toInt()
        val available = byteParts >= 0 && text >= 0 && tool >= 0 && text.toLong() + tool == bytes.toLong()
        if (byteParts != -1L && !available) failed()
        return OrionLifecycleChunkMetadata(sequence, isFinal, finish, failure, bytes.takeIf { it >= 0 },
            if (available) text else null, if (available) tool else null)
    }
}

/** One accept call, scalars only. Read only after an original guard successfully returns its total. */
internal class OrionLifecycleByteParts(private val owner: OrionInferenceLifecycleFactBuffer?,
    private val eventSource: OrionInferenceLifecycleEventSource? = null) {
    var packedParts: Long = -1L
        private set

    fun unavailable() { packedParts = -1L }

    /** No encoding, callback or clock. Failure invalidates both parts and never escapes to the guard. */
    fun record(text: Int, tool: Int, originalTotal: Int?) {
        try {
            packedParts = -1L
            if (originalTotal == null) return
            check(text >= 0 && tool >= 0 && originalTotal in 0..OrionInferenceLifecycle.MAX_CHUNK_CONTENT_BYTES &&
                text.toLong() + tool == originalTotal.toLong())
            packedParts = (text.toLong() shl 32) or tool.toLong()
        } catch (_: Throwable) { packedParts = -1L; owner?.failed(); eventSource?.recordingFailed() }
    }
}

/** Call-local bounded scalar bookkeeping only; it never stores a chunk or its content. */
internal class OrionLifecycleReleasedMetadata(private val owner: OrionInferenceLifecycleFactBuffer) {
    private var values: MutableList<OrionLifecycleChunkMetadata>? = ArrayList(9)
    fun released(sequence: Long, isFinal: Boolean, finish: OrionInferenceTerminal?, failure: OrionInferenceFailure?, bytes: Int,
        byteParts: Long = -1L) {
        val items = values ?: return
        try {
            check(items.size < 9)
            items.add(owner.chunk(sequence, isFinal, finish, failure, bytes, byteParts))
        } catch (_: Throwable) { values = null; owner.failed() }
    }
    fun handoff(): List<OrionLifecycleChunkMetadata>? {
        val items = values ?: return null
        values = null
        // Ownership transfer: no copy/content inspection. This mutable list is never used again.
        return Collections.unmodifiableList(items)
    }
}
