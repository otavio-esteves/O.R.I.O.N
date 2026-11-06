package network.orion.app.reminders

import java.time.Instant
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.time.WallClock
import org.junit.Assert.*
import org.junit.Test

class OrionReminderIngressWireTest {
    private fun draft() = OrionReminderIngressWire("CREATE", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Revisar relatório")
    @Test fun closedWireRoundTripsAndRedactsUserContent() {
        val wire = draft()
        val bytes = wire.encode()
        val decoded = OrionReminderIngressWire.decode(bytes)
        assertArrayEquals(bytes, decoded.encode()); assertEquals(wire.title, decoded.title)
        assertFalse(wire.toString().contains(wire.title))
    }
    @Test fun payloadVersionTrailingBytesOversizeAndMalformedInputAreRejected() {
        val bytes = draft().encode()
        assertThrows(IllegalArgumentException::class.java) { OrionReminderIngressWire.decode(bytes + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { OrionReminderIngressWire.decode(ByteArray(8193)) }
        bytes[3] = 2
        assertThrows(IllegalArgumentException::class.java) { OrionReminderIngressWire.decode(bytes) }
    }
    @Test fun confirmationContainsReferencesAndPreservesExactOriginalTimes() {
        val at = Instant.parse("2026-11-05T04:00:00Z")
        val id = UUID.randomUUID()
        val receipt = ActionConfirmationSnapshot(id, "a", "a", "b", "v1", at, at.plusSeconds(60), at)
        val wire = OrionReminderIngressWire("CREATE", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            actionId = id, actionVersion = 1, fingerprint = "b", policyVersion = "v1", receipt = receipt)
        val decoded = OrionReminderIngressWire.decode(wire.encode())
        assertEquals("", decoded.title); assertEquals(receipt, decoded.receipt)
    }
    @Test fun replayTraceDoesNotChangeCanonicalOperation() {
        val first = draft()
        val replay = OrionReminderIngressWire(first.operation, first.reminderId, first.key, UUID.randomUUID(), first.title)
        assertArrayEquals(first.encode(), replay.encode())
    }
    @Test fun nonCanonicalTitleAndConfirmationPayloadCopyAreRejected() {
        val draft = draft()
        val nonCanonical = OrionReminderIngressWire("CREATE", draft.reminderId, draft.key, draft.trace, " title ")
        assertThrows(IllegalArgumentException::class.java) { OrionReminderIngressWire.decode(nonCanonical.encode()) }
        val copy = OrionReminderIngressWire("CREATE", draft.reminderId, draft.key, draft.trace, draft.title, actionId = UUID.randomUUID())
        assertThrows(IllegalArgumentException::class.java) { OrionReminderIngressWire.decode(copy.encode()) }
    }
}
