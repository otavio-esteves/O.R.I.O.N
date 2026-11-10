package network.orion.app.ai

import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.ai.api.OrionInferencePriority
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.MonotonicDeadlineStatus
import network.orion.core.time.OrionClock
import org.junit.Assert.*
import org.junit.Test

class OrionInferenceTurnstileTest {
    private class Clock : OrionClock {
        var mark = MonotonicInstant(BootSessionId(1), 1_000)
        var wall = Instant.EPOCH
        override fun monotonicNow() = mark
        override fun wallNow() = wall
    }

    /** Holds an already-granted continuation before its code can enter the block. */
    private class PausedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private val clock = Clock()
    private val priority = OrionInferencePriority.INTERACTIVE

    @Test fun `waiting requests honor priority and FIFO ties despite wall changes`() = runBlocking {
        val gate = OrionInferenceTurnstile(clock)
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val active = launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(OrionInferencePriority.BACKGROUND) {
            order.add("active"); release.await()
        } }
        val inputs = listOf("background" to OrionInferencePriority.BACKGROUND,
            "reminder1" to OrionInferencePriority.REMINDER, "interactive1" to priority,
            "command" to OrionInferencePriority.USER_COMMAND, "interactive2" to priority,
            "proactive" to OrionInferencePriority.PROACTIVE, "reminder2" to OrionInferencePriority.REMINDER)
        val waiting = inputs.map { (name, requestedPriority) ->
            clock.wall = if (name == "command") Instant.MIN else Instant.MAX
            launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(requestedPriority) { order.add(name) } }
        }
        assertEquals(listOf("active"), order)
        release.complete(Unit)
        withTimeout(1_000) { active.join(); waiting.joinAll() }
        assertEquals(listOf("active", "interactive1", "interactive2", "command", "reminder1",
            "reminder2", "proactive", "background"), order)
    }

    @Test fun `capacity permits eight waiters and rejects a ninth without starting its block`() = runBlocking {
        val gate = OrionInferenceTurnstile(clock)
        val release = CompletableDeferred<Unit>()
        val active = launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(priority) { release.await() } }
        var completed = 0
        val waiting = List(8) { launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(priority) { completed++ } } }
        val overflow = runCatching { gate.withTurn(priority) { fail("Overflow block ran.") } }
        assertTrue(overflow.exceptionOrNull() is OrionInferenceQueueFullException)
        assertEquals(0, completed)
        release.complete(Unit)
        withTimeout(1_000) { active.join(); waiting.joinAll() }
        assertEquals(8, completed)
    }

    @Test fun `cancelling a waiting request recovers its capacity and never starts it`() = runBlocking {
        val gate = OrionInferenceTurnstile(clock)
        val release = CompletableDeferred<Unit>()
        val active = launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(priority) { release.await() } }
        var cancelledStarted = false
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(priority) { cancelledStarted = true } }
        cancelled.cancelAndJoin()
        var completed = 0
        val waiting = List(8) { launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(priority) { completed++ } } }
        release.complete(Unit)
        withTimeout(1_000) { active.join(); waiting.joinAll() }
        assertFalse(cancelledStarted)
        assertEquals(8, completed)
    }

    @Test fun `cancellation after grant before continuation runs releases the handed off slot`() = runBlocking {
        val gate = OrionInferenceTurnstile(clock)
        val dispatcher = PausedDispatcher()
        val release = CompletableDeferred<Unit>()
        val active = launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(priority) { release.await() } }
        var cancelledStarted = false
        val cancelled = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            gate.withTurn(priority) { cancelledStarted = true }
        }
        release.complete(Unit)
        active.join() // The selected waiter's continuation is now held by dispatcher.
        var followerStarted = false
        val follower = launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(priority) { followerStarted = true } }
        cancelled.cancel()
        dispatcher.runAll()
        withTimeout(1_000) { cancelled.join(); follower.join() }
        assertFalse(cancelledStarted)
        assertTrue(followerStarted)
    }

    @Test fun `active cancellation hands ownership to the next request exactly once`() = runBlocking {
        val gate = OrionInferenceTurnstile(clock)
        val hold = CompletableDeferred<Unit>()
        var inside = 0
        var maximumInside = 0
        val active = launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(priority) {
            inside++; maximumInside = maxOf(maximumInside, inside)
            try { hold.await() } finally { inside-- }
        } }
        val followers = List(8) { launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(priority) {
            inside++; maximumInside = maxOf(maximumInside, inside)
            kotlinx.coroutines.yield()
            inside--
        } } }
        active.cancelAndJoin()
        withTimeout(1_000) { followers.joinAll() }
        assertEquals(1, maximumInside); assertEquals(0, inside)
    }

    @Test fun `block failure releases a slot without swallowing the original exception`() = runBlocking {
        val gate = OrionInferenceTurnstile(clock)
        val failure = IllegalArgumentException("synthetic")
        val result = runCatching { gate.withTurn(priority) { throw failure } }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(42, gate.withTurn(priority) { 42 })
    }

    @Test fun `close drains waiting requests rejects new work and lets owner clean up active work`() = runBlocking {
        val gate = OrionInferenceTurnstile(clock)
        val release = CompletableDeferred<Unit>()
        var activeCompleted = false
        val active = launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(priority) {
            release.await(); activeCompleted = true
        } }
        val failures = mutableListOf<Throwable?>()
        val waiting = List(8) { launch(start = CoroutineStart.UNDISPATCHED) {
            failures.add(runCatching { gate.withTurn(priority) { fail("Closed waiter ran.") } }.exceptionOrNull())
        } }
        gate.close(); gate.close()
        withTimeout(1_000) { waiting.joinAll() }
        assertEquals(8, failures.size)
        assertTrue(failures.all { it is OrionInferenceQueueClosedException })
        assertTrue(runCatching { gate.withTurn(priority) { fail("Closed gate ran.") } }.exceptionOrNull() is OrionInferenceQueueClosedException)
        assertFalse(activeCompleted)
        release.complete(Unit); active.join()
        assertTrue(activeCompleted)
    }

    @Test fun `boot change while waiting prevents dispatch and releases its granted slot`() = runBlocking {
        val gate = OrionInferenceTurnstile(clock)
        val release = CompletableDeferred<Unit>()
        val active = launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(priority) { release.await() } }
        var failure: Throwable? = null
        var dispatched = false
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            failure = runCatching { gate.withTurn(priority) { dispatched = true } }.exceptionOrNull()
        }
        clock.mark = MonotonicInstant(BootSessionId(2), 10)
        release.complete(Unit)
        withTimeout(1_000) { active.join(); waiter.join() }
        assertFalse(dispatched); assertTrue(failure is IllegalStateException)
        assertEquals(42, gate.withTurn(priority) { 42 })
    }

    @Test fun `unavailable boot identity fails without dispatch and without retaining ownership`() = runBlocking {
        val gate = OrionInferenceTurnstile(clock)
        clock.mark = MonotonicInstant(null, 0)
        val result = runCatching { gate.withTurn(priority) { fail("Invalid clock dispatched.") } }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        clock.mark = MonotonicInstant(BootSessionId(1), 1)
        assertEquals(42, gate.withTurn(priority) { 42 })
    }

    @Test fun `queued expiry returns before active release and frees all eight waiting places`() = runBlocking {
        val gate = OrionInferenceTurnstile(clock)
        val release = CompletableDeferred<Unit>()
        val active = launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(priority) { release.await() } }
        val deadline = clock.mark.deadlineAfter(250)
        val expired = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { gate.withTurn(priority, deadline) { fail("Expired request dispatched.") } }.exceptionOrNull()
        }
        clock.mark = MonotonicInstant(BootSessionId(1), 1250)
        val failure = withTimeout(1000) { expired.await() }
        assertTrue(failure is OrionInferenceQueueDeadlineException)
        assertEquals(MonotonicDeadlineStatus.EXPIRED, (failure as OrionInferenceQueueDeadlineException).status)
        assertFalse(active.isCompleted)
        var dispatched = 0
        val waiting = List(8) { launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(priority) { dispatched++ } } }
        release.complete(Unit)
        withTimeout(1000) { active.join(); waiting.joinAll() }
        assertEquals(8, dispatched)
    }

    @Test fun `deadline after handoff before continuation runs releases slot without executing`() = runBlocking {
        val gate = OrionInferenceTurnstile(clock)
        val dispatcher = PausedDispatcher()
        val release = CompletableDeferred<Unit>()
        val active = launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(priority) { release.await() } }
        var failure: Throwable? = null
        val expired = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            failure = runCatching { gate.withTurn(priority, clock.mark.deadlineAfter(250)) { fail("Expired handoff ran.") } }.exceptionOrNull()
        }
        release.complete(Unit); active.join()
        clock.mark = MonotonicInstant(BootSessionId(1), 1250)
        var followerRan = false
        val follower = launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(priority) { followerRan = true } }
        dispatcher.runAll()
        withTimeout(1000) { expired.join(); follower.join() }
        assertTrue(failure is OrionInferenceQueueDeadlineException)
        assertTrue(followerRan)
    }

    @Test fun `boot change or rollback expires queued ownership while active session remains busy`() = runBlocking {
        for (invalid in listOf(MonotonicInstant(BootSessionId(2), 10), MonotonicInstant(BootSessionId(1), 999),
            MonotonicInstant(null, 1100))) {
            clock.mark = MonotonicInstant(BootSessionId(1), 1000)
            val gate = OrionInferenceTurnstile(clock)
            val release = CompletableDeferred<Unit>()
            val active = launch(start = CoroutineStart.UNDISPATCHED) { gate.withTurn(priority) { release.await() } }
            val expired = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { gate.withTurn(priority, clock.mark.deadlineAfter(250)) { fail("Invalid reference ran.") } }.exceptionOrNull()
            }
            clock.mark = invalid
            val failure = withTimeout(1000) { expired.await() } as OrionInferenceQueueDeadlineException
            assertEquals(MonotonicDeadlineStatus.INVALID_REFERENCE, failure.status)
            assertFalse(active.isCompleted)
            release.complete(Unit); active.join()
        }
    }
}
