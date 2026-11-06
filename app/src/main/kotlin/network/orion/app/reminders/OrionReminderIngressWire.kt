package network.orion.app.reminders

import java.io.*
import java.time.Instant
import java.util.UUID
import network.orion.core.actions.ActionConfirmationSnapshot
import network.orion.core.commands.reminders.*
import network.orion.core.model.Sensitivity
import network.orion.data.database.*
import network.orion.data.repository.LocalIngressPayload

/** Closed encrypted wire. No authorization, policy decision or presence field is accepted. */
internal class OrionReminderIngressWire(
    val operation: String, val reminderId: UUID, val key: UUID, val trace: UUID,
    val title: String = "", val expectedVersion: Long = 0,
    val actionId: UUID? = null, val actionVersion: Long = 0,
    val fingerprint: String = "", val policyVersion: String = "",
    val receipt: ActionConfirmationSnapshot? = null,
    val scheduledAtMillis: Long = 0, val zoneId: String = "UTC",
) {
    fun create() = CreateReminder(reminderId, title, Instant.ofEpochMilli(scheduledAtMillis), validatedZone(zoneId), key, trace)
    fun change(): ReminderStatusCommand = if (operation == "SNOOZE") SnoozeReminder(reminderId, expectedVersion, Instant.ofEpochMilli(scheduledAtMillis), validatedZone(zoneId), key, trace)
        else CancelReminder(reminderId, expectedVersion, key, trace)
    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
        out.writeInt(1); listOf(operation, reminderId.toString(), key.toString(), title).forEach(out::writeUTF)
        out.writeLong(expectedVersion); out.writeLong(scheduledAtMillis); out.writeUTF(zoneId); out.writeUTF(actionId?.toString() ?: ""); out.writeLong(actionVersion)
        out.writeUTF(fingerprint); out.writeUTF(policyVersion); out.writeBoolean(receipt != null)
        receipt?.let { r ->
            listOf(r.actionId.toString(), r.normalizedPayloadHash, r.confirmedPayloadHash, r.confirmationFingerprint,
                r.policyVersionAtConfirmation).forEach(out::writeUTF)
            out.writeLong(r.confirmedAt.toEpochMilli()); out.writeLong(checkNotNull(r.confirmationExpiresAt).toEpochMilli())
            out.writeLong(r.payloadLockedAt.toEpochMilli())
        }
    } }.toByteArray()
    override fun toString() = "OrionReminderIngressWire(<redacted>)"
    companion object {
        private fun validatedZone(id: String): java.time.ZoneId = try { java.time.ZoneId.of(id) }
            catch (_: java.time.DateTimeException) { throw IllegalArgumentException("Invalid reminder zone.") }
        const val SCOPE = "orion_ui_reminder_v1"
        const val SOURCE = "MAIN_UI_REMINDER"
        const val TYPE = "ReminderRequest"
        fun decode(bytes: ByteArray): OrionReminderIngressWire {
            require(bytes.size in 1..8192)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 1)
                val op = input.readUTF(); require(op in setOf("CREATE", "SNOOZE", "CANCEL", "DISMISS"))
                val id = UUID.fromString(input.readUTF()); val key = UUID.fromString(input.readUTF()); val trace = UUID(0, 0)
                val title = input.readUTF(); val version = input.readLong(); val scheduled = input.readLong(); val zone = input.readUTF()
                val action = input.readUTF().takeIf { it.isNotEmpty() }?.let(UUID::fromString); val av = input.readLong()
                val fingerprint = input.readUTF(); val policy = input.readUTF()
                val receipt = if (!input.readBoolean()) null else ActionConfirmationSnapshot(UUID.fromString(input.readUTF()),
                    input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), Instant.ofEpochMilli(input.readLong()),
                    Instant.ofEpochMilli(input.readLong()), Instant.ofEpochMilli(input.readLong()))
                require(input.available() == 0 && version >= 0 && av >= 0)
                OrionReminderIngressWire(op, id, key, trace, title, version, action, av, fingerprint, policy, receipt, scheduled, zone).also {
                    require(it.encode().contentEquals(bytes))
                    if (op == "CREATE") {
                        if (action == null) {
                            val ready = OrionCreateReminderPreparer.prepare(it.create()) as? CreateReminderPreparation.Ready
                            require(ready?.reminder?.title == title)
                        } else require(title.isEmpty())
                    } else if (op != "DISMISS") require(OrionReminderStatusPreparer.prepare(it.change()) is ReminderStatusPreparation.Ready)
                    require(receipt == null || action != null && receipt.actionId == action)
                    if (op != "CREATE") require(title.isEmpty())
                    if (op in setOf("CANCEL", "DISMISS") || op == "CREATE" && action != null) require(scheduled == 0L && zone == "UTC")
                    if (op == "DISMISS") require(action != null && receipt == null)
                    else if (action != null) require(receipt != null)
                    else require(receipt == null && av == 0L && fingerprint.isEmpty() && policy.isEmpty())
                }
            }
        }
    }
}

/** Only a decrypted/authenticated queue callback can create replay origin. Foreground alone cannot. */
internal class OrionVerifiedReminderIngress private constructor(val wire: OrionReminderIngressWire) {
    override fun toString() = "OrionVerifiedReminderIngress(<redacted>)"
    companion object {
        fun from(payload: LocalIngressPayload, database: OrionDatabase,
            actions: network.orion.data.actions.OrionActionStore): OrionVerifiedReminderIngress {
            check(database.inTransaction())
            val row = checkNotNull(database.ingress().find(payload.ingressId.toString()))
            require(row.status == DurableIngressStatus.PROCESSING && row.source == OrionReminderIngressWire.SOURCE &&
                payload.scope == OrionReminderIngressWire.SCOPE && payload.commandType == OrionReminderIngressWire.TYPE &&
                payload.schemaVersion == 1 && payload.sensitivity == Sensitivity.SENSITIVE && payload.referenceId == null)
            val bytes = checkNotNull(payload.copyContent())
            return try {
                val decoded = OrionReminderIngressWire.decode(bytes)
                // Trace metadata is deliberately outside the canonical idempotency material.
                val wire = OrionReminderIngressWire(decoded.operation, decoded.reminderId, decoded.key, UUID.fromString(row.correlationId),
                    decoded.title, decoded.expectedVersion, decoded.actionId, decoded.actionVersion, decoded.fingerprint,
                    decoded.policyVersion, decoded.receipt, decoded.scheduledAtMillis, decoded.zoneId)
                if (wire.operation == "CREATE" && wire.actionId != null) {
                    // A confirmation persists references and receipt metadata, never another title copy.
                    require(wire.title.isEmpty())
                    val stored = when (val read = actions.read(wire.actionId)) {
                        is network.orion.data.actions.ActionReadResult.Ready -> read.action
                        is network.orion.data.actions.ActionReadResult.Blocked -> throw OrionReminderTransactionBlocked.Action(
                            if (read.reason in setOf(network.orion.data.actions.ActionStoreOutcome.KEY_UNAVAILABLE,
                                network.orion.data.actions.ActionStoreOutcome.KEY_INVALIDATED)) OrionReminderActionFailure.KEY_UNAVAILABLE
                            else OrionReminderActionFailure.UNREADABLE)
                    }
                    val json = org.json.JSONObject(stored.request.canonicalNormalizedArguments)
                    require(json.getString("reminderId") == wire.reminderId.toString())
                    OrionVerifiedReminderIngress(OrionReminderIngressWire(wire.operation, wire.reminderId, wire.key, wire.trace,
                        json.getString("title"), wire.expectedVersion, wire.actionId, wire.actionVersion, wire.fingerprint,
                        wire.policyVersion, wire.receipt, json.getLong("scheduledAtEpochMillis"), json.getString("zoneId")))
                } else OrionVerifiedReminderIngress(wire)
            } finally { bytes.fill(0) }
        }
    }
}
