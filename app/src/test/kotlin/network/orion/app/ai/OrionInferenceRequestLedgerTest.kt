package network.orion.app.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.ai.api.*
import org.junit.Assert.*
import org.junit.Test

class OrionInferenceRequestLedgerTest {
    private val timedOut = OrionInferenceStatus(OrionInferenceStage.TERMINAL, OrionInferenceTerminal.TIMED_OUT, null)

    @Test fun `concurrent duplicate shares first result without another operation`() = runBlocking {
        val ledger = OrionInferenceRequestLedger()
        val id = ledger.newRequestId()
        val release = CompletableDeferred<Unit>()
        var operations = 0
        val original = async(start = CoroutineStart.UNDISPATCHED) { ledger.runOnce(id, "hash") {
            operations++; release.await(); timedOut
        } }
        val duplicate = async(start = CoroutineStart.UNDISPATCHED) { ledger.runOnce(id, "hash") {
            operations++; error("Duplicate dispatched")
        } }
        assertEquals(1, operations)
        assertFalse(duplicate.isCompleted)
        release.complete(Unit)
        withTimeout(1000) { assertEquals(timedOut, original.await()); assertEquals(timedOut, duplicate.await()) }
        assertEquals(timedOut, ledger.runOnce(id, "hash") { error("Terminal replay dispatched") })
        assertEquals(1, operations)
    }

    @Test fun `identity conflict cannot replace in flight or terminal result`() = runBlocking {
        val ledger = OrionInferenceRequestLedger()
        val id = ledger.newRequestId()
        val release = CompletableDeferred<Unit>()
        val original = async(start = CoroutineStart.UNDISPATCHED) { ledger.runOnce(id, "hash") { release.await(); timedOut } }
        assertTrue(runCatching { ledger.runOnce(id, "other") { timedOut } }.exceptionOrNull() is IllegalStateException)
        release.complete(Unit); original.await()
        assertTrue(runCatching { ledger.runOnce(id, "other") { timedOut } }.exceptionOrNull() is IllegalStateException)
        assertEquals(timedOut, ledger.runOnce(id, "hash") { error("Replay ran") })
    }

    @Test fun `cancelling duplicate waiter does not cancel original request`() = runBlocking {
        val ledger = OrionInferenceRequestLedger()
        val id = ledger.newRequestId()
        val release = CompletableDeferred<Unit>()
        val original = async(start = CoroutineStart.UNDISPATCHED) { ledger.runOnce(id, "hash") { release.await(); timedOut } }
        val duplicate = launch(start = CoroutineStart.UNDISPATCHED) { ledger.runOnce(id, "hash") { error("Duplicate ran") } }
        duplicate.cancelAndJoin()
        assertFalse(original.isCompleted)
        release.complete(Unit)
        assertEquals(timedOut, original.await())
    }

    @Test fun `cancelled owner caches one cancellation for duplicate and later replay`() = runBlocking {
        val ledger = OrionInferenceRequestLedger()
        val id = ledger.newRequestId()
        val hold = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { ledger.runOnce(id, "hash") { hold.await(); timedOut } }
        val duplicate = async(start = CoroutineStart.UNDISPATCHED) { ledger.runOnce(id, "hash") { error("Duplicate ran") } }
        owner.cancelAndJoin()
        val cached = withTimeout(1000) { duplicate.await() }
        assertEquals(OrionInferenceTerminal.CANCELLED, cached.terminal)
        assertEquals(cached, ledger.runOnce(id, "hash") { error("Replay ran") })
    }

    @Test fun `thousands of completions admit new work while retired identities never execute`() = runBlocking {
        val ledger = OrionInferenceRequestLedger()
        val first = ledger.newRequestId()
        assertEquals(timedOut, ledger.runOnce(first, "first") { timedOut })
        repeat(10_000) {
            assertEquals(timedOut, ledger.runOnce(ledger.newRequestId(), "hash$it") { timedOut })
        }
        assertTrue(runCatching { ledger.runOnce(first, "first") { error("Retired replay ran") } }
            .exceptionOrNull() is OrionRetiredRequestException)
        assertTrue(runCatching { ledger.runOnce(first, "changed") { error("Retired conflict ran") } }
            .exceptionOrNull() is OrionRetiredRequestException)
    }

    @Test fun `active owner and its duplicate survive retirement pressure`() = runBlocking {
        val ledger = OrionInferenceRequestLedger()
        val id = ledger.newRequestId()
        val release = CompletableDeferred<Unit>()
        val owner = async(start = CoroutineStart.UNDISPATCHED) { ledger.runOnce(id, "active") { release.await(); timedOut } }
        repeat(1000) { ledger.runOnce(ledger.newRequestId(), "completed") { timedOut } }
        val duplicate = async(start = CoroutineStart.UNDISPATCHED) { ledger.runOnce(id, "active") { error("Duplicate ran") } }
        assertFalse(owner.isCompleted); assertFalse(duplicate.isCompleted)
        release.complete(Unit)
        assertEquals(timedOut, owner.await()); assertEquals(timedOut, duplicate.await())
    }

    @Test fun `coroutine cancellation cannot overwrite an already observed timeout`() = runBlocking {
        val ledger = OrionInferenceRequestLedger()
        val id = ledger.newRequestId()
        val hold = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            ledger.runOnce(id, "hash", cancelledStatus = { timedOut }) { hold.await(); timedOut }
        }
        owner.cancelAndJoin()
        assertEquals(timedOut, ledger.runOnce(id, "hash") { error("Timed out replay ran") })
    }
}
