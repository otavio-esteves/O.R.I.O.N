package network.orion.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import network.orion.core.model.Sensitivity

enum class DomainEventDeliveryStatus { PENDING, DELIVERED, DEAD_LETTER }

/** Reference-only envelope: durable user content is read through its owner, never copied into the outbox. */
@Entity(tableName = "orion_domain_event_outbox", indices = [Index(value = ["status", "nextAttemptAt"])])
data class DomainEventOutboxEntity(
    @PrimaryKey val eventId: String,
    val aggregateId: String,
    val aggregateVersion: Long,
    val eventType: String,
    val schemaVersion: Int,
    val payloadSensitivity: Sensitivity,
    val occurredAt: Long,
    val correlationId: String,
    val status: DomainEventDeliveryStatus = DomainEventDeliveryStatus.PENDING,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = occurredAt,
    val deliveredAt: Long? = null,
    val version: Long = 0,
)

@Entity(tableName = "orion_event_consumer_receipts", primaryKeys = ["eventId", "consumerId"], foreignKeys = [ForeignKey(
    entity = DomainEventOutboxEntity::class, parentColumns = ["eventId"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE,
)])
data class EventConsumerReceiptEntity(val eventId: String, val consumerId: String, val consumedAt: Long)
