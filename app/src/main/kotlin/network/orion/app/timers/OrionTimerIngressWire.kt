package network.orion.app.timers

import java.io.*
import java.time.Instant
import java.util.UUID
import network.orion.core.actions.ActionConfirmationSnapshot
import network.orion.core.commands.timers.*
import network.orion.core.model.Sensitivity
import network.orion.data.database.*
import network.orion.data.repository.LocalIngressPayload

/** Closed encrypted wire. No authorization, policy decision or presence field is accepted. */
internal class OrionTimerIngressWire(
    val operation: String, val timerId: UUID, val key: UUID, val trace: UUID,
    val title: String = "", val expectedVersion: Long = 0,
    val actionId: UUID? = null, val actionVersion: Long = 0,
    val fingerprint: String = "", val policyVersion: String = "",
    val receipt: ActionConfirmationSnapshot? = null,
    val durationMillis: Long = 0,
) {
    fun create() = CreateTimer(timerId, title, durationMillis, key, trace)
    fun change(): TimerStatusCommand = CancelTimer(timerId, expectedVersion, key, trace)
    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
        out.writeInt(1); listOf(operation, timerId.toString(), key.toString(), title).forEach(out::writeUTF)
        out.writeLong(durationMillis); out.writeLong(expectedVersion); out.writeUTF(actionId?.toString() ?: ""); out.writeLong(actionVersion)
        out.writeUTF(fingerprint); out.writeUTF(policyVersion); out.writeBoolean(receipt != null)
        receipt?.let { r ->
            listOf(r.actionId.toString(), r.normalizedPayloadHash, r.confirmedPayloadHash, r.confirmationFingerprint,
                r.policyVersionAtConfirmation).forEach(out::writeUTF)
            out.writeLong(r.confirmedAt.toEpochMilli()); out.writeLong(checkNotNull(r.confirmationExpiresAt).toEpochMilli())
            out.writeLong(r.payloadLockedAt.toEpochMilli())
        }
    } }.toByteArray()
    override fun toString() = "OrionTimerIngressWire(<redacted>)"
    companion object {
        const val SCOPE = "orion_ui_timer_v1"
        const val SOURCE = "MAIN_UI_TIMER"
        const val TYPE = "TimerRequest"
        fun decode(bytes: ByteArray): OrionTimerIngressWire {
            require(bytes.size in 1..8192)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 1)
                val op = input.readUTF(); require(op in setOf("CREATE", "CANCEL", "DISMISS"))
                val id = UUID.fromString(input.readUTF()); val key = UUID.fromString(input.readUTF()); val trace = UUID(0, 0)
                val title = input.readUTF(); val duration = input.readLong(); val version = input.readLong()
                val action = input.readUTF().takeIf { it.isNotEmpty() }?.let(UUID::fromString); val av = input.readLong()
                val fingerprint = input.readUTF(); val policy = input.readUTF()
                val receipt = if (!input.readBoolean()) null else ActionConfirmationSnapshot(UUID.fromString(input.readUTF()),
                    input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), Instant.ofEpochMilli(input.readLong()),
                    Instant.ofEpochMilli(input.readLong()), Instant.ofEpochMilli(input.readLong()))
                require(input.available() == 0 && version >= 0 && av >= 0)
                OrionTimerIngressWire(op, id, key, trace, title, version, action, av, fingerprint, policy, receipt, duration).also {
                    require(it.encode().contentEquals(bytes))
                    if (op == "CREATE") {
                        if (action == null) {
                            val ready = OrionCreateTimerPreparer.prepare(it.create()) as? CreateTimerPreparation.Ready
                            require(ready?.timer?.title == title)
                        } else require(title.isEmpty() && duration == 0L)
                    } else if (op != "DISMISS") require(OrionTimerStatusPreparer.prepare(it.change()) is TimerStatusPreparation.Ready)
                    require(receipt == null || action != null && receipt.actionId == action)
                    if (op != "CREATE") require(title.isEmpty() && duration == 0L)
                    if (op == "DISMISS") require(action != null && receipt == null)
                    else if (action != null) require(receipt != null)
                    else require(receipt == null && av == 0L && fingerprint.isEmpty() && policy.isEmpty())
                }
            }
        }
    }
}

/** Only a decrypted/authenticated queue callback can create replay origin. Foreground alone cannot. */
internal class OrionVerifiedTimerIngress private constructor(val wire: OrionTimerIngressWire) {
    override fun toString() = "OrionVerifiedTimerIngress(<redacted>)"
    companion object {
        fun from(payload: LocalIngressPayload, database: OrionDatabase,
            actions: network.orion.data.actions.OrionActionStore): OrionVerifiedTimerIngress {
            check(database.inTransaction())
            val row = checkNotNull(database.ingress().find(payload.ingressId.toString()))
            require(row.status == DurableIngressStatus.PROCESSING && row.source == OrionTimerIngressWire.SOURCE &&
                payload.scope == OrionTimerIngressWire.SCOPE && payload.commandType == OrionTimerIngressWire.TYPE &&
                payload.schemaVersion == 1 && payload.sensitivity == Sensitivity.SENSITIVE && payload.referenceId == null)
            val bytes = checkNotNull(payload.copyContent())
            return try {
                val decoded = OrionTimerIngressWire.decode(bytes)
                // Trace metadata is deliberately outside the canonical idempotency material.
                val wire = OrionTimerIngressWire(decoded.operation, decoded.timerId, decoded.key, UUID.fromString(row.correlationId),
                    decoded.title, decoded.expectedVersion, decoded.actionId, decoded.actionVersion, decoded.fingerprint,
                    decoded.policyVersion, decoded.receipt, decoded.durationMillis)
                if (wire.operation == "CREATE" && wire.actionId != null) {
                    // A confirmation persists references and receipt metadata, never another title copy.
                    require(wire.title.isEmpty() && wire.durationMillis == 0L)
                    val stored = when (val read = actions.read(wire.actionId)) {
                        is network.orion.data.actions.ActionReadResult.Ready -> read.action
                        is network.orion.data.actions.ActionReadResult.Blocked -> throw OrionTimerTransactionBlocked.Action(
                            if (read.reason in setOf(network.orion.data.actions.ActionStoreOutcome.KEY_UNAVAILABLE,
                                network.orion.data.actions.ActionStoreOutcome.KEY_INVALIDATED)) OrionTimerActionFailure.KEY_UNAVAILABLE
                            else OrionTimerActionFailure.UNREADABLE)
                    }
                    val json = org.json.JSONObject(stored.request.canonicalNormalizedArguments)
                    require(json.getString("timerId") == wire.timerId.toString())
                    OrionVerifiedTimerIngress(OrionTimerIngressWire(wire.operation, wire.timerId, wire.key, wire.trace,
                        json.getString("title"), wire.expectedVersion, wire.actionId, wire.actionVersion, wire.fingerprint,
                        wire.policyVersion, wire.receipt, json.getLong("durationMillis")))
                } else OrionVerifiedTimerIngress(wire)
            } finally { bytes.fill(0) }
        }
    }
}
