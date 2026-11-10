package network.orion.app

import network.orion.app.scheduling.OrionReconciliationResult
import network.orion.data.database.RetentionResult
import org.junit.Assert.*
import org.junit.Test

class OrionMaintenanceOutcomeTest {
    private val empty = RetentionResult(0, 0, 0, 0, 0)
    private val ready = OrionReconciliationResult(true, true)

    private fun outcome(pruned: RetentionResult = empty, expired: Int = 0,
        published: Boolean = true, dueEvents: Boolean = false,
        ingress: Boolean = false, registrations: OrionReconciliationResult = ready,
        deliveries: OrionReconciliationResult = ready) = maintenanceOutcome(
        pruned, 50, expired, 100, ingress, registrations, deliveries, published, dueEvents)

    @Test fun `each full retention table requests continuation until cleanup finishes`() {
        val batches = listOf(empty.copy(events = 50), empty.copy(ingress = 50),
            empty.copy(actions = 50), empty.copy(audit = 50), empty.copy(technical = 50))
        for (batch in batches) {
            assertTrue(outcome(pruned = batch).needsContinuation)
            assertFalse(outcome(pruned = batch).healthy)
        }
        assertFalse(outcome(pruned = RetentionResult(49, 49, 49, 49, 49)).needsContinuation)
        assertTrue(outcome().healthy)
    }

    @Test fun `full proposal expiration batch requests another bounded pass`() {
        assertTrue(outcome(expired = 100).needsContinuation)
        assertFalse(outcome(expired = 99).needsContinuation)
    }

    @Test fun `dead letter or deferred publication stays degraded without a continuation loop`() {
        val result = outcome(published = false)
        assertFalse(result.healthy)
        assertFalse(result.needsContinuation)
    }

    @Test fun `pending internal work continues even when publication is degraded`() {
        assertTrue(outcome(published = false, dueEvents = true).needsContinuation)
        assertTrue(outcome(published = false, ingress = true).needsContinuation)
        assertTrue(outcome(registrations = ready.copy(complete = false)).needsContinuation)
        assertTrue(outcome(deliveries = ready.copy(complete = false)).needsContinuation)
    }

    @Test fun `stable delivery or registration degradation is not success or unfinished work`() {
        for (result in listOf(outcome(registrations = ready.copy(healthy = false)),
            outcome(deliveries = ready.copy(healthy = false)))) {
            assertFalse(result.healthy)
            assertFalse(result.needsContinuation)
        }
    }
}
