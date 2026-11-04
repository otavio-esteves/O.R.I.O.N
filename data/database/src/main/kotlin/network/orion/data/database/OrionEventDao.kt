package network.orion.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

enum class EventConsumption { APPLIED, DEDUP }

@Dao
abstract class OrionEventDao {
    @Query("SELECT * FROM orion_domain_event_outbox WHERE eventId = :id")
    abstract fun find(id: String): DomainEventOutboxEntity?

    @Query("SELECT count(*) FROM orion_domain_event_outbox")
    abstract fun count(): Int

    @Query("SELECT * FROM orion_domain_event_outbox WHERE status = 'PENDING' AND nextAttemptAt <= :now ORDER BY nextAttemptAt, eventId LIMIT :limit")
    protected abstract fun queryDue(now: Long, limit: Int): List<DomainEventOutboxEntity>

    open fun due(now: Long, limit: Int): List<DomainEventOutboxEntity> {
        require(limit in 1..100)
        return queryDue(now, limit)
    }

    @Insert
    protected abstract fun insert(event: DomainEventOutboxEntity)

    /** Must be invoked in the same Room transaction as the mutation that produced the fact. */
    @Transaction
    open fun append(event: DomainEventOutboxEntity) {
        check(count() < 10_000) { "Durable event capacity reached." }
        requireTechnicalUuid(event.eventId)
        requireTechnicalUuid(event.aggregateId)
        requireTechnicalUuid(event.correlationId)
        require(event.eventType.matches(TECHNICAL_NAME) && event.schemaVersion > 0 && event.aggregateVersion >= 0)
        require(event.status == DomainEventDeliveryStatus.PENDING && event.attemptCount == 0 &&
            event.version == 0L && event.deliveredAt == null && event.nextAttemptAt == event.occurredAt)
        insert(event)
    }

    @Query("""UPDATE orion_domain_event_outbox SET attemptCount = attemptCount + 1,
        nextAttemptAt = :retryAt, version = version + 1
        WHERE eventId = :id AND version = :version AND status = 'PENDING'""")
    protected abstract fun reserve(id: String, version: Long, retryAt: Long): Int

    @Query("""UPDATE orion_domain_event_outbox SET status = :status, deliveredAt = :deliveredAt,
        version = version + 1 WHERE eventId = :id AND version = :version AND status = 'PENDING'""")
    protected abstract fun setStatus(id: String, version: Long, status: DomainEventDeliveryStatus, deliveredAt: Long?): Int

    /** Persist attempt/backoff BEFORE delivery. A crash can cause duplicate delivery, never infinite retry. */
    @Transaction
    open fun reserveAttempt(id: String, expectedVersion: Long, now: Long, retryAt: Long, maxAttempts: Int): DomainEventOutboxEntity? {
        require(expectedVersion >= 0 && expectedVersion < Long.MAX_VALUE && retryAt > now && maxAttempts in 1..100)
        val current = find(id) ?: return null
        if (current.version != expectedVersion || current.status != DomainEventDeliveryStatus.PENDING || current.nextAttemptAt > now) return null
        if (current.attemptCount >= maxAttempts) {
            setStatus(id, expectedVersion, DomainEventDeliveryStatus.DEAD_LETTER, null)
            return null
        }
        if (reserve(id, expectedVersion, retryAt) != 1) return null
        return find(id)
    }

    open fun delivered(id: String, expectedVersion: Long, at: Long): Boolean =
        setStatus(id, expectedVersion, DomainEventDeliveryStatus.DELIVERED, at) == 1

    open fun deadLetter(id: String, expectedVersion: Long): Boolean =
        setStatus(id, expectedVersion, DomainEventDeliveryStatus.DEAD_LETTER, null) == 1

    @Transaction
    open fun failed(id: String, expectedVersion: Long, maxAttempts: Int): Boolean {
        require(maxAttempts in 1..100)
        val current = find(id) ?: return false
        if (current.status != DomainEventDeliveryStatus.PENDING || current.version != expectedVersion) return false
        return if (current.attemptCount >= maxAttempts) {
            setStatus(id, expectedVersion, DomainEventDeliveryStatus.DEAD_LETTER, null) == 1
        } else true // retryAt was already committed by reserveAttempt.
    }

    @Query("SELECT count(*) FROM orion_event_consumer_receipts WHERE eventId = :eventId AND consumerId = :consumerId")
    abstract fun consumed(eventId: String, consumerId: String): Int

    @Query("SELECT count(*) FROM orion_event_consumer_receipts WHERE eventId = :eventId")
    protected abstract fun receiptCount(eventId: String): Int

    @Insert
    protected abstract fun insertReceipt(receipt: EventConsumerReceiptEntity)

    @Insert
    protected abstract fun insertTechnicalEvent(event: TechnicalEventEntity)

    /** Example durable local consumer: technical projection and dedup receipt share one transaction. */
    @Transaction
    open fun consumeTechnical(eventId: String, consumerId: String, projection: TechnicalEventEntity): EventConsumption {
        require(consumerId.matches(TECHNICAL_NAME))
        requireTechnicalEvent(projection)
        require(find(eventId) != null) { "Unknown durable event." }
        if (consumed(eventId, consumerId) != 0) return EventConsumption.DEDUP
        check(receiptCount(eventId) < 32) { "Durable consumer capacity reached." }
        insertTechnicalEvent(projection)
        insertReceipt(EventConsumerReceiptEntity(eventId, consumerId, projection.occurredAt))
        return EventConsumption.APPLIED
    }
}

internal fun requireTechnicalEvent(event: TechnicalEventEntity) {
    requireTechnicalUuid(event.technicalEventId)
    requireTechnicalUuid(event.correlationId)
    require(event.component.matches(TECHNICAL_NAME) && event.eventCode.matches(TECHNICAL_NAME))
}
