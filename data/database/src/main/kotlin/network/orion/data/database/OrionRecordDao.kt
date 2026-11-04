package network.orion.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import java.time.ZoneId
import network.orion.core.model.Sensitivity

/** F2 encrypted records only; semantic memory and scheduler implementations remain later phases. */
@Dao
abstract class OrionRecordDao {
    /** Every authoritative encrypted table, including retained terminal envelopes. */
    @Query("""SELECT (EXISTS(SELECT 1 FROM orion_action_requests) OR
        EXISTS(SELECT 1 FROM orion_tasks) OR EXISTS(SELECT 1 FROM orion_reminders) OR
        EXISTS(SELECT 1 FROM orion_memories) OR EXISTS(SELECT 1 FROM orion_memory_claims) OR
        EXISTS(SELECT 1 FROM orion_ingress_envelopes))""")
    abstract fun hasEncryptedRecords(): Boolean

    @Query("SELECT * FROM orion_reminders WHERE reminderId = :id")
    abstract fun reminder(id: String): ReminderEntity?

    @Query("SELECT * FROM orion_memories WHERE memoryId = :id")
    abstract fun memory(id: String): MemoryEntity?

    @Query("SELECT * FROM orion_memory_claims WHERE memoryId = :memoryId ORDER BY claimId")
    abstract fun claims(memoryId: String): List<MemoryClaimEntity>

    @Insert
    protected abstract fun insertReminder(reminder: ReminderEntity)

    @Insert
    protected abstract fun insertMemory(memory: MemoryEntity)

    @Insert
    protected abstract fun insertClaims(claims: List<MemoryClaimEntity>)

    @Insert
    protected abstract fun insertEvent(event: DomainEventOutboxEntity)

    @Query("SELECT count(*) FROM orion_domain_event_outbox")
    protected abstract fun eventCount(): Int

    @Insert
    protected abstract fun insertAudit(audit: AuditEntity)

    @Insert
    protected abstract fun insertTechnical(event: TechnicalEventEntity)

    @Transaction
    open fun createReminderWithEvent(reminder: ReminderEntity, event: DomainEventOutboxEntity) {
        requireTechnicalUuid(reminder.reminderId)
        requireEncryptedContent(reminder.contentEnvelope)
        ZoneId.of(reminder.zoneId)
        require(reminder.status == ReminderStatus.PENDING && reminder.precision == ReminderPrecision.UNSCHEDULED &&
            reminder.version == 0L && reminder.updatedAt == reminder.createdAt)
        requireCreationEvent(reminder.reminderId, reminder.createdAt, "REMINDER_CREATED", reminder.sensitivity, event)
        check(eventCount() < 10_000) { "Durable event capacity reached." }
        insertReminder(reminder)
        insertEvent(event)
    }

    @Transaction
    open fun createMemoryWithClaimsAndEvent(memory: MemoryEntity, claims: List<MemoryClaimEntity>, event: DomainEventOutboxEntity) {
        requireTechnicalUuid(memory.memoryId)
        requireEncryptedContent(memory.contentEnvelope)
        require(memory.version == 0L && memory.status == MemoryStatus.ACTIVE && memory.createdAt == memory.updatedAt)
        require(memory.supersedesId == null) { "Supersession requires a separate guarded transaction." }
        requireConfidence(memory.origin, memory.confidence, memory.userConfirmed)
        require(claims.size <= 100 && claims.map { it.claimId }.distinct().size == claims.size)
        for (claim in claims) {
            requireTechnicalUuid(claim.claimId)
            require(claim.memoryId == memory.memoryId && claim.version == 0L && claim.status == MemoryStatus.ACTIVE)
            require(claim.validUntil == null || claim.validUntil > claim.validFrom)
            requireEncryptedContent(claim.statementEnvelope)
            requireConfidence(claim.origin, claim.confidence, claim.userConfirmed)
        }
        requireCreationEvent(memory.memoryId, memory.createdAt, "MEMORY_CREATED", memory.sensitivity, event)
        check(eventCount() < 10_000) { "Durable event capacity reached." }
        insertMemory(memory)
        insertClaims(claims)
        insertEvent(event)
    }

    open fun appendAudit(audit: AuditEntity) {
        requireTechnicalUuid(audit.auditId)
        requireTechnicalUuid(audit.aggregateId)
        requireTechnicalUuid(audit.correlationId)
        require(audit.aggregateVersion >= 0 && audit.eventCode.matches(TECHNICAL_NAME))
        insertAudit(audit)
    }

    open fun appendTechnical(event: TechnicalEventEntity) {
        requireTechnicalEvent(event)
        insertTechnical(event)
    }

    private fun requireConfidence(origin: MemoryOrigin, confidence: Double, confirmed: Boolean) {
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(origin != MemoryOrigin.LLM_INFERRED || !confirmed) { "Inferred memory cannot be created confirmed." }
    }

    private fun requireEncryptedContent(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_DURABLE_FIELD_BYTES)
    }

    private fun requireCreationEvent(id: String, at: Long, type: String, sensitivity: Sensitivity, event: DomainEventOutboxEntity) {
        requireTechnicalUuid(event.eventId)
        requireTechnicalUuid(event.correlationId)
        require(event.aggregateId == id && event.aggregateVersion == 0L && event.occurredAt == at && event.eventType == type)
        require(event.payloadSensitivity == sensitivity)
        require(event.schemaVersion == 1 && event.status == DomainEventDeliveryStatus.PENDING && event.attemptCount == 0 &&
            event.version == 0L && event.deliveredAt == null && event.nextAttemptAt == at)
    }
}
