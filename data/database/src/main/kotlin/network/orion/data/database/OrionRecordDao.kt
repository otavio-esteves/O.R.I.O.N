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
        EXISTS(SELECT 1 FROM orion_tasks) OR EXISTS(SELECT 1 FROM orion_timers) OR EXISTS(SELECT 1 FROM orion_reminders) OR
        EXISTS(SELECT 1 FROM orion_memories) OR EXISTS(SELECT 1 FROM orion_memory_claims) OR
        EXISTS(SELECT 1 FROM orion_ingress_envelopes))""")
    abstract fun hasEncryptedRecords(): Boolean

    @Query("SELECT * FROM orion_reminders WHERE reminderId = :id")
    abstract fun reminder(id: String): ReminderEntity?

    @Query("SELECT * FROM orion_memories WHERE memoryId = :id")
    abstract fun memory(id: String): MemoryEntity?

    @Query("SELECT * FROM orion_memory_claims WHERE memoryId = :memoryId ORDER BY claimId")
    abstract fun claims(memoryId: String): List<MemoryClaimEntity>

    @Query("SELECT * FROM orion_reminders WHERE reminderId > :afterId ORDER BY reminderId LIMIT :limit")
    protected abstract fun reminderPage(afterId: String, limit: Int): List<ReminderEntity>

    @Query("SELECT * FROM orion_memories WHERE memoryId > :afterId ORDER BY memoryId LIMIT :limit")
    protected abstract fun memoryPage(afterId: String, limit: Int): List<MemoryEntity>

    open fun reminders(afterId: String = "", limit: Int = 50): List<ReminderEntity> {
        require(limit in 1..100)
        return reminderPage(afterId, limit)
    }

    open fun memories(afterId: String = "", limit: Int = 50): List<MemoryEntity> {
        require(limit in 1..100)
        return memoryPage(afterId, limit)
    }

    @Query("""UPDATE orion_reminders SET contentEnvelope = :content, sensitivity = :sensitivity,
        scheduledAt = :scheduledAt, zoneId = :zoneId, precision = 'UNSCHEDULED', updatedAt = :at, version = version + 1
        WHERE reminderId = :id AND version = :version AND status = 'PENDING'""")
    protected abstract fun editReminder(id: String, version: Long, content: ByteArray, sensitivity: Sensitivity,
        scheduledAt: Long, zoneId: String, at: Long): Int

    @Query("""UPDATE orion_reminders SET status = 'CANCELLED', updatedAt = :at, version = version + 1
        WHERE reminderId = :id AND version = :version AND status = 'PENDING'""")
    protected abstract fun cancelReminder(id: String, version: Long, at: Long): Int

    @Query("""UPDATE orion_memories SET status = 'SUPERSEDED', updatedAt = :at, version = version + 1
        WHERE memoryId = :id AND version = :version AND status = 'ACTIVE'""")
    protected abstract fun supersedeMemory(id: String, version: Long, at: Long): Int

    @Query("""UPDATE orion_memory_claims SET status = 'SUPERSEDED',
        validUntil = CASE WHEN validUntil IS NULL OR validUntil > :at THEN :at ELSE validUntil END,
        version = version + 1
        WHERE memoryId = :id AND status = 'ACTIVE'""")
    protected abstract fun supersedeClaims(id: String, at: Long)

    @Query("DELETE FROM orion_memories WHERE memoryId = :id AND version = :version")
    protected abstract fun deleteMemory(id: String, version: Long): Int

    @Transaction
    open fun updateReminderWithEvent(id: String, version: Long, content: ByteArray, sensitivity: Sensitivity,
        scheduledAt: Long, zoneId: String, at: Long, event: DomainEventOutboxEntity): ActionMutation {
        requireVersion(version)
        val current = reminder(id) ?: return ActionMutation.NOT_FOUND
        if (current.version != version || current.status != ReminderStatus.PENDING) return ActionMutation.CONFLICT
        requireEncryptedContent(content)
        ZoneId.of(zoneId)
        requireCreationEvent(id, at, "REMINDER_UPDATED", sensitivity, event, version + 1)
        check(eventCount() < 10_000) { "Durable event capacity reached." }
        if (editReminder(id, version, content, sensitivity, scheduledAt, zoneId, at) != 1) return ActionMutation.CONFLICT
        insertEvent(event)
        return ActionMutation.APPLIED
    }

    @Transaction
    open fun cancelReminderWithEvent(id: String, version: Long, at: Long, event: DomainEventOutboxEntity): ActionMutation {
        requireVersion(version)
        val current = reminder(id) ?: return ActionMutation.NOT_FOUND
        if (current.version != version || current.status != ReminderStatus.PENDING) return ActionMutation.CONFLICT
        requireCreationEvent(id, at, "REMINDER_CANCELLED", current.sensitivity, event, version + 1)
        check(eventCount() < 10_000) { "Durable event capacity reached." }
        if (cancelReminder(id, version, at) != 1) return ActionMutation.CONFLICT
        insertEvent(event)
        return ActionMutation.APPLIED
    }

    @Transaction
    open fun supersedeWithEvents(id: String, version: Long, replacement: MemoryEntity,
        claims: List<MemoryClaimEntity>, priorEvent: DomainEventOutboxEntity,
        replacementEvent: DomainEventOutboxEntity): ActionMutation {
        requireVersion(version)
        val current = memory(id) ?: return ActionMutation.NOT_FOUND
        if (current.version != version || current.status != MemoryStatus.ACTIVE) return ActionMutation.CONFLICT
        require(replacement.memoryId != id && replacement.supersedesId == id)
        require(replacement.createdAt >= current.updatedAt)
        require(claims(id).all { claim ->
            claim.status != MemoryStatus.ACTIVE ||
                claim.validFrom < minOf(claim.validUntil ?: replacement.createdAt, replacement.createdAt)
        }) { "Supersession must preserve nonempty claim validity." }
        requireCreationEvent(id, replacement.createdAt, "MEMORY_SUPERSEDED", current.sensitivity, priorEvent, version + 1)
        check(eventCount() <= 9_998) { "Durable event capacity reached." }
        // Reuse creation validation inside this transaction; supersedesId is set only by this guarded path.
        createMemoryWithClaimsAndEvent(replacement.copy(supersedesId = null), claims, replacementEvent)
        check(linkSupersession(replacement.memoryId, id) == 1)
        check(supersedeMemory(id, version, replacement.createdAt) == 1)
        supersedeClaims(id, replacement.createdAt)
        insertEvent(priorEvent)
        return ActionMutation.APPLIED
    }

    @Query("UPDATE orion_memories SET supersedesId = :priorId WHERE memoryId = :id AND version = 0")
    protected abstract fun linkSupersession(id: String, priorId: String): Int

    @Transaction
    open fun deleteMemoryWithEvent(id: String, version: Long, at: Long, event: DomainEventOutboxEntity): ActionMutation {
        requireVersion(version)
        val current = memory(id) ?: return ActionMutation.NOT_FOUND
        if (current.version != version) return ActionMutation.CONFLICT
        requireCreationEvent(id, at, "MEMORY_DELETED", current.sensitivity, event, version + 1)
        check(eventCount() < 10_000) { "Durable event capacity reached." }
        if (deleteMemory(id, version) != 1) return ActionMutation.CONFLICT
        insertEvent(event)
        return ActionMutation.APPLIED
    }

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
            require(claim.sensitivity.ordinal <= memory.sensitivity.ordinal) { "Memory sensitivity must cover every claim." }
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

    private fun requireVersion(version: Long) { require(version >= 0 && version < Long.MAX_VALUE) }

    private fun requireCreationEvent(id: String, at: Long, type: String, sensitivity: Sensitivity, event: DomainEventOutboxEntity,
        version: Long = 0) {
        requireTechnicalUuid(event.eventId)
        requireTechnicalUuid(event.correlationId)
        require(event.aggregateId == id && event.aggregateVersion == version && event.occurredAt == at && event.eventType == type)
        require(event.payloadSensitivity == sensitivity)
        require(event.schemaVersion == 1 && event.status == DomainEventDeliveryStatus.PENDING && event.attemptCount == 0 &&
            event.version == 0L && event.deliveredAt == null && event.nextAttemptAt == at)
    }
}
