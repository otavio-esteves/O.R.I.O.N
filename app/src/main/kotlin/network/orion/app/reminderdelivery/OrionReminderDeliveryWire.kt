package network.orion.app.reminderdelivery

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.UUID
import network.orion.core.actions.ActionConfirmationSnapshot
import network.orion.core.orchestrator.reminders.ReminderDeliveryPreview

/** Only bounded references and an original exact confirmation receipt enter encrypted ingress. */
internal data class OrionReminderDeliveryWire(
    val confirm: Boolean,
    val preview: ReminderDeliveryPreview,
    val receipt: ActionConfirmationSnapshot? = null,
) {
    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeBoolean(confirm)
            out.writeUTF(preview.reminderId.toString()); out.writeLong(preview.reminderVersion)
            out.writeLong(preview.generation); out.writeLong(preview.scheduledAt.toEpochMilli())
            out.writeUTF(preview.actionId.toString()); out.writeLong(preview.actionVersion)
            out.writeUTF(preview.confirmationFingerprint); out.writeUTF(preview.policyVersion)
            if (confirm) {
                val original = checkNotNull(receipt)
                out.writeUTF(original.actionId.toString()); out.writeUTF(original.normalizedPayloadHash)
                out.writeUTF(original.confirmedPayloadHash); out.writeUTF(original.confirmationFingerprint)
                out.writeUTF(original.policyVersionAtConfirmation); out.writeLong(original.confirmedAt.toEpochMilli())
                out.writeLong(checkNotNull(original.confirmationExpiresAt).toEpochMilli())
                out.writeLong(original.payloadLockedAt.toEpochMilli())
            }
        }
    }.toByteArray()

    companion object {
        const val SCOPE = "reminder.delivery.ui"
        const val SOURCE = "MAIN_UI"
        const val TYPE = "REMINDER_DELIVERY"
        fun decode(bytes: ByteArray): OrionReminderDeliveryWire {
            require(bytes.size in 1..2048)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 1)
                val confirm = input.readBoolean()
                val preview = ReminderDeliveryPreview(UUID.fromString(input.readUTF()), input.readLong(),
                    input.readLong(), Instant.ofEpochMilli(input.readLong()), UUID.fromString(input.readUTF()),
                    input.readLong(), input.readUTF(), input.readUTF())
                require(preview.reminderVersion in 0 until Long.MAX_VALUE && preview.generation in 1 until Long.MAX_VALUE)
                require(preview.actionVersion in 0 until Long.MAX_VALUE && preview.policyVersion.length in 1..96)
                require(!confirm || preview.confirmationFingerprint.matches(Regex("[a-f0-9]{64}")))
                val receipt = if (confirm) ActionConfirmationSnapshot(UUID.fromString(input.readUTF()),
                    input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(),
                    Instant.ofEpochMilli(input.readLong()), Instant.ofEpochMilli(input.readLong()),
                    Instant.ofEpochMilli(input.readLong())) else null
                require(receipt == null || receipt.actionId == preview.actionId)
                require(input.available() == 0)
                OrionReminderDeliveryWire(confirm, preview, receipt)
            }
        }
    }
}
