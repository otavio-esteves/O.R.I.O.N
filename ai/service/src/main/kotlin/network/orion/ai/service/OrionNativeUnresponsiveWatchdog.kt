package network.orion.ai.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import network.orion.core.time.MonotonicClock
import network.orion.core.time.MonotonicInstant

internal fun interface OrionWatchdogCancellation { fun cancel() }
internal interface OrionWatchdogScheduler : AutoCloseable {
    /** Must enqueue asynchronously, never invoke the task inside schedule(). */
    fun schedule(delayMillis: Long, task: () -> Unit): OrionWatchdogCancellation
}

/** No Android, native, callback, request or service monitor is acquired by this guard. */
internal class OrionNativeUnresponsiveWatchdog(private val clock: MonotonicClock,
    private val graceMillis: Long, private val scheduler: OrionWatchdogScheduler,
    private val terminateOwnProcess: () -> Unit) {
    internal class Ticket internal constructor(internal val id: Long)
    private data class State(val work: Map<Long, MonotonicInstant?> = emptyMap(),
        val closing: Boolean = false, val escalated: Boolean = false)
    private val state = AtomicReference(State())
    private val sequence = AtomicLong()
    private class Fence {
        private val cancelled = AtomicBoolean()
        private val cancellation = AtomicReference<OrionWatchdogCancellation?>()
        fun install(value: OrionWatchdogCancellation) {
            cancellation.set(value)
            if (cancelled.get()) cancellation.getAndSet(null)?.cancel()
        }
        fun cancel() { cancelled.set(true); cancellation.getAndSet(null)?.cancel() }
    }
    private val futures = ConcurrentHashMap<Long, Fence>()
    init { require(graceMillis > 0) }

    fun beginWork(armed: Boolean = false): Ticket {
        val id = sequence.updateAndGet { Math.addExact(it, 1) }
        while (true) {
            val old = state.get()
            check(!old.closing && !old.escalated && old.work.size < MAX_WORK)
            if (state.compareAndSet(old, old.copy(work = old.work + (id to null)))) break
        }
        return Ticket(id).also { if (armed) arm(it) }
    }

    fun arm(ticket: Ticket) {
        val started = clock.monotonicNow()
        while (true) {
            val old = state.get()
            if (old.escalated || !old.work.containsKey(ticket.id) || old.work[ticket.id] != null) return
            if (state.compareAndSet(old, old.copy(work = old.work + (ticket.id to started)))) break
        }
        schedule(ticket)
    }

    fun armAll() { state.get().work.keys.forEach { arm(Ticket(it)) } }

    /** Call only after the owned native call/cleanup has returned, from its worker finally. */
    fun finishWork(ticket: Ticket) {
        while (true) {
            val old = state.get()
            if (!old.work.containsKey(ticket.id)) return
            if (state.compareAndSet(old, old.copy(work = old.work - ticket.id))) break
        }
        futures.remove(ticket.id)?.cancel()
        closeIfQuiet()
    }

    /** Survives service destruction while any operation/queued cleanup remains busy. */
    fun closeWhenQuiet() {
        while (true) {
            val old = state.get()
            if (old.closing || state.compareAndSet(old, old.copy(closing = true))) break
        }
        armAll()
        closeIfQuiet()
    }

    private fun closeIfQuiet() {
        val current = state.get()
        if (current.closing && current.work.isEmpty()) scheduler.close()
    }

    private fun remaining(ticket: Ticket): Long? {
        val current = state.get()
        if (current.escalated) return null
        val started = current.work[ticket.id] ?: return null
        // Unknown boot, reboot or regression cannot justify letting armed native
        // work run indefinitely. Escalation still targets only this disposable process.
        val elapsed = clock.monotonicNow().elapsedSince(started)?.toMillis() ?: return 0
        return (graceMillis - elapsed).coerceAtLeast(0)
    }

    private fun schedule(ticket: Ticket) {
        val delay = remaining(ticket) ?: return
        val fence = Fence()
        if (futures.putIfAbsent(ticket.id, fence) != null) return
        fence.install(scheduler.schedule(delay) { checkDeadline(ticket, fence) })
        if (remaining(ticket) == null && futures.remove(ticket.id, fence)) fence.cancel()
    }

    private fun checkDeadline(ticket: Ticket, fence: Fence) {
        if (!futures.remove(ticket.id, fence)) return
        fence.cancel()
        while (true) {
            val old = state.get()
            val delay = remaining(ticket) ?: return
            if (delay > 0) { schedule(ticket); return }
            if (!old.work.containsKey(ticket.id) || old.work[ticket.id] == null || old.escalated) continue
            if (state.compareAndSet(old, old.copy(escalated = true))) {
                futures.values.forEach { it.cancel() }; futures.clear(); scheduler.close()
                terminateOwnProcess()
                return
            }
        }
    }

    companion object { private const val MAX_WORK = 4 }
}

/** Allocates its sole thread only for an armed fence; zero polling while idle/unarmed. */
internal class OrionNativeWatchdogScheduler : OrionWatchdogScheduler {
    private val executor = AtomicReference<ScheduledThreadPoolExecutor?>()
    private val closed = AtomicBoolean()
    override fun schedule(delayMillis: Long, task: () -> Unit): OrionWatchdogCancellation {
        if (closed.get()) return OrionWatchdogCancellation { }
        val pool = executor.get() ?: ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }.let { created ->
            if (executor.compareAndSet(null, created)) created else { created.shutdown(); checkNotNull(executor.get()) }
        }
        if (closed.get()) { pool.shutdown(); return OrionWatchdogCancellation { } }
        val future = try { pool.schedule(task, delayMillis, TimeUnit.MILLISECONDS) }
        catch (failure: java.util.concurrent.RejectedExecutionException) {
            if (closed.get()) return OrionWatchdogCancellation { } else throw failure
        }
        return OrionWatchdogCancellation { future.cancel(false) }
    }
    override fun close() { closed.set(true); executor.get()?.shutdown() }
}
