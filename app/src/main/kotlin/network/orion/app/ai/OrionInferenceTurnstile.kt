package network.orion.app.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import network.orion.ai.api.OrionInferencePriority
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.MonotonicDeadline
import network.orion.core.time.MonotonicDeadlineStatus
import network.orion.core.time.OrionClock

internal class OrionInferenceQueueFullException : IllegalStateException("Cortex inference queue is full.")
internal class OrionInferenceQueueClosedException : IllegalStateException("Cortex inference queue is closed.")
internal class OrionInferenceQueueDeadlineException(val status: MonotonicDeadlineStatus) :
    IllegalStateException("Cortex queued deadline is no longer active.")

/** One ephemeral MAIN session, eight waiters, priority then FIFO; grants no execution authority. */
internal class OrionInferenceTurnstile(private val clock: OrionClock) : AutoCloseable {
    private class Turn(val priority: OrionInferencePriority, val sequence: Long, val createdAt: MonotonicInstant) {
        val ready = CompletableDeferred<Unit>()
    }

    private val monitor = Any()
    private val waiting = mutableListOf<Turn>()
    private var active: Turn? = null
    private var nextSequence = 0L
    private var closed = false

    suspend fun <T> withTurn(priority: OrionInferencePriority, deadline: MonotonicDeadline? = null, block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val (turn, granted) = synchronized(monitor) {
            val createdAt = clock.monotonicNow()
            if (closed) throw OrionInferenceQueueClosedException()
            requireActiveDeadline(deadline, createdAt)
            if (active != null && waiting.size >= MAX_WAITERS) throw OrionInferenceQueueFullException()
            val sequence = nextSequence
            nextSequence = Math.addExact(sequence, 1)
            val turn = Turn(priority, sequence, createdAt)
            val granted = active == null
            if (granted) active = turn else waiting.add(turn)
            turn to granted
        }
        // The cleanup region includes await: prompt cancellation after handoff
        // cannot abandon a slot whose continuation has not executed yet.
        try {
            if (granted) turn.ready.complete(Unit)
            if (deadline == null) turn.ready.await()
            else while (!turn.ready.isCompleted) {
                currentCoroutineContext().ensureActive()
                val now = clock.monotonicNow()
                requireActiveDeadline(deadline, now)
                if (now.elapsedSince(turn.createdAt) == null)
                    throw OrionInferenceQueueDeadlineException(MonotonicDeadlineStatus.INVALID_REFERENCE)
                // Coroutine time only schedules a wake-up; the injected boot-bound clock decides expiration.
                val wakeAfter = minOf(100L, deadline.expiresElapsedRealtimeMillis - now.elapsedRealtimeMillis)
                withTimeoutOrNull(wakeAfter) { turn.ready.await() }
            }
            turn.ready.await() // Propagate close/failure even when already completed exceptionally.
            currentCoroutineContext().ensureActive()
            synchronized(monitor) { if (closed) throw OrionInferenceQueueClosedException() }
            val now = clock.monotonicNow()
            requireActiveDeadline(deadline, now)
            check(now.elapsedSince(turn.createdAt) != null) { "Invalid inference admission time reference." }
            return block()
        } finally { release(turn) }
    }

    private fun requireActiveDeadline(deadline: MonotonicDeadline?, now: MonotonicInstant) {
        val status = deadline?.statusAt(now) ?: return
        if (status != MonotonicDeadlineStatus.ACTIVE) throw OrionInferenceQueueDeadlineException(status)
    }

    private fun release(turn: Turn) {
        val next = synchronized(monitor) {
            if (active !== turn) { waiting.remove(turn); return@synchronized null }
            active = null
            if (closed) return@synchronized null
            val next = waiting.minWithOrNull(compareBy<Turn>({ priorityOrder(it.priority) }, { it.sequence }))
            if (next != null) { waiting.remove(next); active = next }
            next
        }
        // Completing a deferred may resume an unconfined block synchronously.
        next?.ready?.complete(Unit)
    }

    override fun close() {
        val drained = synchronized(monitor) {
            if (closed) return
            closed = true
            waiting.toList().also { waiting.clear() }
        }
        drained.forEach { it.ready.completeExceptionally(OrionInferenceQueueClosedException()) }
    }

    private fun priorityOrder(priority: OrionInferencePriority) = when (priority) {
        OrionInferencePriority.INTERACTIVE -> 0
        OrionInferencePriority.USER_COMMAND -> 1
        OrionInferencePriority.REMINDER -> 2
        OrionInferencePriority.PROACTIVE -> 3
        OrionInferencePriority.BACKGROUND -> 4
    }

    companion object { private const val MAX_WAITERS = 8 }
}
