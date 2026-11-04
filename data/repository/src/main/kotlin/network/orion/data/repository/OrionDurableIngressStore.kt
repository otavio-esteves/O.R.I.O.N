package network.orion.data.repository

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import network.orion.core.ingress.*
import network.orion.core.model.Sensitivity
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.database.*

enum class LocalIngressDisposition { CONSUMED, REJECTED }

/** Trusted Core consumer. Only local DB mutation/outbox writes, on this store's transaction, are permitted. */
fun interface LocalIngressConsumer {
    fun apply(payload: LocalIngressPayload): LocalIngressDisposition
}

class LocalIngressPayload internal constructor(
    val ingressId: UUID, val scope: String, val idempotencyKey: UUID,
    val commandType: String, val schemaVersion: Int, val sensitivity: Sensitivity,
    val referenceId: UUID?, private val content: ByteArray?,
) {
    fun copyContent(): ByteArray? = content?.copyOf()
    internal fun clear() { content?.fill(0) }
    override fun toString(): String = "LocalIngressPayload(<redacted>)"
}

/** Blocking main-owned adapter. Not wired to product commands until production recovery/authorization exists. */
class OrionDurableIngressStore(
    private val database: OrionDatabase,
    private val encryption: OrionFieldEncryption,
    private val clock: WallClock,
    private val barrier: StartupBarrier,
    private val consumer: LocalIngressConsumer,
) : DurableIngressQueue {
    override fun enqueue(request: DurableIngressRequest): DurableIngressOutcome {
        val at = clock.wallNow().toEpochMilli()
        val expires = Math.addExact(at, request.lifetimeMillis)
        val retain = Math.addExact(at, request.idempotencyRetentionMillis)
        val content = request.copyContent()
        val wire = try {
            val requirements = DurablePayloadPolicy.requirementsFor(request.sensitivity, request.referenceSufficient)
            check(!requirements.referenceOnly || request.referenceId != null && content == null)
            encode(request.referenceId, content)
        } finally { content?.fill(0) }
        try {
            val hash = hash(request.scope, request.idempotencyKey.toString(), request.source, request.commandType,
                request.schemaVersion, request.sensitivity, request.priority, request.lifetimeMillis,
                request.idempotencyRetentionMillis, wire)
            val encrypted = when (val result = encryption.encrypt(wire, context(request.ingressId.toString(), hash,
                request.schemaVersion, at, expires, retain))) {
                is FieldEncryptionResult.Encrypted -> FieldEnvelopeCodec.encode(result.envelope)
                is FieldEncryptionResult.Failure -> return failure(result.reason)
            }
            val row = IngressEnvelopeEntity(request.ingressId.toString(), request.scope, request.idempotencyKey.toString(),
                hash, request.schemaVersion, request.commandType, request.source, request.priority,
                request.correlationId.toString(), request.sensitivity, encrypted, at, expires, retain)
            return when (database.ingress().enqueue(row)) {
                IngressRegistration.INSERTED -> DurableIngressOutcome.QUEUED
                IngressRegistration.DEDUP -> DurableIngressOutcome.DEDUP
                IngressRegistration.IDEMPOTENCY_CONFLICT -> DurableIngressOutcome.IDEMPOTENCY_CONFLICT
                IngressRegistration.INGRESS_ID_CONFLICT -> DurableIngressOutcome.INGRESS_ID_CONFLICT
                IngressRegistration.CAPACITY_EXCEEDED -> DurableIngressOutcome.CAPACITY_EXCEEDED
            }
        } finally { wire.fill(0) }
    }

    override fun consumeNext(): Boolean = barrier.whileOpen { consumeTransaction() } ?: false

    private fun consumeTransaction(): Boolean {
        var progressed = false
        database.runInTransaction {
            val dao = database.ingress()
            val row = dao.queued(1).firstOrNull() ?: return@runInTransaction
            val now = clock.wallNow().toEpochMilli()
            if (now < row.receivedAt) return@runInTransaction
            if (now >= row.expiresAt) {
                check(dao.transition(row.ingressId, row.version, DurableIngressStatus.EXPIRED, now) == ActionMutation.APPLIED)
                progressed = true
                return@runInTransaction
            }
            val envelope = when (val parsed = FieldEnvelopeCodec.decode(row.payloadEnvelope)) {
                is FieldEnvelopeDecodingResult.Decoded -> parsed.envelope
                is FieldEnvelopeDecodingResult.Failure -> {
                    reject(row, now); progressed = true; return@runInTransaction
                }
            }
            val wire = when (val decrypted = encryption.decrypt(envelope, context(row.ingressId, row.canonicalPayloadHash,
                row.schemaVersion, row.receivedAt, row.expiresAt, row.idempotencyRetainUntil))) {
                is FieldDecryptionResult.Plaintext -> decrypted.copyBytes()
                is FieldDecryptionResult.Failure -> {
                    // Loss/unavailability of keys is not a reason to discard pending requests.
                    if (decrypted.reason == FieldCryptoFailure.KEY_UNAVAILABLE || decrypted.reason == FieldCryptoFailure.KEY_INVALIDATED)
                        return@runInTransaction
                    reject(row, now); progressed = true; return@runInTransaction
                }
            }
            val payload = try {
                val expected = hash(row.idempotencyScope, row.idempotencyKey, row.source, row.commandType,
                    row.schemaVersion, row.payloadSensitivity, row.priority,
                    Math.subtractExact(row.expiresAt, row.receivedAt),
                    Math.subtractExact(row.idempotencyRetainUntil, row.receivedAt), wire)
                require(expected == row.canonicalPayloadHash)
                decode(row, wire)
            } catch (_: IllegalArgumentException) {
                reject(row, now); progressed = true; return@runInTransaction
            } finally { wire.fill(0) }
            try {
                check(dao.transition(row.ingressId, row.version, DurableIngressStatus.PROCESSING, now) == ActionMutation.APPLIED)
                val disposition = consumer.apply(payload)
                val terminal = if (disposition == LocalIngressDisposition.CONSUMED) DurableIngressStatus.CONSUMED else DurableIngressStatus.REJECTED
                check(dao.transition(row.ingressId, row.version + 1, terminal, now) == ActionMutation.APPLIED)
                progressed = true
            } finally { payload.clear() }
        }
        return progressed
    }

    private fun reject(row: IngressEnvelopeEntity, at: Long) {
        check(database.ingress().transition(row.ingressId, row.version, DurableIngressStatus.REJECTED, at) == ActionMutation.APPLIED)
    }

    private fun decode(row: IngressEnvelopeEntity, wire: ByteArray): LocalIngressPayload {
        require(wire.isNotEmpty())
        val reference: UUID?
        val content: ByteArray?
        when (wire[0].toInt()) {
            1 -> {
                require(wire.size == 17)
                val buffer = ByteBuffer.wrap(wire, 1, 16)
                reference = UUID(buffer.long, buffer.long); content = null
            }
            2 -> {
                require(wire.size in 1..(DurableIngressRequest.MAX_CONTENT_BYTES + 1))
                reference = null; content = wire.copyOfRange(1, wire.size)
            }
            else -> throw IllegalArgumentException("Unsupported ingress payload.")
        }
        return LocalIngressPayload(UUID.fromString(row.ingressId), row.idempotencyScope, UUID.fromString(row.idempotencyKey),
            row.commandType, row.schemaVersion, row.payloadSensitivity, reference, content)
    }

    private fun encode(reference: UUID?, content: ByteArray?): ByteArray = if (reference != null) {
        ByteBuffer.allocate(17).put(1.toByte()).putLong(reference.mostSignificantBits).putLong(reference.leastSignificantBits).array()
    } else {
        val bytes = checkNotNull(content)
        byteArrayOf(2) + bytes
    }

    private fun context(id: String, hash: String, schema: Int, received: Long, expires: Long, retain: Long) =
        FieldEncryptionContext("ingress/$id/$hash", "payload/$received/$expires/$retain", schema)

    private fun hash(scope: String, key: String, source: String, type: String, schema: Int,
        sensitivity: Sensitivity, priority: Int, lifetime: Long, retention: Long, wire: ByteArray): String {
        val canonical = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                fun field(value: String) { val encoded = value.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded) }
                field("orion_ingress_v1"); field(scope); field(key); field(source); field(type)
                out.writeInt(schema); field(sensitivity.name); out.writeInt(priority); out.writeLong(lifetime); out.writeLong(retention)
                out.writeInt(wire.size); out.write(wire)
            }
        }.toByteArray()
        return try { MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it) } }
        finally { canonical.fill(0) }
    }

    private fun failure(reason: FieldCryptoFailure) = when (reason) {
        FieldCryptoFailure.KEY_UNAVAILABLE -> DurableIngressOutcome.KEY_UNAVAILABLE
        FieldCryptoFailure.KEY_INVALIDATED -> DurableIngressOutcome.KEY_INVALIDATED
        else -> DurableIngressOutcome.UNREADABLE
    }
}
