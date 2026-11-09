package network.orion.ai.api

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class OrionCortexStreamLedgerTest {
    private val id = UUID.randomUUID()
    @Test fun `duplicates late signals and repeated terminals never republish`() {
        val ledger = OrionCortexStreamLedger(); assertTrue(ledger.register(id))
        val delta = OrionCortexChunk(id, 0, "private")
        assertEquals(listOf(delta), ledger.accept(delta)); assertTrue(ledger.accept(delta).isEmpty())
        assertEquals(1, ledger.accept(OrionCortexChunk(id, 1, terminal = OrionCortexRequestStatus.SUCCEEDED)).size)
        assertTrue(ledger.terminate(id, OrionCortexRequestStatus.PROCESS_DIED).isEmpty())
        assertTrue(ledger.accept(OrionCortexChunk(id, 2, "late")).isEmpty())
        assertFalse(ledger.register(id)); assertFalse(delta.toString().contains("private"))
    }
    @Test fun `bounded reordering drains in sequence`() {
        val ledger = OrionCortexStreamLedger(); ledger.register(id)
        assertTrue(ledger.accept(OrionCortexChunk(id, 1, "b")).isEmpty())
        assertEquals(listOf("a", "b"), ledger.accept(OrionCortexChunk(id, 0, "a")).map { it.textDelta })
        assertEquals(OrionCortexRequestStatus.RUNNING, ledger.status(id))
    }
    @Test fun `reordering overflow and conflicting buffered sequence fail once`() {
        for (overflow in listOf(true, false)) {
            val ledger = OrionCortexStreamLedger(); ledger.register(id)
            ledger.accept(OrionCortexChunk(id, 1, "a"))
            val result = ledger.accept(OrionCortexChunk(id, if (overflow) 5 else 1, "b"))
            assertEquals(OrionCortexRequestStatus.FAILED, result.single().terminal)
            assertTrue(ledger.accept(OrionCortexChunk(id, 0, "a")).isEmpty())
        }
    }
    @Test fun `terminal after timeout death and cancellation wins exactly once`() {
        for (status in listOf(OrionCortexRequestStatus.TIMED_OUT, OrionCortexRequestStatus.CANCELLED, OrionCortexRequestStatus.PROCESS_DIED)) {
            val ledger = OrionCortexStreamLedger(); ledger.register(id)
            assertEquals(status, ledger.terminate(id, status).single().terminal)
            assertTrue(ledger.accept(OrionCortexChunk(id, 0, terminal = OrionCortexRequestStatus.SUCCEEDED)).isEmpty())
        }
    }
    @Test fun `capacity refuses new IDs without forgetting duplicate tombstones`() {
        val ledger = OrionCortexStreamLedger(1); assertTrue(ledger.register(id))
        ledger.terminate(id, OrionCortexRequestStatus.SUCCEEDED)
        assertFalse(ledger.register(UUID.randomUUID())); assertFalse(ledger.register(id))
    }
    @Test fun `unicode splitting preserves supplementary characters and rejects malformed input`() {
        val text = "a".repeat(4095) + "\uD83D\uDE80" + "b".repeat(4096)
        val parts = orionCortexTextParts(text)
        assertEquals(text, parts.joinToString("")); parts.forEach { assertTrue(it.length <= 4096) }
        assertThrows(IllegalArgumentException::class.java) { orionCortexTextParts("\uD800") }
        assertThrows(IllegalArgumentException::class.java) { OrionCortexChunk(id, 0, "\uDC00") }
    }
    @Test fun `output budget is enforced without retaining unbounded history`() {
        val ledger = OrionCortexStreamLedger(); ledger.register(id)
        repeat(64) { assertEquals(1, ledger.accept(OrionCortexChunk(id, it.toLong(), "x".repeat(4096))).size) }
        assertEquals(OrionCortexRequestStatus.FAILED, ledger.accept(OrionCortexChunk(id, 64, "x")).single().terminal)
    }
}
