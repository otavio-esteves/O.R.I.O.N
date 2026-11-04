package network.orion.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import network.orion.core.model.Sensitivity

enum class DurableIngressStatus { QUEUED, PROCESSING, CONSUMED, REJECTED, EXPIRED, FAILED }
enum class IngressRegistration { INSERTED, DEDUP, IDEMPOTENCY_CONFLICT, INGRESS_ID_CONFLICT, CAPACITY_EXCEEDED }

/** Opaque encrypted content only. ID/key/type fields are technical identifiers, never user content. */
@Entity(tableName = "orion_ingress_envelopes", indices = [
    Index(value = ["idempotencyScope", "idempotencyKey"], unique = true),
    Index(value = ["status", "receivedAt"]),
])
data class IngressEnvelopeEntity(
    @PrimaryKey val ingressId: String,
    val idempotencyScope: String,
    val idempotencyKey: String,
    val canonicalPayloadHash: String,
    val schemaVersion: Int,
    val commandType: String,
    val source: String,
    val priority: Int,
    val correlationId: String,
    val payloadSensitivity: Sensitivity,
    val payloadEnvelope: ByteArray,
    val receivedAt: Long,
    val expiresAt: Long,
    val idempotencyRetainUntil: Long,
    val status: DurableIngressStatus = DurableIngressStatus.QUEUED,
    val version: Long = 0,
    val updatedAt: Long = receivedAt,
    val attemptCount: Int = 0,
    val lastErrorCode: String? = null,
) {
    override fun toString(): String = "IngressEnvelopeEntity(<redacted>)"
}
