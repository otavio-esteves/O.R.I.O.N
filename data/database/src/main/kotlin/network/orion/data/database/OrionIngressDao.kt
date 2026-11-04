package network.orion.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/** Persistence only: caller enforces Startup Barrier, validates command and encrypts before enqueue. */
@Dao
abstract class OrionIngressDao {
    @Query("SELECT * FROM orion_ingress_envelopes WHERE ingressId = :id")
    abstract fun find(id: String): IngressEnvelopeEntity?

    @Query("SELECT * FROM orion_ingress_envelopes WHERE idempotencyScope = :scope AND idempotencyKey = :key")
    abstract fun findByKey(scope: String, key: String): IngressEnvelopeEntity?

    @Query("SELECT count(*) FROM orion_ingress_envelopes")
    abstract fun count(): Int

    @Query("SELECT * FROM orion_ingress_envelopes WHERE status = 'QUEUED' ORDER BY priority DESC, receivedAt, ingressId LIMIT :limit")
    protected abstract fun queryQueued(limit: Int): List<IngressEnvelopeEntity>

    open fun queued(limit: Int): List<IngressEnvelopeEntity> {
        require(limit in 1..100)
        return queryQueued(limit)
    }

    @Insert
    protected abstract fun insert(envelope: IngressEnvelopeEntity)

    @Query("""UPDATE orion_ingress_envelopes SET status = :to, updatedAt = :at, version = version + 1
        , attemptCount = attemptCount + CASE WHEN :to = 'PROCESSING' THEN 1 ELSE 0 END
        WHERE ingressId = :id AND version = :version AND status = :from""")
    protected abstract fun update(id: String, version: Long, from: DurableIngressStatus, to: DurableIngressStatus, at: Long): Int

    @Transaction
    open fun enqueue(envelope: IngressEnvelopeEntity): IngressRegistration {
        requireTechnicalUuid(envelope.ingressId)
        requireTechnicalUuid(envelope.correlationId)
        require(envelope.idempotencyScope.matches(TECHNICAL_NAME) && envelope.commandType.matches(TECHNICAL_NAME))
        require(envelope.source.matches(TECHNICAL_NAME) && envelope.priority in 0..100)
        require(envelope.idempotencyKey.isNotBlank() && envelope.idempotencyKey.length <= 128)
        require(envelope.canonicalPayloadHash.matches(SHA256_HEX) && envelope.schemaVersion > 0)
        require(envelope.payloadEnvelope.isNotEmpty() && envelope.payloadEnvelope.size <= MAX_DURABLE_FIELD_BYTES)
        require(envelope.expiresAt > envelope.receivedAt && envelope.idempotencyRetainUntil >= envelope.expiresAt)
        require(envelope.status == DurableIngressStatus.QUEUED && envelope.version == 0L && envelope.updatedAt == envelope.receivedAt)
        require(envelope.attemptCount == 0 && envelope.lastErrorCode == null)
        val previous = findByKey(envelope.idempotencyScope, envelope.idempotencyKey)
        if (previous != null) {
            return if (previous.canonicalPayloadHash == envelope.canonicalPayloadHash &&
                previous.schemaVersion == envelope.schemaVersion && previous.commandType == envelope.commandType &&
                previous.payloadSensitivity == envelope.payloadSensitivity && previous.source == envelope.source &&
                previous.priority == envelope.priority) IngressRegistration.DEDUP
            else IngressRegistration.IDEMPOTENCY_CONFLICT
        }
        if (find(envelope.ingressId) != null) return IngressRegistration.INGRESS_ID_CONFLICT
        if (count() >= 10_000) return IngressRegistration.CAPACITY_EXCEEDED
        insert(envelope)
        return IngressRegistration.INSERTED
    }

    /** QUEUED -> PROCESSING does not authorize any effect; drain requires an open startup barrier. */
    @Transaction
    open fun transition(id: String, expectedVersion: Long, to: DurableIngressStatus, at: Long): ActionMutation {
        require(expectedVersion >= 0 && expectedVersion < Long.MAX_VALUE)
        val current = find(id) ?: return ActionMutation.NOT_FOUND
        if (current.version != expectedVersion) return ActionMutation.CONFLICT
        val legal = when (current.status) {
            DurableIngressStatus.QUEUED -> to == DurableIngressStatus.PROCESSING || to == DurableIngressStatus.REJECTED || to == DurableIngressStatus.EXPIRED
            DurableIngressStatus.PROCESSING -> to == DurableIngressStatus.CONSUMED || to == DurableIngressStatus.REJECTED || to == DurableIngressStatus.FAILED
            else -> false
        }
        require(legal) { "Illegal ingress transition." }
        require(to != DurableIngressStatus.PROCESSING || at < current.expiresAt) { "Ingress expired." }
        require(to != DurableIngressStatus.EXPIRED || at >= current.expiresAt) { "Ingress has not expired." }
        return if (update(id, expectedVersion, current.status, to, at) == 1) ActionMutation.APPLIED else ActionMutation.CONFLICT
    }
}
