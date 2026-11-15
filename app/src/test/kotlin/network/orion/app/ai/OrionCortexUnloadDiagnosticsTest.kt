package network.orion.app.ai

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionClock
import org.junit.Assert.*
import org.junit.Test

class OrionCortexUnloadDiagnosticsTest {
    private val at = MonotonicInstant(BootSessionId(4), 1000)
    private val selected = OrionCortexUnloadOwnerStamp(3, 1, 1, false)
    private fun owner() = OrionCortexUnloadObservationOwner().apply { update(selected) }
    private fun facts(stamp: OrionCortexUnloadOwnerStamp = selected) = OrionCortexUnloadFacts(
        1, stamp, null, at.deadlineAfter(5000), at, false, null, true, true, true, false)

    private class Clock(private val value: MonotonicInstant, private val block: Boolean = false,
        private val fail: Boolean = false) : OrionClock {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        override fun monotonicNow(): MonotonicInstant {
            calls.incrementAndGet(); entered.countDown()
            if (block) check(release.await(5, TimeUnit.SECONDS))
            if (fail) error("Technical clock unavailable")
            return value
        }
        override fun wallNow() = Instant.EPOCH
    }

    private fun idle() {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (OrionCortexUnloadDiagnosticDispatcher.hasPendingSample()) {
            assertTrue("Diagnostic slot was not released", System.nanoTime() < end)
            Thread.sleep(1)
        }
    }

    @Test fun `blocked provider occupies one global slot across closed and newly created clients`() {
        idle()
        val clock = Clock(at, block = true)
        val originalOwner = owner()
        val originalBuffer = OrionCortexUnloadObservationBuffer()
        assertTrue(OrionCortexUnloadDiagnosticDispatcher.submit(
            OrionCortexUnloadDiagnosticTicket(clock, originalOwner, facts(), originalBuffer)))
        try {
            assertTrue(clock.entered.await(1, TimeUnit.SECONDS))
            originalOwner.update(selected.copy(closed = true))
            repeat(20) {
                val nextClock = Clock(at)
                val nextBuffer = OrionCortexUnloadObservationBuffer()
                assertFalse(OrionCortexUnloadDiagnosticDispatcher.submit(
                    OrionCortexUnloadDiagnosticTicket(nextClock, owner(), facts(), nextBuffer)))
                assertEquals(0, nextClock.calls.get())
                val batch = nextBuffer.drain()
                assertTrue(batch.observations.isEmpty())
                assertEquals(1L, batch.samplingBusyDroppedAtLeast)
                assertEquals(1L, batch.droppedAtLeast)
                assertEquals(0L, batch.recordingFailuresAtLeast)
                assertTrue(batch.lossAccountingComplete)
            }
            assertEquals(1, clock.calls.get())
        } finally { clock.release.countDown(); idle() }
        assertEquals(OrionCortexUnloadOutcome.CLIENT_CLOSED, originalBuffer.drain().observations.single().outcome)
        val nextBuffer = OrionCortexUnloadObservationBuffer()
        assertTrue(OrionCortexUnloadDiagnosticDispatcher.submit(
            OrionCortexUnloadDiagnosticTicket(Clock(at), owner(), facts(), nextBuffer)))
        idle()
        assertEquals(OrionCortexUnloadOutcome.UNLOADED_OBSERVED, nextBuffer.drain().observations.single().outcome)
    }

    @Test fun `permanent disable invalidates blocked pending ticket and cannot be revived by later updates`() {
        idle()
        val clock = Clock(at, block = true)
        val owner = owner()
        val buffer = OrionCortexUnloadObservationBuffer()
        assertTrue(OrionCortexUnloadDiagnosticDispatcher.submit(OrionCortexUnloadDiagnosticTicket(clock, owner, facts(), buffer)))
        try {
            assertTrue(clock.entered.await(1, TimeUnit.SECONDS))
            owner.disable()
            assertTrue(owner.isDisabled())
            owner.update(selected.copy(endpointVersion = 4, executionEpoch = 2))
            assertTrue(owner.isDisabled())
        } finally { clock.release.countDown(); idle() }
        assertEquals(OrionCortexUnloadOutcome.OBSERVATION_UNAVAILABLE, buffer.drain().observations.single().outcome)
        // Neither a fresh matching stamp nor another finite worker job can reset disable.
        owner.update(selected)
        val later = Clock(at)
        assertTrue(OrionCortexUnloadDiagnosticDispatcher.submit(OrionCortexUnloadDiagnosticTicket(later, owner, facts(), buffer)))
        idle()
        assertEquals(1, later.calls.get())
        assertTrue(owner.isDisabled())
        assertEquals(OrionCortexUnloadOutcome.OBSERVATION_UNAVAILABLE, buffer.drain().observations.single().outcome)
        // The global slot still serves a different healthy owner, rather than being abandoned.
        assertTrue(OrionCortexUnloadDiagnosticDispatcher.submit(OrionCortexUnloadDiagnosticTicket(Clock(at), this.owner(), facts(), buffer)))
        idle()
        assertEquals(OrionCortexUnloadOutcome.UNLOADED_OBSERVED, buffer.drain().observations.single().outcome)
    }

    @Test fun `same endpoint cannot confirm old cleanup after another actual execution starts`() {
        idle()
        val clock = Clock(at, block = true)
        val owner = owner()
        val buffer = OrionCortexUnloadObservationBuffer()
        assertTrue(OrionCortexUnloadDiagnosticDispatcher.submit(OrionCortexUnloadDiagnosticTicket(clock, owner, facts(), buffer)))
        try {
            assertTrue(clock.entered.await(1, TimeUnit.SECONDS))
            owner.update(selected.copy(executionEpoch = 2))
        } finally { clock.release.countDown(); idle() }
        val event = buffer.drain().observations.single()
        assertEquals(OrionCortexUnloadOutcome.EXECUTION_CHANGED, event.outcome)
        assertEquals(1L, event.executionEpoch)
        assertEquals(1L, event.connectionEpoch)
    }

    @Test fun `endpoint replacement and closure during sampling invalidate pending confirmation`() {
        for ((state, expected) in listOf(
            selected.copy(endpointVersion = 4) to OrionCortexUnloadOutcome.ENDPOINT_CHANGED,
            selected.copy(connectionEpoch = 2) to OrionCortexUnloadOutcome.ENDPOINT_CHANGED,
            selected.copy(closed = true) to OrionCortexUnloadOutcome.CLIENT_CLOSED)) {
            idle()
            val clock = Clock(at, block = true)
            val owner = owner()
            val buffer = OrionCortexUnloadObservationBuffer()
            assertTrue(OrionCortexUnloadDiagnosticDispatcher.submit(OrionCortexUnloadDiagnosticTicket(clock, owner, facts(), buffer)))
            try {
                assertTrue(clock.entered.await(1, TimeUnit.SECONDS))
                owner.update(state)
            } finally { clock.release.countDown(); idle() }
            assertEquals(expected, buffer.drain().observations.single().outcome)
        }
    }

    @Test fun `unavailable version never creates confirmation`() {
        for (stamp in listOf(selected.copy(executionEpoch = null), selected.copy(endpointVersion = null),
            selected.copy(connectionEpoch = null), selected.copy(executionEpoch = 0), selected.copy(connectionEpoch = 0))) {
            val owner = OrionCortexUnloadObservationOwner().apply { update(stamp) }
            val buffer = OrionCortexUnloadObservationBuffer()
            owner.publish(facts(stamp), at, buffer)
            val event = buffer.drain().observations.single()
            assertEquals(OrionCortexUnloadOutcome.IDENTITY_UNAVAILABLE, event.outcome)
            if (stamp.connectionEpoch == 0L) assertNull(event.connectionEpoch)
            if (stamp.executionEpoch == 0L) assertNull(event.executionEpoch)
        }
    }

    @Test fun `async unavailable clock never confirms and frees slot for subsequent observation`() {
        idle()
        val buffer = OrionCortexUnloadObservationBuffer()
        assertTrue(OrionCortexUnloadDiagnosticDispatcher.submit(
            OrionCortexUnloadDiagnosticTicket(Clock(at, fail = true), owner(), facts(), buffer)))
        idle()
        val unavailable = buffer.drain().observations.single()
        assertEquals(OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID, unavailable.outcome)
        assertNull(unavailable.checkedAt)
        assertTrue(OrionCortexUnloadDiagnosticDispatcher.submit(
            OrionCortexUnloadDiagnosticTicket(Clock(at), owner(), facts(), buffer)))
        idle()
        assertEquals(OrionCortexUnloadOutcome.UNLOADED_OBSERVED, buffer.drain().observations.single().outcome)
    }

    @Test fun `functional failure stays distinct from later endpoint retirement and missing diagnostic time`() {
        val owner = owner().apply { update(selected.copy(endpointVersion = 4)) }
        for (failure in listOf(OrionCortexUnloadOutcome.TRANSPORT_FAILURE, OrionCortexUnloadOutcome.UNLOAD_REQUEST_FAILED,
            OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID, OrionCortexUnloadOutcome.POLL_TIMEOUT)) {
            val buffer = OrionCortexUnloadObservationBuffer()
            owner.publish(facts().copy(unloadedReply = false, failure = failure), null, buffer)
            assertEquals(failure, buffer.drain().observations.single().outcome)
        }
    }

    @Test fun `nonterminal loop exit keeps original pre release facts rather than inferring confirmation`() {
        val owner = owner().apply { update(selected.copy(endpointVersion = 4)) }
        val buffer = OrionCortexUnloadObservationBuffer()
        val expired = MonotonicInstant(BootSessionId(4), 6000)
        owner.publish(facts().copy(unloadedReply = false, pollAt = expired), at, buffer)
        val event = buffer.drain().observations.single()
        assertEquals(OrionCortexUnloadOutcome.DEADLINE_EXPIRED, event.outcome)
        assertEquals(expired, event.checkedAt)
    }

    @Test fun `late sampler cannot restart deadline or ignore pre reply clock regression`() {
        for ((checked, expected) in listOf(
            MonotonicInstant(BootSessionId(4), 6000) to OrionCortexUnloadOutcome.DEADLINE_EXPIRED,
            MonotonicInstant(BootSessionId(5), 2000) to OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID,
            MonotonicInstant(BootSessionId(4), 3000) to OrionCortexUnloadOutcome.TIME_REFERENCE_INVALID)) {
            val buffer = OrionCortexUnloadObservationBuffer()
            owner().publish(facts().copy(pollAt = MonotonicInstant(BootSessionId(4), 4000)), checked, buffer)
            assertEquals(expected, buffer.drain().observations.single().outcome)
        }
    }
}
