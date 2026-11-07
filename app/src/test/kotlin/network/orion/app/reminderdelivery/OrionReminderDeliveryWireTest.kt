package network.orion.app.reminderdelivery

import java.time.Instant
import java.util.UUID
import network.orion.core.actions.ActionConfirmationSnapshot
import network.orion.core.orchestrator.reminders.ReminderDeliveryPreview
import org.junit.Assert.*
import org.junit.Test

class OrionReminderDeliveryWireTest {
    private val preview = ReminderDeliveryPreview(UUID.randomUUID(), 2, 3, Instant.ofEpochMilli(9000),
        UUID.randomUUID(), 1, "a".repeat(64), "reminder.delivery.v1")
    @Test fun confirmationWirePreservesOriginalTimesAndExactBinding() {
        val receipt = ActionConfirmationSnapshot(preview.actionId, "b".repeat(64), "b".repeat(64),
            preview.confirmationFingerprint, preview.policyVersion, Instant.ofEpochMilli(100),
            Instant.ofEpochMilli(10000), Instant.ofEpochMilli(100))
        val wire = OrionReminderDeliveryWire(true, preview, receipt)
        assertEquals(wire, OrionReminderDeliveryWire.decode(wire.encode()))
        assertThrows(IllegalArgumentException::class.java) { OrionReminderDeliveryWire.decode(wire.encode() + byteArrayOf(0)) }
    }
    @Test fun prepareWireContainsNoReceiptAndOversizedOrTruncatedInputFails() {
        val wire = OrionReminderDeliveryWire(false, preview.copy(actionVersion = 0, confirmationFingerprint = ""))
        assertEquals(wire, OrionReminderDeliveryWire.decode(wire.encode()))
        assertThrows(IllegalArgumentException::class.java) { OrionReminderDeliveryWire.decode(ByteArray(2049)) }
        assertThrows(java.io.EOFException::class.java) { OrionReminderDeliveryWire.decode(wire.encode().take(9).toByteArray()) }
    }
}
