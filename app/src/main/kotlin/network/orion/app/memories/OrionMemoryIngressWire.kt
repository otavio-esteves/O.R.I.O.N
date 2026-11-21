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
    val revisionArguments: String = "",
    val allowSearchIndex: Boolean = false,
) {
    fun create() = CreateMemory(memoryId, content, key, trace, allowSearchIndex)
    fun change(): MemoryStatusCommand {
        val kind = MemoryStatusOperation.valueOf(operation)
        if (kind == MemoryStatusOperation.DELETE) return ForgetMemory(memoryId, expectedVersion, key, trace)
        val revision = decodeMemoryRevision(kind, revisionArguments, key, trace)
        require(revision.memoryId == memoryId && revision.expectedVersion == expectedVersion)
        return revision.command
    }
    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
        val revised = operation in revisionOperations
        out.writeInt(if (revised) 2 else if (allowSearchIndex) 3 else 1); listOf(operation, memoryId.toString(), key.toString(), content).forEach(out::writeUTF)
        out.writeLong(expectedVersion); out.writeUTF(actionId?.toString() ?: ""); out.writeLong(actionVersion)
        out.writeUTF(fingerprint); out.writeUTF(policyVersion); out.writeBoolean(receipt != null)
        receipt?.let { r ->
            listOf(r.actionId.toString(), r.normalizedPayloadHash, r.confirmedPayloadHash, r.confirmationFingerprint,
                r.policyVersionAtConfirmation).forEach(out::writeUTF)
            out.writeLong(r.confirmedAt.toEpochMilli()); out.writeLong(checkNotNull(r.confirmationExpiresAt).toEpochMilli())
            out.writeLong(r.payloadLockedAt.toEpochMilli())
        }
        if (revised) out.writeUTF(revisionArguments)
    } }.toByteArray()
    override fun toString() = "OrionMemoryIngressWire(<redacted>)"
    companion object {
        const val SCOPE = "orion_ui_memory_v1"
        const val SOURCE = "MAIN_UI_MEMORY"
        const val TYPE = "MemoryRequest"
        val revisionOperations = MemoryStatusOperation.entries.filter { it != MemoryStatusOperation.DELETE }
            .mapTo(mutableSetOf()) { it.name }

        fun decode(bytes: ByteArray): OrionMemoryIngressWire {
            require(bytes.size in 1..8192)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val schema = input.readInt()
                val op = input.readUTF()
                require(schema == 1 && op in setOf("CREATE", "DELETE", "DISMISS") || schema == 2 && op in revisionOperations || schema == 3 && op == "CREATE")
                val id = UUID.fromString(input.readUTF()); val key = UUID.fromString(input.readUTF()); val trace = UUID(0, 0)
                val content = input.readUTF(); val version = input.readLong()
                val action = input.readUTF().takeIf { it.isNotEmpty() }?.let(UUID::fromString); val av = input.readLong()
                val fingerprint = input.readUTF(); val policy = input.readUTF()
                val receipt = if (!input.readBoolean()) null else ActionConfirmationSnapshot(UUID.fromString(input.readUTF()),
                    input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), Instant.ofEpochMilli(input.readLong()),
                    Instant.ofEpochMilli(input.readLong()), Instant.ofEpochMilli(input.readLong()))
                val revision = if (schema == 2) input.readUTF() else ""
                require(input.available() == 0 && version >= 0 && av >= 0)
                OrionMemoryIngressWire(op, id, key, trace, content, version, action, av, fingerprint, policy, receipt, revision, schema == 3).also {
                    require(it.encode().contentEquals(bytes))
                    if (op == "CREATE") {
                        if (action == null) {
                            val ready = OrionCreateMemoryPreparer.prepare(it.create()) as? CreateMemoryPreparation.Ready
                            require(ready?.memory?.content == content)
                        } else require(content.isEmpty() && schema == 1)
                    } else if (op != "DISMISS") {
                        if (schema == 1 || action == null) require(OrionMemoryStatusPreparer.prepare(it.change()) is MemoryStatusPreparation.Ready)
                        else require(revision.isEmpty() && version < Long.MAX_VALUE)
                    }
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
                    decoded.policyVersion, decoded.receipt, decoded.revisionArguments, decoded.allowSearchIndex)
                if (wire.actionId != null && (wire.operation == "CREATE" || wire.operation in OrionMemoryIngressWire.revisionOperations)) {
                    // Confirmation carries references and the original receipt. Read canonical arguments from the action.
                    require(wire.content.isEmpty() && wire.revisionArguments.isEmpty())
                    val stored = when (val read = actions.read(wire.actionId)) {
                        is network.orion.data.actions.ActionReadResult.Ready -> read.action
                        is network.orion.data.actions.ActionReadResult.Blocked -> throw OrionMemoryTransactionBlocked.Action(
                            if (read.reason in setOf(network.orion.data.actions.ActionStoreOutcome.KEY_UNAVAILABLE,
                                network.orion.data.actions.ActionStoreOutcome.KEY_INVALIDATED)) OrionMemoryActionFailure.KEY_UNAVAILABLE
                            else OrionMemoryActionFailure.UNREADABLE)
                    }
                    val json = org.json.JSONObject(stored.request.canonicalNormalizedArguments)
                    require(json.getString("memoryId") == wire.memoryId.toString())
                    val create = wire.operation == "CREATE"
                    val restored = OrionMemoryIngressWire(wire.operation, wire.memoryId, wire.key, wire.trace,
                        if (create) json.getString("content") else "", wire.expectedVersion, wire.actionId, wire.actionVersion,
                        wire.fingerprint, wire.policyVersion, wire.receipt, if (create) "" else stored.request.canonicalNormalizedArguments,
                        create && json.optBoolean("allowSearchIndex"))
                    if (!create) {
                        require(stored.request.skill == MemoryStatusOperation.valueOf(wire.operation).skillName)
                        restored.change()
                    }
                    OrionVerifiedMemoryIngress(restored)
                } else OrionVerifiedMemoryIngress(wire)
            } finally { bytes.fill(0) }
        }
    }
}
