package network.orion.ai.api

import org.junit.Assert.*
import org.junit.Test

class OrionIssuedRequestHistoryTest {
    private class Entry(var terminal: Boolean)
    private fun history(capacity: Int = 64) = OrionIssuedRequestHistory<Entry>(capacity) { it.terminal }

    @Test fun thousandsOfCompletedRequestsStayBoundedAndRetiredIdsNeverBecomeNew() {
        val history = history()
        val first = history.issue()
        history.put(first, Entry(true))
        repeat(10_000) {
            val id = history.issue()
            history.put(id, Entry(true))
            assertNotNull(history.find(id))
            assertTrue(history.retainedCount <= 64)
        }
        assertThrows(OrionRetiredRequestException::class.java) { history.find(first) }
        assertThrows(OrionRetiredRequestException::class.java) { history.put(first, Entry(false)) }
    }

    @Test fun oldestLiveRequestSurvivesRetirementPressureAndRemainsDeduplicated() {
        val history = history(2)
        val id = history.issue(); val active = Entry(false)
        history.put(id, active)
        repeat(1000) { history.put(history.issue(), Entry(true)) }
        assertSame(active, history.find(id))
        assertEquals(2, history.retainedCount)
        active.terminal = true
        repeat(2) { history.put(history.issue(), Entry(true)) }
        assertThrows(OrionRetiredRequestException::class.java) { history.find(id) }
    }

    @Test fun allLiveCapacityRejectsAdmissionUntilOneCompletes() {
        val history = history(2)
        val first = Entry(false)
        history.put(history.issue(), first)
        history.put(history.issue(), Entry(false))
        val waiting = history.issue()
        assertFalse(history.hasCapacity())
        assertThrows(OrionRequestHistoryFullException::class.java) { history.put(waiting, Entry(false)) }
        first.terminal = true
        assertTrue(history.hasCapacity())
        history.put(waiting, Entry(false))
        assertEquals(2, history.retainedCount)
    }

    @Test fun delayedUnsubmittedIdentityBelowRetirementWatermarkCannotStart() {
        val history = history(1)
        val delayed = history.issue()
        val completed = history.issue()
        history.put(completed, Entry(true))
        history.put(history.issue(), Entry(true))
        assertThrows(OrionRetiredRequestException::class.java) { history.find(delayed) }
        assertThrows(OrionRetiredRequestException::class.java) { history.put(delayed, Entry(false)) }
    }

    @Test fun foreignUnissuedAndNoncanonicalIdentitiesCannotEnterHistory() {
        val history = history()
        val id = history.issue()
        val foreign = OrionIssuedRequestHistory<Entry> { it.terminal }.issue()
        for (invalid in listOf(foreign, id.substringBeforeLast('/') + "/2",
            id.substringBeforeLast('/') + "/01", "arbitrary", "")) {
            assertThrows(IllegalArgumentException::class.java) { history.find(invalid) }
            assertThrows(IllegalArgumentException::class.java) { history.put(invalid, Entry(false)) }
        }
        assertEquals(0, history.retainedCount)
    }

    @Test fun issuingUnusedReservationsDoesNotRetainUnboundedEntries() {
        val history = history()
        repeat(10_000) { history.issue() }
        assertEquals(0, history.retainedCount)
    }

    @Test fun sequenceExhaustionFailsClosedWithoutWrappingOrReusingIdentity() {
        val history = OrionIssuedRequestHistory<Entry>(2, "test_scope", Long.MAX_VALUE - 1) { it.terminal }
        val last = history.issue()
        history.put(last, Entry(true))
        assertThrows(IllegalStateException::class.java) { history.issue() }
        assertTrue(last.endsWith("/${Long.MAX_VALUE}"))
        assertNotNull(history.find(last))
    }
}
