package network.orion.app.memories

import java.io.*
import java.time.Instant
import java.util.UUID
import network.orion.core.actions.ActionConfirmationSnapshot
import network.orion.core.commands.memories.*
import network.orion.core.model.Sensitivity
import network.orion.data.database.*
import network.orion.data.repository.LocalIngressPayload

/** Closed encrypted wire. No authorization, policy decision or presence field is accepted. */
internal class OrionMemoryIngressWire(
    val operation: String, val memoryId: UUID, val key: UUID, val trace: UUID,
    val content: String = "", val expectedVersion: Long = 0,
    val actionId: UUID? = null, val actionVersion: Long = 0,
    val fingerprint: String = "", val policyVersion: String = "",
    val receipt: ActionConfirmationSnapshot? = null,
) {
    fun create() = CreateMemory(memoryId, content, key, trace)
    fun change(): MemoryStatusCommand = ForgetMemory(memoryId, expectedVersion, key, trace)
    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
        out.writeInt(1); listOf(operation, memoryId.toString(), key.toString(), content).forEach(out::writeUTF)
        out.writeLong(expectedVersion); out.writeUTF(actionId?.toString() ?: ""); out.writeLong(actionVersion)
        out.writeUTF(fingerprint); out.writeUTF(policyVersion); out.writeBoolean(receipt != null)
        receipt?.let { r ->
            listOf(r.actionId.toString(), r.normalizedPayloadHash, r.confirmedPayloadHash, r.confirmationFingerprint,
                r.policyVersionAtConfirmation).forEach(out::writeUTF)
            out.writeLong(r.confirmedAt.toEpochMilli()); out.writeLong(checkNotNull(r.confirmationExpiresAt).toEpochMilli())
            out.writeLong(r.payloadLockedAt.toEpochMilli())
        }
    } }.toByteArray()
    override fun toString() = "OrionMemoryIngressWire(<redacted>)"
    companion object {
        const val SCOPE = "orion_ui_memory_v1"
        const val SOURCE = "MAIN_UI_MEMORY"
        const val TYPE = "MemoryRequest"
        fun decode(bytes: ByteArray): OrionMemoryIngressWire {
            require(bytes.size in 1..8192)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 1)
                val op = input.readUTF(); require(op in setOf("CREATE", "DELETE", "DISMISS"))
                val id = UUID.fromString(input.readUTF()); val key = UUID.fromString(input.readUTF()); val trace = UUID(0, 0)
                val content = input.readUTF(); val version = input.readLong()
                val action = input.readUTF().takeIf { it.isNotEmpty() }?.let(UUID::fromString); val av = input.readLong()
                val fingerprint = input.readUTF(); val policy = input.readUTF()
                val receipt = if (!input.readBoolean()) null else ActionConfirmationSnapshot(UUID.fromString(input.readUTF()),
                    input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), Instant.ofEpochMilli(input.readLong()),
                    Instant.ofEpochMilli(input.readLong()), Instant.ofEpochMilli(input.readLong()))
                require(input.available() == 0 && version >= 0 && av >= 0)
                OrionMemoryIngressWire(op, id, key, trace, content, version, action, av, fingerprint, policy, receipt).also {
                    require(it.encode().contentEquals(bytes))
                    if (op == "CREATE") {
                        if (action == null) {
                            val ready = OrionCreateMemoryPreparer.prepare(it.create()) as? CreateMemoryPreparation.Ready
                            require(ready?.memory?.content == content)
                        } else require(content.isEmpty())
                    } else if (op != "DISMISS") require(OrionMemoryStatusPreparer.prepare(it.change()) is MemoryStatusPreparation.Ready)
                    require(receipt == null || action != null && receipt.actionId == action)
                    if (op != "CREATE") require(content.isEmpty())
                    if (op == "DISMISS") require(action != null && receipt == null)
                    else if (action != null) require(receipt != null)
                    else require(receipt == null && av == 0L && fingerprint.isEmpty() && policy.isEmpty())
                }
            }
        }
    }
}

/** Only a decrypted/authenticated queue callback can create replay origin. Foreground alone cannot. */
internal class OrionVerifiedMemoryIngress private constructor(val wire: OrionMemoryIngressWire) {
    override fun toString() = "OrionVerifiedMemoryIngress(<redacted>)"
    companion object {
        fun from(payload: LocalIngressPayload, database: OrionDatabase,
            actions: network.orion.data.actions.OrionActionStore): OrionVerifiedMemoryIngress {
            check(database.inTransaction())
            val row = checkNotNull(database.ingress().find(payload.ingressId.toString()))
            require(row.status == DurableIngressStatus.PROCESSING && row.source == OrionMemoryIngressWire.SOURCE &&
                payload.scope == OrionMemoryIngressWire.SCOPE && payload.commandType == OrionMemoryIngressWire.TYPE &&
                payload.schemaVersion == 1 && payload.sensitivity == Sensitivity.SENSITIVE && payload.referenceId == null)
            val bytes = checkNotNull(payload.copyContent())
            return try {
                val decoded = OrionMemoryIngressWire.decode(bytes)
                // Trace metadata is deliberately outside the canonical idempotency material.
                val wire = OrionMemoryIngressWire(decoded.operation, decoded.memoryId, decoded.key, UUID.fromString(row.correlationId),
                    decoded.content, decoded.expectedVersion, decoded.actionId, decoded.actionVersion, decoded.fingerprint,
                    decoded.policyVersion, decoded.receipt)
                if (wire.operation == "CREATE" && wire.actionId != null) {
                    // A confirmation persists references and receipt metadata, never another content copy.
                    require(wire.content.isEmpty())
                    val stored = when (val read = actions.read(wire.actionId)) {
                        is network.orion.data.actions.ActionReadResult.Ready -> read.action
                        is network.orion.data.actions.ActionReadResult.Blocked -> throw OrionMemoryTransactionBlocked.Action(
                            if (read.reason in setOf(network.orion.data.actions.ActionStoreOutcome.KEY_UNAVAILABLE,
                                network.orion.data.actions.ActionStoreOutcome.KEY_INVALIDATED)) OrionMemoryActionFailure.KEY_UNAVAILABLE
                            else OrionMemoryActionFailure.UNREADABLE)
                    }
                    val json = org.json.JSONObject(stored.request.canonicalNormalizedArguments)
                    require(json.getString("memoryId") == wire.memoryId.toString())
                    OrionVerifiedMemoryIngress(OrionMemoryIngressWire(wire.operation, wire.memoryId, wire.key, wire.trace,
                        json.getString("content"), wire.expectedVersion, wire.actionId, wire.actionVersion, wire.fingerprint,
                        wire.policyVersion, wire.receipt))
                } else OrionVerifiedMemoryIngress(wire)
            } finally { bytes.fill(0) }
        }
    }
}
