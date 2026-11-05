package network.orion.app.tasks

import java.time.Instant
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.time.WallClock
import org.junit.Assert.*
import org.junit.Test

class OrionTaskIngressWireTest {
    private fun draft() = OrionTaskIngressWire("CREATE", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Revisar relatório")
    @Test fun closedWireRoundTripsAndRedactsUserContent() {
        val wire = draft()
        val bytes = wire.encode()
        val decoded = OrionTaskIngressWire.decode(bytes)
        assertArrayEquals(bytes, decoded.encode()); assertEquals(wire.title, decoded.title)
        assertFalse(wire.toString().contains(wire.title))
    }
    @Test fun payloadVersionTrailingBytesOversizeAndMalformedInputAreRejected() {
        val bytes = draft().encode()
        assertThrows(IllegalArgumentException::class.java) { OrionTaskIngressWire.decode(bytes + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { OrionTaskIngressWire.decode(ByteArray(8193)) }
        bytes[3] = 2
        assertThrows(IllegalArgumentException::class.java) { OrionTaskIngressWire.decode(bytes) }
    }
    @Test fun confirmationContainsReferencesAndPreservesExactOriginalTimes() {
        val at = Instant.parse("2026-11-05T04:00:00Z")
        val id = UUID.randomUUID()
        val receipt = ActionConfirmationSnapshot(id, "a", "a", "b", "v1", at, at.plusSeconds(60), at)
        val wire = OrionTaskIngressWire("CREATE", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            actionId = id, actionVersion = 1, fingerprint = "b", policyVersion = "v1", receipt = receipt)
        val decoded = OrionTaskIngressWire.decode(wire.encode())
        assertEquals("", decoded.title); assertEquals(receipt, decoded.receipt)
    }
    @Test fun replayTraceDoesNotChangeCanonicalOperation() {
        val first = draft()
        val replay = OrionTaskIngressWire(first.operation, first.taskId, first.key, UUID.randomUUID(), first.title)
        assertArrayEquals(first.encode(), replay.encode())
    }
    @Test fun nonCanonicalTitleAndConfirmationPayloadCopyAreRejected() {
        val draft = draft()
        val nonCanonical = OrionTaskIngressWire("CREATE", draft.taskId, draft.key, draft.trace, " title ")
        assertThrows(IllegalArgumentException::class.java) { OrionTaskIngressWire.decode(nonCanonical.encode()) }
        val copy = OrionTaskIngressWire("CREATE", draft.taskId, draft.key, draft.trace, draft.title, actionId = UUID.randomUUID())
        assertThrows(IllegalArgumentException::class.java) { OrionTaskIngressWire.decode(copy.encode()) }
    }
}
