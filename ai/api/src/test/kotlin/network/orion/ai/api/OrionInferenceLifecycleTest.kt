package network.orion.ai.api

import java.time.Instant
import network.orion.core.ipc.IpcProtocolNegotiation
import network.orion.core.ipc.ProtocolInfo
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionClock
import org.junit.Assert.*
import org.junit.Test

class OrionInferenceLifecycleTest {
    private class Clock : OrionClock {
        var boot: BootSessionId? = BootSessionId(1)
        var elapsed = 100L
        var wall = Instant.EPOCH
        override fun monotonicNow() = MonotonicInstant(boot, elapsed)
        override fun wallNow() = wall
    }
    private val clock = Clock()
    private fun request(schema: Int = 1) = OrionInferenceRequest("r1", schema, "model-sha", "private prompt", "private context",
        128, 0.5, OrionInferencePriority.INTERACTIVE, clock.monotonicNow().deadlineAfter(100), "c1")
    private fun lifecycle() = OrionInferenceLifecycle(request(), clock)
    private fun chunk(seq: Long, text: String = "delta", final: OrionInferenceTerminal? = null,
        id: String = "r1", schema: Int = 1) = OrionInferenceChunk(id, schema, seq, textDelta = text,
            isFinal = final != null, finishReason = final)

    @Test fun `Cortex preserves baseline protocol negotiation`() {
        assertEquals(1, OrionCortexProtocol.CURRENT.protocolMajor)
        assertEquals(1, OrionCortexProtocol.CURRENT.protocolMinor)
        assertTrue(OrionCortexProtocol.CURRENT.negotiateWith(ProtocolInfo(2, 0)) is IpcProtocolNegotiation.MajorMismatch)
        assertEquals(1, (OrionCortexProtocol.CURRENT.negotiateWith(ProtocolInfo(1, 3)) as
            IpcProtocolNegotiation.Compatible).protocolMinor)
        val legacy = OrionCortexProtocol.CURRENT.negotiateWith(ProtocolInfo(1, 0)) as IpcProtocolNegotiation.Compatible
        assertEquals(0, legacy.protocolMinor)
        assertTrue(legacy.capabilities.isEmpty())
    }

    @Test fun `chunks are emitted once and in order including buffered terminal`() {
        val reducer = lifecycle()
        assertTrue(reducer.accept(chunk(2, "third", OrionInferenceTerminal.SUCCEEDED)).chunks.isEmpty())
        assertTrue(reducer.accept(chunk(1, "second")).chunks.isEmpty())
        val result = reducer.accept(chunk(0, "first"))
        assertEquals(listOf("first", "second", "third"), result.chunks.map { it.textDelta })
        assertEquals(OrionInferenceTerminal.SUCCEEDED, result.status.terminal)
        assertEquals(OrionInferenceReceipt.IGNORED_TERMINAL, reducer.accept(chunk(2)).receipt)
    }

    @Test fun `already delivered and matching buffered duplicates are ignored`() {
        val reducer = lifecycle()
        reducer.accept(chunk(0))
        assertEquals(OrionInferenceReceipt.DUPLICATE, reducer.accept(chunk(0)).receipt)
        reducer.accept(chunk(2))
        assertEquals(OrionInferenceReceipt.DUPLICATE, reducer.accept(chunk(2)).receipt)
        assertEquals(2, reducer.accept(chunk(1)).chunks.size)
    }

    @Test fun `conflicting buffered duplicate fails and clears pending content`() {
        val reducer = lifecycle()
        reducer.accept(chunk(1, "one"))
        val failed = reducer.accept(chunk(1, "changed"))
        assertEquals(OrionInferenceTerminal.FAILED, failed.status.terminal)
        assertEquals(OrionInferenceFailure.CONFLICTING_CHUNK, failed.status.failure)
        assertTrue(reducer.accept(chunk(0)).chunks.isEmpty())
    }

    @Test fun `excessive sequence gap and Long MAX fail without overflow`() {
        for (sequence in listOf(9L, Long.MAX_VALUE)) {
            assertEquals(OrionInferenceFailure.REORDER_LIMIT, lifecycle().accept(chunk(sequence)).status.failure)
        }
    }

    @Test fun `buffer content bytes are bounded independent of chunk count`() {
        val reducer = lifecycle()
        for (sequence in 1L..4L) assertNull(reducer.accept(chunk(sequence, "x".repeat(65536))).status.terminal)
        assertEquals(OrionInferenceFailure.REORDER_LIMIT,
            reducer.accept(chunk(5, "x")).status.failure)
    }

    @Test fun `individual decoded chunks are bounded in UTF8 bytes`() {
        assertEquals(OrionInferenceFailure.CHUNK_TOO_LARGE,
            lifecycle().accept(chunk(0, "é".repeat(32769))).status.failure)
        assertEquals(OrionInferenceFailure.CHUNK_TOO_LARGE,
            lifecycle().accept(chunk(0, "x".repeat(65537))).status.failure)
        assertEquals(1, lifecycle().accept(chunk(0, "x".repeat(65536))).chunks.size)
    }

    @Test fun `wrong request cannot terminate original but unsupported schema fails`() {
        val reducer = lifecycle()
        assertEquals(OrionInferenceReceipt.WRONG_REQUEST, reducer.accept(chunk(0, id = "other")).receipt)
        assertNull(reducer.status().terminal)
        assertEquals(OrionInferenceFailure.SCHEMA_MISMATCH, reducer.accept(chunk(0, schema = 2)).status.failure)
    }

    @Test fun `unsupported request schema is rejected before work`() {
        val reducer = OrionInferenceLifecycle(request(2), clock)
        assertEquals(OrionInferenceFailure.SCHEMA_MISMATCH, reducer.status().failure)
        assertFalse(reducer.markLoading())
    }

    @Test fun `cancel before start loading decoding and after terminal is idempotent`() {
        for (stage in 0..2) {
            val reducer = lifecycle()
            if (stage >= 1) assertTrue(reducer.markLoading())
            if (stage >= 2) assertTrue(reducer.markDecoding())
            assertEquals(OrionInferenceTerminal.CANCELLED, reducer.cancel().terminal)
            assertEquals(OrionInferenceTerminal.CANCELLED, reducer.cancel().terminal)
            assertFalse(reducer.markLoading())
            assertEquals(OrionInferenceTerminal.CANCELLED, reducer.processDied().terminal)
            assertTrue(reducer.accept(chunk(0, final = OrionInferenceTerminal.SUCCEEDED)).chunks.isEmpty())
        }
        val complete = lifecycle()
        complete.accept(chunk(0, final = OrionInferenceTerminal.SUCCEEDED))
        assertEquals(OrionInferenceTerminal.SUCCEEDED, complete.cancel().terminal)
    }

    @Test fun `deadline wins at exact boundary and ignores wall clock changes`() {
        val reducer = lifecycle()
        clock.wall = Instant.MAX
        clock.elapsed = 199
        assertNull(reducer.status().terminal)
        clock.wall = Instant.MIN
        clock.elapsed = 200
        assertEquals(OrionInferenceTerminal.TIMED_OUT,
            reducer.accept(chunk(0, final = OrionInferenceTerminal.SUCCEEDED)).status.terminal)
        assertEquals(OrionInferenceTerminal.TIMED_OUT, reducer.cancel().terminal)
    }

    @Test fun `boot change unavailable identity and monotonic rollback fail closed`() {
        for (scenario in 0..2) {
            clock.boot = BootSessionId(1); clock.elapsed = 100
            val reducer = lifecycle()
            when (scenario) {
                0 -> clock.boot = BootSessionId(2)
                1 -> clock.boot = null
                else -> clock.elapsed = 99
            }
            assertEquals(OrionInferenceFailure.INVALID_TIME_REFERENCE, reducer.status().failure)
            assertEquals(OrionInferenceTerminal.FAILED, reducer.status().terminal)
        }
    }

    @Test fun `death clears reorder data and late callback cannot recreate output`() {
        val reducer = lifecycle()
        reducer.accept(chunk(1))
        assertEquals(OrionInferenceTerminal.PROCESS_DIED, reducer.processDied().terminal)
        assertTrue(reducer.accept(chunk(0)).chunks.isEmpty())
        assertEquals(OrionInferenceTerminal.PROCESS_DIED, reducer.cancel().terminal)
    }

    @Test fun `one terminal wins concurrent cancel success timeout and process death`() {
        val reducer = lifecycle()
        val threads = listOf<() -> Unit>(
            { reducer.cancel() }, { reducer.processDied() },
            { reducer.accept(chunk(0, final = OrionInferenceTerminal.SUCCEEDED)) },
        ).map { Thread(it) }
        threads.forEach(Thread::start); threads.forEach(Thread::join)
        val terminal = reducer.status().terminal
        assertNotNull(terminal)
        clock.elapsed = 300
        assertEquals(terminal, reducer.status().terminal)
    }

    @Test fun `malformed values rejected and diagnostic strings omit content`() {
        assertThrows(IllegalArgumentException::class.java) { chunk(-1) }
        assertThrows(IllegalArgumentException::class.java) { chunk(0, id = "bad\nID") }
        assertThrows(IllegalArgumentException::class.java) {
            OrionInferenceChunk("r1", 1, 0, isFinal = true)
        }
        assertFalse(request().toString().contains("private"))
        assertFalse(chunk(0, "secret-value").toString().contains("secret-value"))
    }
}
