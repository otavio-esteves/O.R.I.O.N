package network.orion.app.ai

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import network.orion.ai.api.OrionCortexWire
import network.orion.ai.api.OrionInferencePriority
import org.junit.Assert.*
import org.junit.Test

/** Mechanical limits/claim controls, never a trusted operational configuration. */
class OrionCortexSingleAttemptTest {
    private fun template(context: Int = 128, threads: Int = 1, tokens: Int = 8, duration: Long = 1000,
        temperature: Double = 0.0, prompt: String = "SECRET_FIXED_WORKLOAD", history: String = "",
        hash: String = "a".repeat(64)) = OrionCortexAttemptTemplate(hash, context, threads, tokens, duration,
            temperature, OrionInferencePriority.INTERACTIVE, prompt, history)

    @Test fun explicitRequestFieldsAreValidatedWithoutInventingAChosenPoint() {
        assertTrue(template().isValid())
        assertTrue(template(context = OrionCortexWire.MAX_CONTEXT, threads = OrionCortexWire.MAX_THREADS,
            tokens = OrionCortexWire.MAX_TOKENS, duration = 120_000, temperature = 2.0).isValid())
        assertTrue(template(duration = 0).isValid()) // Original request contract: immediately expired, never an admission.
        listOf(template(context = 31), template(context = OrionCortexWire.MAX_CONTEXT + 1),
            template(threads = 0), template(threads = OrionCortexWire.MAX_THREADS + 1), template(tokens = 0),
            template(tokens = OrionCortexWire.MAX_TOKENS + 1), template(duration = -1), template(duration = 120_001),
            template(temperature = Double.NaN), template(temperature = Double.POSITIVE_INFINITY),
            template(temperature = -0.1), template(temperature = 2.1), template(hash = "0".repeat(64)),
            template(hash = "SECRET_IDENTIFIER"), template(prompt = "")).forEach { assertFalse(it.isValid()) }
    }

    @Test fun completeUtf8WorkloadAndContextAreBoundedWithoutLoggingTheirContent() {
        assertFalse(template(prompt = "a".repeat(OrionCortexWire.MAX_INPUT_BYTES)).isValid())
        assertFalse(template(prompt = "é".repeat(OrionCortexWire.MAX_INPUT_BYTES / 2)).isValid())
        assertFalse(template(prompt = "a", history = "b".repeat(OrionCortexWire.MAX_INPUT_BYTES - 1)).isValid())
        assertFalse(template().toString().contains("SECRET"))
        assertFalse(template().toString().contains("a".repeat(64)))
    }

    @Test fun foreignOwnerAndDriverCannotConsumeAnOtherwiseUnusedClaim() {
        val owner = Any(); val driver = Any(); val claim = OrionCortexAttemptClaim(owner, driver)
        assertEquals(OrionCortexAttemptRefusal.OWNER_UNAVAILABLE, claim.consume(Any(), driver))
        assertEquals(OrionCortexAttemptRefusal.OWNER_UNAVAILABLE, claim.consume(owner, Any()))
        assertNull(claim.consume(owner, driver))
        assertEquals(OrionCortexAttemptRefusal.ALREADY_CONSUMED, claim.consume(owner, driver))
        assertEquals(OrionCortexAttemptRefusal.OWNER_UNAVAILABLE, claim.consume(Any(), driver))
    }

    @Test fun simultaneousClaimsHaveExactlyOneWinnerWithoutHistoryOrRetry() {
        val owner = Any(); val driver = Any(); val claim = OrionCortexAttemptClaim(owner, driver)
        val start = CountDownLatch(1); val done = CountDownLatch(8); val winners = AtomicInteger()
        val refusals = AtomicInteger()
        repeat(8) { Thread {
            try {
                check(start.await(3, TimeUnit.SECONDS))
                when (claim.consume(owner, driver)) {
                    null -> winners.incrementAndGet()
                    OrionCortexAttemptRefusal.ALREADY_CONSUMED -> refusals.incrementAndGet()
                    else -> error("Unexpected claim result")
                }
            } finally { done.countDown() }
        }.start() }
        start.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, winners.get()); assertEquals(7, refusals.get())
    }

    @Test fun refusalCodesAreClosedDistinctAndSelectionIsNotAnApproval() {
        assertEquals((1..12).toSet(), OrionCortexAttemptRefusal.entries.map { it.code }.toSet())
        assertEquals(12, OrionCortexAttemptRefusal.entries.size)
    }

    @Test fun onlyAnUnclaimedExactOwnerReservationCanBeDiscarded() {
        val observer = OrionCortexCycleObserver(1); val owner = Any()
        val first = checkNotNull(observer.begin(owner))
        assertFalse(observer.discardUnclaimed(Any(), first))
        assertFalse(observer.release(owner, first))
        assertNull(observer.begin(owner))
        assertTrue(observer.discardUnclaimed(owner, first))
        assertFalse(observer.discardUnclaimed(owner, first))
        val claimed = checkNotNull(observer.begin(owner)); assertSame(claimed, observer.claim(owner, claimed))
        assertFalse(observer.discardUnclaimed(owner, claimed)); assertFalse(observer.release(owner, claimed))
        observer.threw(claimed, OrionCycleThrown.CANCELLED)
        assertFalse(observer.discardUnclaimed(owner, claimed)); assertTrue(observer.release(owner, claimed))
        assertNotNull(observer.begin(owner))
    }

    @Test fun lostUnclaimedReservationCannotBeTreatedAsProofOfNoInvocation() {
        val observer = OrionCortexCycleObserver(1); val owner = Any()
        val token = checkNotNull(observer.begin(owner))
        val lock = observer.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(observer) as ReentrantLock
        val entered = CountDownLatch(1); val leave = CountDownLatch(1)
        val holder = Thread { lock.lock(); try { entered.countDown(); check(leave.await(3, TimeUnit.SECONDS)) } finally { lock.unlock() } }
        holder.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNull(observer.claim(owner, token)) // Original run may proceed without capture after this loss.
        } finally { leave.countDown(); holder.join(3000) }
        assertFalse(holder.isAlive); assertTrue(token.lost)
        assertFalse(observer.discardUnclaimed(owner, token))
        assertNull(observer.begin(owner))
        assertFalse(observer.release(owner, token))
    }
}
