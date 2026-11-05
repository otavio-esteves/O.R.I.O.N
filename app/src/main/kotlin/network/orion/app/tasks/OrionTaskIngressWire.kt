package network.orion.app.tasks

import java.io.*
import java.time.Instant
import java.util.UUID
import network.orion.core.actions.ActionConfirmationSnapshot
import network.orion.core.commands.tasks.*
import network.orion.core.model.Sensitivity
import network.orion.data.database.*
import network.orion.data.repository.LocalIngressPayload

/** Closed encrypted wire. No authorization, policy decision or presence field is accepted. */
internal class OrionTaskIngressWire(
    val operation: String, val taskId: UUID, val key: UUID, val trace: UUID,
    val title: String = "", val expectedVersion: Long = 0,
    val actionId: UUID? = null, val actionVersion: Long = 0,
    val fingerprint: String = "", val policyVersion: String = "",
    val receipt: ActionConfirmationSnapshot? = null,
) {
    fun create() = CreateTask(taskId, title, key, trace)
    fun change(): TaskStatusCommand = if (operation == "COMPLETE") CompleteTask(taskId, expectedVersion, key, trace)
        else CancelTask(taskId, expectedVersion, key, trace)
    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
        out.writeInt(1); listOf(operation, taskId.toString(), key.toString(), title).forEach(out::writeUTF)
        out.writeLong(expectedVersion); out.writeUTF(actionId?.toString() ?: ""); out.writeLong(actionVersion)
        out.writeUTF(fingerprint); out.writeUTF(policyVersion); out.writeBoolean(receipt != null)
        receipt?.let { r ->
            listOf(r.actionId.toString(), r.normalizedPayloadHash, r.confirmedPayloadHash, r.confirmationFingerprint,
                r.policyVersionAtConfirmation).forEach(out::writeUTF)
            out.writeLong(r.confirmedAt.toEpochMilli()); out.writeLong(checkNotNull(r.confirmationExpiresAt).toEpochMilli())
            out.writeLong(r.payloadLockedAt.toEpochMilli())
        }
    } }.toByteArray()
    override fun toString() = "OrionTaskIngressWire(<redacted>)"
    companion object {
        const val SCOPE = "orion_ui_task_v1"
        const val SOURCE = "MAIN_UI_TASK"
        const val TYPE = "TaskRequest"
        fun decode(bytes: ByteArray): OrionTaskIngressWire {
            require(bytes.size in 1..8192)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 1)
                val op = input.readUTF(); require(op in setOf("CREATE", "COMPLETE", "CANCEL", "DISMISS"))
                val id = UUID.fromString(input.readUTF()); val key = UUID.fromString(input.readUTF()); val trace = UUID(0, 0)
                val title = input.readUTF(); val version = input.readLong()
                val action = input.readUTF().takeIf { it.isNotEmpty() }?.let(UUID::fromString); val av = input.readLong()
                val fingerprint = input.readUTF(); val policy = input.readUTF()
                val receipt = if (!input.readBoolean()) null else ActionConfirmationSnapshot(UUID.fromString(input.readUTF()),
                    input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), Instant.ofEpochMilli(input.readLong()),
                    Instant.ofEpochMilli(input.readLong()), Instant.ofEpochMilli(input.readLong()))
                require(input.available() == 0 && version >= 0 && av >= 0)
                OrionTaskIngressWire(op, id, key, trace, title, version, action, av, fingerprint, policy, receipt).also {
                    require(it.encode().contentEquals(bytes))
                    if (op == "CREATE") {
                        if (action == null) {
                            val ready = OrionCreateTaskPreparer.prepare(it.create()) as? CreateTaskPreparation.Ready
                            require(ready?.task?.title == title)
                        } else require(title.isEmpty())
                    } else if (op != "DISMISS") require(OrionTaskStatusPreparer.prepare(it.change()) is TaskStatusPreparation.Ready)
                    require(receipt == null || action != null && receipt.actionId == action)
                    if (op != "CREATE") require(title.isEmpty())
                    if (op == "DISMISS") require(action != null && receipt == null)
                    else if (action != null) require(receipt != null)
                    else require(receipt == null && av == 0L && fingerprint.isEmpty() && policy.isEmpty())
                }
            }
        }
    }
}

/** Only a decrypted/authenticated queue callback can create replay origin. Foreground alone cannot. */
internal class OrionVerifiedTaskIngress private constructor(val wire: OrionTaskIngressWire) {
    override fun toString() = "OrionVerifiedTaskIngress(<redacted>)"
    companion object {
        fun from(payload: LocalIngressPayload, database: OrionDatabase,
            actions: network.orion.data.actions.OrionActionStore): OrionVerifiedTaskIngress {
            check(database.inTransaction())
            val row = checkNotNull(database.ingress().find(payload.ingressId.toString()))
            require(row.status == DurableIngressStatus.PROCESSING && row.source == OrionTaskIngressWire.SOURCE &&
                payload.scope == OrionTaskIngressWire.SCOPE && payload.commandType == OrionTaskIngressWire.TYPE &&
                payload.schemaVersion == 1 && payload.sensitivity == Sensitivity.SENSITIVE && payload.referenceId == null)
            val bytes = checkNotNull(payload.copyContent())
            return try {
                val decoded = OrionTaskIngressWire.decode(bytes)
                // Trace metadata is deliberately outside the canonical idempotency material.
                val wire = OrionTaskIngressWire(decoded.operation, decoded.taskId, decoded.key, UUID.fromString(row.correlationId),
                    decoded.title, decoded.expectedVersion, decoded.actionId, decoded.actionVersion, decoded.fingerprint,
                    decoded.policyVersion, decoded.receipt)
                if (wire.operation == "CREATE" && wire.actionId != null) {
                    // A confirmation persists references and receipt metadata, never another title copy.
                    require(wire.title.isEmpty())
                    val stored = when (val read = actions.read(wire.actionId)) {
                        is network.orion.data.actions.ActionReadResult.Ready -> read.action
                        is network.orion.data.actions.ActionReadResult.Blocked -> throw OrionTaskTransactionBlocked.Action(
                            if (read.reason in setOf(network.orion.data.actions.ActionStoreOutcome.KEY_UNAVAILABLE,
                                network.orion.data.actions.ActionStoreOutcome.KEY_INVALIDATED)) OrionTaskActionFailure.KEY_UNAVAILABLE
                            else OrionTaskActionFailure.UNREADABLE)
                    }
                    val json = org.json.JSONObject(stored.request.canonicalNormalizedArguments)
                    require(json.getString("taskId") == wire.taskId.toString())
                    OrionVerifiedTaskIngress(OrionTaskIngressWire(wire.operation, wire.taskId, wire.key, wire.trace,
                        json.getString("title"), wire.expectedVersion, wire.actionId, wire.actionVersion, wire.fingerprint,
                        wire.policyVersion, wire.receipt))
                } else OrionVerifiedTaskIngress(wire)
            } finally { bytes.fill(0) }
        }
    }
}
