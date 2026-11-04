package network.orion.data.memory

import java.util.UUID
import network.orion.core.model.Sensitivity
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.database.*

class MemoryClaimInput(val id: UUID, statement: ByteArray, val sensitivity: Sensitivity,
    val origin: MemoryOrigin, val confidence: Double, val userConfirmed: Boolean = false) {
    private val content = statement.copyOf()
    fun copyStatement(): ByteArray = content.copyOf()
    override fun toString(): String = "MemoryClaimInput(<redacted>)"
}

sealed interface MemoryWriteResult {
    data class Stored(val id: UUID) : MemoryWriteResult
    data class Blocked(val reason: FieldCryptoFailure) : MemoryWriteResult
    data class Rejected(val mutation: ActionMutation) : MemoryWriteResult
}

/** Storage only: provenance is supplied by trusted Core; no inference promotion, FTS or ranking. */
class OrionMemoryStore(private val database: OrionDatabase, private val encryption: OrionFieldEncryption,
    private val clock: WallClock) {
    fun create(id: UUID, content: ByteArray, sensitivity: Sensitivity, origin: MemoryOrigin, confidence: Double,
        claims: List<MemoryClaimInput>, correlationId: UUID, userConfirmed: Boolean = false): MemoryWriteResult =
        write(id, content, sensitivity, origin, confidence, claims, correlationId, userConfirmed, null, null)

    fun supersede(priorId: UUID, expectedVersion: Long, newId: UUID, content: ByteArray, sensitivity: Sensitivity,
        origin: MemoryOrigin, confidence: Double, claims: List<MemoryClaimInput>, correlationId: UUID,
        userConfirmed: Boolean = false): MemoryWriteResult {
        require(expectedVersion in 0 until Long.MAX_VALUE && priorId != newId)
        return write(newId, content, sensitivity, origin, confidence, claims, correlationId, userConfirmed, priorId, expectedVersion)
    }

    private fun write(id: UUID, content: ByteArray, sensitivity: Sensitivity, origin: MemoryOrigin, confidence: Double,
        claims: List<MemoryClaimInput>, correlationId: UUID, userConfirmed: Boolean, priorId: UUID?, version: Long?): MemoryWriteResult {
        require(claims.size <= 100)
        val body = encryption.encrypt(content, context(id))
        if (body is FieldEncryptionResult.Failure) return MemoryWriteResult.Blocked(body.reason)
        val at = clock.wallNow().toEpochMilli()
        val rows = ArrayList<MemoryClaimEntity>(claims.size)
        for (claim in claims) {
            val statement = claim.copyStatement()
            val encrypted = try { encryption.encrypt(statement, claimContext(id, claim.id)) } finally { statement.fill(0) }
            if (encrypted is FieldEncryptionResult.Failure) return MemoryWriteResult.Blocked(encrypted.reason)
            rows += MemoryClaimEntity(claim.id.toString(), id.toString(),
                FieldEnvelopeCodec.encode((encrypted as FieldEncryptionResult.Encrypted).envelope), claim.sensitivity,
                claim.origin, claim.confidence, claim.userConfirmed, validFrom = at)
        }
        val row = MemoryEntity(id.toString(), FieldEnvelopeCodec.encode((body as FieldEncryptionResult.Encrypted).envelope),
            sensitivity, origin, confidence, userConfirmed, at, at, supersedesId = priorId?.toString())
        val created = event(id, 0, "MEMORY_CREATED", sensitivity, at, correlationId)
        if (priorId == null) {
            database.records().createMemoryWithClaimsAndEvent(row, rows, created)
        } else {
            val prior = database.records().memory(priorId.toString()) ?: return MemoryWriteResult.Rejected(ActionMutation.NOT_FOUND)
            val result = database.records().supersedeWithEvents(priorId.toString(), checkNotNull(version), row, rows,
                event(priorId, version + 1, "MEMORY_SUPERSEDED", prior.sensitivity, at, correlationId), created)
            if (result != ActionMutation.APPLIED) return MemoryWriteResult.Rejected(result)
        }
        return MemoryWriteResult.Stored(id)
    }

    fun readContent(id: UUID): FieldDecryptionResult? = database.records().memory(id.toString())?.let {
        decrypt(it.contentEnvelope, context(id))
    }

    fun readClaim(memoryId: UUID, claimId: UUID): FieldDecryptionResult? =
        database.records().claims(memoryId.toString()).firstOrNull { it.claimId == claimId.toString() }?.let {
            decrypt(it.statementEnvelope, claimContext(memoryId, claimId))
        }

    fun delete(id: UUID, expectedVersion: Long, correlationId: UUID): ActionMutation {
        require(expectedVersion in 0 until Long.MAX_VALUE)
        val row = database.records().memory(id.toString()) ?: return ActionMutation.NOT_FOUND
        val at = clock.wallNow().toEpochMilli()
        return database.records().deleteMemoryWithEvent(id.toString(), expectedVersion, at,
            event(id, expectedVersion + 1, "MEMORY_DELETED", row.sensitivity, at, correlationId))
    }

    fun list(afterId: UUID? = null, limit: Int = 50): List<MemoryEntity> = database.records().memories(afterId?.toString() ?: "", limit)

    private fun decrypt(bytes: ByteArray, context: FieldEncryptionContext): FieldDecryptionResult {
        val decoded = FieldEnvelopeCodec.decode(bytes)
        return if (decoded is FieldEnvelopeDecodingResult.Decoded) encryption.decrypt(decoded.envelope, context)
            else FieldDecryptionResult.Failure(FieldCryptoFailure.UNSUPPORTED_ENVELOPE)
    }
    private fun context(id: UUID) = FieldEncryptionContext("memory/$id", "content", 1)
    private fun claimContext(memoryId: UUID, claimId: UUID) = FieldEncryptionContext("memory/$memoryId/claim/$claimId", "statement", 1)
    private fun event(id: UUID, version: Long, type: String, sensitivity: Sensitivity, at: Long, correlation: UUID) =
        DomainEventOutboxEntity(UUID.randomUUID().toString(), id.toString(), version, type, 1, sensitivity, at, correlation.toString())
}
