package network.orion.app.ai

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.time.Instant
import network.orion.ai.api.*
import network.orion.app.resources.OrionAndroidResourceSnapshot
import network.orion.app.resources.OrionCortexResourceFact
import network.orion.core.resources.*
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionClock
import org.junit.Assert.*
import org.junit.Test

/** Pure mechanical observations. No owner invocation, model, Android or qualification evidence. */
class OrionCortexCycleObservationTest {
    private val owner = Any()
    private val client = Any()
    private val status = OrionInferenceStatus(OrionInferenceStage.TERMINAL, OrionInferenceTerminal.SUCCEEDED, null)
    private val original = OrionCortexRunResult.Completed(status)
    private fun token(observer: OrionCortexCycleObserver) = checkNotNull(observer.begin(owner)).also {
        assertSame(it, observer.claim(owner, it))
    }
    private fun read(observer: OrionCortexCycleObserver, token: OrionCortexCycleToken) =
        checkNotNull(observer.prepareRead(owner, token)).let { checkNotNull(observer.completeRead(it, null, false)) }

    @Test fun `early refusal retains only actual result and no invented native or stream fields`() {
        val observer = OrionCortexCycleObserver(); val token = token(observer)
        val unavailable = OrionCortexRunResult.Unavailable(OrionCortexUnavailable.NO_QUALIFIED_PROFILE)
        observer.noAttempt(token); observer.returned(token, unavailable)
        val receipt = read(observer, token)
        assertSame(unavailable, receipt.originalResult)
        assertEquals(OrionCycleState.NO_ATTEMPT, receipt.state)
        assertNull(receipt.trustedModelSha256); assertNull(receipt.nativeMetrics); assertNull(receipt.stream)
        assertNull(receipt.unload); assertTrue(receipt.resources.isEmpty())
        assertFalse(receipt.actualTurnEntered == true); assertFalse(receipt.inferenceAccepted == true)
        assertEquals("NOT_QUALIFIED", receipt.qualification); assertFalse(receipt.fullF5GateClosed)
    }

    @Test fun `retained result cannot become another executed cycle`() {
        val observer = OrionCortexCycleObserver(); val token = token(observer)
        observer.retained(token); observer.returned(token, original)
        val receipt = read(observer, token)
        assertEquals(OrionCycleState.RETAINED_RESULT, receipt.state)
        assertSame(original, receipt.originalResult); assertNull(receipt.stream); assertNull(receipt.nativeMetrics)
        assertFalse(checkNotNull(observer.prepareRead(owner, token)).mayQuery)
    }

    @Test fun `logical ownership without an actual turn does not prove execution`() {
        val observer = OrionCortexCycleObserver(); val token = token(observer)
        observer.logicalOwner(token, "mechanical-issued-id")
        observer.clientResult(token, false); observer.returned(token, original)
        val receipt = read(observer, token)
        assertEquals(OrionCycleSource.LOGICAL_OWNER, receipt.source)
        assertTrue(OrionCycleIssue.NO_ACTUAL_TURN in receipt.issues)
        assertFalse(receipt.actualTurnEntered == true); assertNull(receipt.stream)
    }

    @Test fun `missing logical owner event cannot be reinterpreted as retained replay`() {
        val observer = OrionCortexCycleObserver(); val token = token(observer)
        observer.failed(token) // Actual ownership event was unavailable.
        observer.clientResult(token, false); observer.returned(token, original)
        val receipt = read(observer, token)
        assertNotEquals(OrionCycleSource.RETAINED_RESULT, receipt.source)
        assertNull(receipt.actualTurnEntered)
        assertTrue(OrionCycleIssue.LOSS in receipt.issues)
    }

    @Test fun `stream counts are observations and original exception stays separate from result`() {
        val observer = OrionCortexCycleObserver(); val token = token(observer)
        observer.logicalOwner(token, "mechanical-id"); observer.turn(token, client)
        observer.streamAttempt(token, 2)
        observer.threw(token, OrionCycleThrown.OTHER)
        val receipt = read(observer, token)
        assertNull(receipt.originalResult); assertEquals(OrionCycleThrown.OTHER, receipt.thrown)
        assertEquals(OrionCycleStreamSummary(2, 1, 0), receipt.stream)
        assertNull(receipt.nativeMetrics)
        assertThrows(UnsupportedOperationException::class.java) { (receipt.issues as MutableSet<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (receipt.resources as MutableList<*>).clear() }
    }

    @Test fun `pending reads do not claim metrics and only one completed manual reader may query`() {
        val observer = OrionCortexCycleObserver(); val token = token(observer)
        observer.logicalOwner(token, "mechanical-id"); observer.turn(token, client)
        observer.inferenceAccepted(token, OrionCycleEndpoint(client, 1, 42))
        val early = checkNotNull(observer.prepareRead(owner, token))
        assertFalse(early.mayQuery); assertEquals(OrionCycleState.PENDING, early.base.state)
        observer.returned(token, original)
        val first = checkNotNull(observer.prepareRead(owner, token)); assertTrue(first.mayQuery)
        val concurrent = checkNotNull(observer.prepareRead(owner, token)); assertFalse(concurrent.mayQuery)
        assertEquals(OrionCycleState.PENDING, observer.completeRead(concurrent, null, false)?.state)
        val complete = checkNotNull(observer.completeRead(first, null, false))
        assertSame(complete, read(observer, token)); assertFalse(checkNotNull(observer.prepareRead(owner, token)).mayQuery)
        assertTrue(complete.retainedSnapshot)
    }

    @Test fun `unloaded reply is retained without post reply time or native release proof`() {
        val observer = OrionCortexCycleObserver(); val token = token(observer)
        observer.turn(token, client)
        val at = MonotonicInstant(BootSessionId(1), 1000)
        val fact = OrionCycleUnloadFact(OrionCycleEndpoint(client, 1, 42), at.deadlineAfter(5000), at,
            false, true, true, null)
        observer.unloaded(token, fact); observer.returned(token, original)
        val receipt = read(observer, token)
        assertSame(fact, receipt.unload)
        assertTrue(OrionCycleIssue.POST_REPLY_TIME_UNAVAILABLE in receipt.issues)
        assertEquals(OrionCycleState.INCOMPLETE, receipt.state); assertNull(receipt.nativeMetrics)
    }

    @Test fun `foreign owner cannot consume observation and explicit release is bounded`() {
        val observer = OrionCortexCycleObserver(1); val token = checkNotNull(observer.begin(owner))
        assertNull(observer.claim(Any(), token)); assertSame(token, observer.claim(owner, token))
        assertNull(observer.begin(owner)); assertNull(observer.prepareRead(Any(), token))
        assertFalse(observer.release(owner, token))
        observer.noAttempt(token); observer.returned(token, OrionCortexRunResult.Unavailable(OrionCortexUnavailable.CLOSED))
        assertTrue(observer.release(owner, token)); assertNull(observer.prepareRead(owner, token))
        assertNotNull(observer.begin(owner))
    }

    @Test fun `contention drops final record without blocking original caller or inventing completion`() {
        val observer = OrionCortexCycleObserver(); val token = token(observer)
        val lock = observer.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(observer) as ReentrantLock
        val worker = Executors.newSingleThreadExecutor()
        lock.lock()
        try {
            worker.submit(Callable { observer.returned(token, original) }).get(1, TimeUnit.SECONDS)
        } finally { lock.unlock(); worker.shutdownNow() }
        val receipt = read(observer, token)
        assertEquals(OrionCycleState.PENDING, receipt.state)
        assertTrue(OrionCycleIssue.LOSS in receipt.issues); assertNull(receipt.originalResult)
        assertNull(receipt.actualTurnEntered); assertFalse(observer.release(owner, token))
    }

    @Test fun `diagnostic failure and reused token never execute arbitrary work or reset old observation`() {
        val observer = OrionCortexCycleObserver(); val token = token(observer)
        assertNull(observer.isolatedCycle(token) { throw OutOfMemoryError("Mechanical diagnostic fault") })
        observer.noAttempt(token); observer.returned(token, OrionCortexRunResult.Unavailable(OrionCortexUnavailable.CLOSED))
        val original = read(observer, token)
        assertTrue(OrionCycleIssue.LOSS in original.issues)
        assertNull(observer.claim(owner, token)); assertSame(original, read(observer, token))
        val disabled: OrionCortexCycleObserver? = null
        assertNull(disabled.isolatedCycle { error("Disabled collector must stay lazy") })
    }

    @Test fun `capacity cannot be expanded into unbounded history`() {
        for (capacity in listOf(0, 17, Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { OrionCortexCycleObserver(capacity) }
    }

    @Test fun `resource scopes require exact token client kind and thread without reevaluation or new clocks`() {
        val at = MonotonicInstant(BootSessionId(1), 1000)
        var reads = 0
        val clock = object : OrionClock {
            override fun monotonicNow() = at.also { reads++ }
            override fun wallNow(): Instant = error("No wall clock expected")
        }
        val model = InferenceModelProfile("a".repeat(64), 100)
        val plan = OrionInferenceResourcePlan(model, "MECHANICAL_NOT_QUALIFIED", 1, 128, 8, 0,
            10_000, 20_000, 100, 20, 2)
        val sample = OrionResourceObservation(at, model, 80, 0, true, OrionProcessImportance.FOREGROUND, false,
            OrionMemoryPressure.NONE, 100, 200, 100_000, emptyList(), 1, 128, 8)
        val snapshot = OrionAndroidResourceSnapshot(sample, OrionAiExitHistory(at, emptyList()), 100, emptySet())
        val decision = OrionMinimumResourceGovernor(clock, { sample }, listOf(plan), 1000, 1000).evaluate()
        val before = reads
        val observer = OrionCortexCycleObserver(); val actual = token(observer); val wrong = token(observer)
        observer.turn(actual, client); observer.turn(wrong, client)
        observer.governor(OrionCycleCallbackScope(actual, client, OrionCycleCallback.RESOURCE, OrionCyclePhase.INFERENCE,
            Thread.currentThread()), OrionCycleEndpoint(client, 1, 42), snapshot, decision)
        observer.governor(OrionCycleCallbackScope(wrong, client, OrionCycleCallback.RESTART, OrionCyclePhase.LOAD,
            Thread.currentThread()), null, snapshot, decision)
        observer.governor(OrionCycleCallbackScope(wrong, client, OrionCycleCallback.RESOURCE, OrionCyclePhase.LOAD,
            Thread()), null, snapshot, decision)
        observer.returned(actual, original); observer.returned(wrong, original)
        val receipt = read(observer, actual)
        val fact = receipt.resources.single().fact as OrionCortexResourceFact.Governor
        assertEquals(decision.allowed, fact.decision.allowedAsReported); assertEquals(at, fact.decision.sampledAt)
        assertEquals(before, reads)
        assertTrue(receipt.droppedAtLeast >= 2) // Observer-global counts; not this token's loss.
        assertFalse(OrionCycleIssue.LOSS in receipt.issues)
        assertTrue(OrionCycleIssue.LOSS in read(observer, wrong).issues)
        assertTrue(read(observer, wrong).resources.isEmpty())
    }
}
