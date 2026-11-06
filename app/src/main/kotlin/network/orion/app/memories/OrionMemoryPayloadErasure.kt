package network.orion.app.memories

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.memories.*
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.actions.*
import network.orion.data.database.*
import org.json.JSONObject

/**
 * Privacy retention maintenance, called only inside the admitted forget writer.
 * Erase terminal ciphertext copies while retaining technical key/hash/audit tombstones.
 * Executable/confirmed material is never changed: matching drafts are cancelled first.
 * This is logical deletion, not a promise of forensic erasure of SQLite/flash blocks.
 */
internal class OrionMemoryPayloadErasure(private val db: OrionDatabase,
    private val encryption: OrionFieldEncryption, private val clock: WallClock) {
    private val actions = OrionActionStore(db, encryption, clock)
    fun erase(id: UUID) {
        check(db.inTransaction())
        val dao = db.memories()
        val actionRows = dao.creationActions()
        val ingressRows = dao.memoryIngress()
        if (actionRows.size > 10_000 || ingressRows.size > 10_000) unavailable()
        for (row in actionRows) {
            if (row.argumentsEnvelope.isEmpty() && row.targetEnvelope.isEmpty()) {
                if (row.status !in terminalActions) unavailable()
                continue
            }
            val stored = (actions.read(UUID.fromString(row.actionId)) as? ActionReadResult.Ready)?.action ?: unavailable()
            val target = try {
                val json = JSONObject(stored.request.canonicalNormalizedArguments)
                val memoryId = UUID.fromString(json.getString("memoryId"))
                val memory = (OrionCreateMemoryPreparer.prepare(CreateMemory(memoryId, json.getString("content"),
                    UUID.fromString(row.idempotencyKey), UUID.fromString(row.correlationId))) as? CreateMemoryPreparation.Ready)?.memory ?: unavailable()
                if (stored.request != OrionCreateMemoryActionStore.normalized(stored.request.actionId, memory, stored.request.policyVersion)) unavailable()
                memoryId
            } catch (_: org.json.JSONException) { unavailable() }
              catch (_: IllegalArgumentException) { unavailable() }
            if (target != id) continue
            if (row.status in setOf(ActionStatus.PENDING, ActionStatus.NEEDS_CONFIRMATION, ActionStatus.CONFIRMED)) {
                if (actions.cancel(UUID.fromString(row.actionId), row.version).outcome != ActionStoreOutcome.APPLIED) unavailable()
            } else if (row.status !in terminalActions) unavailable()
            val current = db.actions().find(row.actionId) ?: unavailable()
            if (dao.eraseTerminalCreate(current.actionId, current.version) != 1) unavailable()
        }
        for (row in ingressRows) {
            if (row.payloadEnvelope.isEmpty()) {
                if (row.status !in terminalIngress) unavailable()
                continue
            }
            // Scope alone routes work; authenticate the full envelope/hash before reading target.
            if (row.source != OrionMemoryIngressWire.SOURCE || row.commandType != OrionMemoryIngressWire.TYPE || row.schemaVersion != 1)
                unavailable()
            val decoded = FieldEnvelopeCodec.decode(row.payloadEnvelope) as? FieldEnvelopeDecodingResult.Decoded ?: unavailable()
            val opened = encryption.decrypt(decoded.envelope, FieldEncryptionContext(
                "ingress/${row.ingressId}/${row.canonicalPayloadHash}",
                "payload/${row.receivedAt}/${row.expiresAt}/${row.idempotencyRetainUntil}", row.schemaVersion))
                as? FieldDecryptionResult.Plaintext ?: unavailable()
            val clear = opened.copyBytes()
            val wire = try {
                if (clear.size < 2 || clear[0].toInt() != 2 || hash(row, clear) != row.canonicalPayloadHash) unavailable()
                val body = clear.copyOfRange(1, clear.size)
                try { OrionMemoryIngressWire.decode(body) } finally { body.fill(0) }
            } catch (_: IllegalArgumentException) { unavailable() }
              catch (_: java.io.IOException) { unavailable() }
              finally { clear.fill(0) }
            if (wire.operation != "CREATE" || wire.memoryId != id) continue
            if (row.status in setOf(DurableIngressStatus.QUEUED, DurableIngressStatus.PROCESSING)) {
                if (db.ingress().transition(row.ingressId, row.version, DurableIngressStatus.REJECTED,
                        clock.wallNow().toEpochMilli()) != ActionMutation.APPLIED) unavailable()
            } else if (row.status !in terminalIngress) unavailable()
            val current = db.ingress().find(row.ingressId) ?: unavailable()
            if (dao.eraseTerminalIngress(current.ingressId, current.version) != 1) unavailable()
        }
    }
    private fun hash(row: IngressEnvelopeEntity, wire: ByteArray): String {
        val bytes = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                fun field(value: String) { val encoded=value.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded) }
                field("orion_ingress_v1"); field(row.idempotencyScope); field(row.idempotencyKey); field(row.source); field(row.commandType)
                out.writeInt(row.schemaVersion); field(row.payloadSensitivity.name); out.writeInt(row.priority)
                out.writeLong(Math.subtractExact(row.expiresAt,row.receivedAt))
                out.writeLong(Math.subtractExact(row.idempotencyRetainUntil,row.receivedAt))
                out.writeInt(wire.size); out.write(wire)
            }
        }.toByteArray()
        return try { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
            finally { bytes.fill(0) }
    }
    private fun unavailable(): Nothing = throw OrionMemoryTransactionBlocked.Execution(OrionMemoryExecutionFailure.STORAGE_UNAVAILABLE)
    private companion object {
        val terminalActions = setOf(ActionStatus.SUCCEEDED,ActionStatus.FAILED,ActionStatus.CANCELLED)
        val terminalIngress = setOf(DurableIngressStatus.CONSUMED,DurableIngressStatus.REJECTED,DurableIngressStatus.EXPIRED,DurableIngressStatus.FAILED)
    }
}
