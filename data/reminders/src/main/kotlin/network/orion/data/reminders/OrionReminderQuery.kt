package network.orion.data.reminders

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import network.orion.core.commands.reminders.*
import network.orion.core.queries.reminders.*
import network.orion.core.security.*
import network.orion.data.database.OrionDatabase
import org.json.JSONObject

/** MAIN-only read adapter. Legacy opaque/corrupt content stays explicitly unreadable. */
class OrionReminderQuery(private val database: OrionDatabase, private val encryption: OrionFieldEncryption,
    private val clock: network.orion.core.time.WallClock) : ReminderQuery {
    override fun get(request: GetReminders): ReminderPage {
        val rows = database.reminders().page(request.after?.toString(), request.limit + 1)
        val selected = rows.take(request.limit).map(::select)
        return ReminderPage(selected, if (rows.size > request.limit) selected.last().reminderId else null)
    }

    fun find(id: UUID): ReminderSummary? = database.reminders().find(id.toString())?.let(::select)

    private fun select(row: network.orion.data.database.ReminderEntity): ReminderSummary {
            val id = UUID.fromString(row.reminderId)
            var state = ReminderContentState.UNREADABLE
            var title: String? = null
            val parsed = FieldEnvelopeCodec.decode(row.contentEnvelope)
            if (parsed is FieldEnvelopeDecodingResult.Decoded) {
                when (val result = encryption.decrypt(parsed.envelope, FieldEncryptionContext("reminder/$id", "content", 1))) {
                    is FieldDecryptionResult.Plaintext -> {
                        val clear = result.copyBytes()
                        try { title = decodeContent(id, clear); if (title != null) state = ReminderContentState.READABLE }
                        finally { clear.fill(0) }
                    }
                    is FieldDecryptionResult.Failure -> if (result.reason in setOf(FieldCryptoFailure.KEY_UNAVAILABLE,
                        FieldCryptoFailure.KEY_INVALIDATED, FieldCryptoFailure.CRYPTO_UNAVAILABLE)) state = ReminderContentState.KEY_UNAVAILABLE
                }
            }
            return ReminderSummary(id, title, ReminderState.valueOf(row.status.name), row.version, state, java.time.Instant.ofEpochMilli(row.scheduledAt), row.zoneId, row.precision.name, alertState(row))
    }

    private fun alertState(row: network.orion.data.database.ReminderEntity): ReminderAlertState {
        val actionId = row.deliveryActionId ?: return ReminderAlertState.DISABLED
        val action = database.actions().find(actionId)
        // Completed action history is prunable. The same committed delivery wrote
        // FIRED and lastFiredAt on the authoritative reminder; legacy rows lack it.
        // This is display evidence only and never grants delivery/retry authority.
        if (action == null && row.status == network.orion.data.database.ReminderStatus.FIRED &&
            row.fireGeneration > 0 && row.lastFiredAt?.let { it >= row.scheduledAt } == true)
            return ReminderAlertState.DELIVERED
        if (action?.status == network.orion.core.actions.ActionStatus.CONFIRMED &&
            action.confirmationExpiresAt?.let { clock.wallNow().toEpochMilli() >= it } == true) return ReminderAlertState.EXPIRED
        return when (action?.status) {
            network.orion.core.actions.ActionStatus.SUCCEEDED -> ReminderAlertState.DELIVERED
            network.orion.core.actions.ActionStatus.UNKNOWN, network.orion.core.actions.ActionStatus.MANUAL_REVIEW,
            network.orion.core.actions.ActionStatus.RUNNING -> ReminderAlertState.UNCERTAIN
            network.orion.core.actions.ActionStatus.CONFIRMED -> if (!row.registrationConfirmed || row.precision == network.orion.data.database.ReminderPrecision.UNSCHEDULED)
                ReminderAlertState.AWAITING_SCHEDULE else ReminderAlertState.SCHEDULED
            else -> ReminderAlertState.EXPIRED
        }
    }

    private fun decodeContent(id: UUID, bytes: ByteArray): String? {
        if (bytes.size !in 1..8192) return null
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val json = JSONObject(text)
            if (json.length() != 4 || json.opt("reminderId") != id.toString() || json.opt("title") !is String) return null
            val command = CreateReminder(id, json.getString("title"), java.time.Instant.ofEpochMilli(json.getLong("scheduledAtEpochMillis")), java.time.ZoneId.of(json.getString("zoneId")), id, id)
            val ready = OrionCreateReminderPreparer.prepare(command) as? CreateReminderPreparation.Ready ?: return null
            ready.reminder.title.takeIf { ready.reminder.canonicalNormalizedArguments == text }
        } catch (_: Exception) { null }
    }
}
