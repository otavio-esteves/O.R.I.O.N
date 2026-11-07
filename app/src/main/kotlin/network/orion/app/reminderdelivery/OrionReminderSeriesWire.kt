package network.orion.app.reminderdelivery

import java.io.*
import java.time.LocalDateTime
import java.util.UUID
import network.orion.core.actions.ActionConfirmationSnapshot
import network.orion.core.model.OrionReminderRecurrenceCodec
import network.orion.core.orchestrator.reminders.*

/** Encrypted manifest carries only typed timing references and original per-action receipts. */
internal data class OrionReminderSeriesWire(val confirm: Boolean, val preview: ReminderSeriesPreview,
    val receipts: List<ActionConfirmationSnapshot> = emptyList()) {
    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            require(preview.occurrences.size in 2..31 && (!confirm || receipts.size == preview.occurrences.size))
            out.writeInt(1); out.writeBoolean(confirm); out.writeUTF(preview.seriesId.toString())
            out.writeUTF(OrionReminderRecurrenceCodec.encode(preview.rule)); out.writeUTF(preview.confirmationFingerprint)
            out.writeInt(preview.occurrences.size)
            preview.occurrences.forEachIndexed { index, occurrence ->
                val child = OrionReminderDeliveryWire(confirm, occurrence.delivery, if (confirm) receipts[index] else null).encode()
                out.writeInt(child.size); out.write(child)
                out.writeUTF(occurrence.requestedLocalDateTime.toString()); out.writeUTF(occurrence.resolvedLocalDateTime.toString())
                out.writeInt(occurrence.offsetSeconds); out.writeUTF(occurrence.decision)
            }
        }
    }.toByteArray().also { require(it.size <= MAX_BYTES) }
    companion object {
        const val MAX_BYTES = 65_536
        const val SCOPE = "reminder.delivery.series"
        const val SOURCE = "MAIN_UI"
        const val TYPE = "REMINDER_SERIES"
        fun decode(bytes: ByteArray): OrionReminderSeriesWire {
            require(bytes.size in 1..MAX_BYTES)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 1)
                val confirm = input.readBoolean()
                val id = UUID.fromString(input.readUTF())
                val rule = requireNotNull(OrionReminderRecurrenceCodec.decode(input.readUTF()))
                val fingerprint = input.readUTF()
                require(fingerprint.matches(Regex("[0-9a-f]{64}")))
                val count = input.readInt(); require(count in 2..31 && count == rule.count)
                val receipts = ArrayList<ActionConfirmationSnapshot>(count)
                val children = (0 until count).map {
                    val size = input.readInt(); require(size in 1..2048)
                    val childBytes = ByteArray(size); input.readFully(childBytes)
                    val child = try { OrionReminderDeliveryWire.decode(childBytes) } finally { childBytes.fill(0) }
                    require(child.confirm == confirm)
                    if (confirm) receipts += checkNotNull(child.receipt)
                    val requested = LocalDateTime.parse(input.readUTF())
                    val resolved = LocalDateTime.parse(input.readUTF())
                    val offset = input.readInt(); java.time.ZoneOffset.ofTotalSeconds(offset)
                    val decision = input.readUTF()
                    require(decision in setOf("NORMAL", "GAP_FIRST_VALID_TIME", "OVERLAP_PREVIOUS_OFFSET", "OVERLAP_EARLIER_OFFSET"))
                    ReminderSeriesOccurrence(child.preview, requested, resolved, offset, decision)
                }
                require(input.available() == 0)
                OrionReminderSeriesWire(confirm, ReminderSeriesPreview(id, rule, children, fingerprint), receipts)
            }
        }
    }
}
