package network.orion.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/** Per-table batch is bounded; domain user data, UNKNOWN, MANUAL_REVIEW and DEAD_LETTER are preserved. */
data class RetentionResult(val events: Int, val ingress: Int, val actions: Int, val audit: Int, val technical: Int)

@Dao
abstract class OrionRetentionDao {
    @Query("""DELETE FROM orion_domain_event_outbox WHERE eventId IN (
        SELECT eventId FROM orion_domain_event_outbox WHERE status = 'DELIVERED'
        AND deliveredAt < :before ORDER BY deliveredAt, eventId LIMIT :limit)""")
    protected abstract fun pruneEvents(before: Long, limit: Int): Int

    @Query("""DELETE FROM orion_ingress_envelopes WHERE ingressId IN (
        SELECT ingressId FROM orion_ingress_envelopes WHERE status IN ('CONSUMED','REJECTED','EXPIRED')
        AND updatedAt < :before AND idempotencyRetainUntil < :now ORDER BY updatedAt, ingressId LIMIT :limit)""")
    protected abstract fun pruneIngress(before: Long, now: Long, limit: Int): Int

    @Query("""SELECT actionId FROM orion_action_requests WHERE status IN ('SUCCEEDED','FAILED','CANCELLED')
        AND updatedAt < :before AND idempotencyRetainUntil < :now
        ORDER BY updatedAt, actionId LIMIT :limit""")
    protected abstract fun actionIdsToPrune(before: Long, now: Long, limit: Int): List<String>

    @Query("DELETE FROM orion_action_audit WHERE actionId IN (:ids)")
    protected abstract fun pruneActionAudit(ids: List<String>): Int

    @Query("DELETE FROM orion_action_requests WHERE actionId IN (:ids)")
    protected abstract fun pruneActions(ids: List<String>): Int

    @Query("""DELETE FROM orion_audit WHERE auditId IN (
        SELECT auditId FROM orion_audit WHERE preserveUntilReview = 0 AND occurredAt < :before
        ORDER BY occurredAt, auditId LIMIT :limit)""")
    protected abstract fun pruneAudit(before: Long, limit: Int): Int

    @Query("""DELETE FROM orion_technical_events WHERE technicalEventId IN (
        SELECT technicalEventId FROM orion_technical_events WHERE occurredAt < :before
        OR technicalEventId IN (SELECT technicalEventId FROM orion_technical_events
            ORDER BY occurredAt DESC, technicalEventId DESC LIMIT -1 OFFSET :maxTechnicalRows)
        ORDER BY occurredAt, technicalEventId LIMIT :limit)""")
    protected abstract fun pruneTechnical(before: Long, maxTechnicalRows: Int, limit: Int): Int

    /** Cutoffs come from an injected wall clock and explicit retention policy, never a raw clock here. */
    @Transaction
    open fun prune(now: Long, eventsBefore: Long, ingressBefore: Long, actionsBefore: Long,
        auditBefore: Long, technicalBefore: Long, maxTechnicalRows: Int, batchSize: Int): RetentionResult {
        require(batchSize in 1..100 && maxTechnicalRows in 100..100_000)
        require(listOf(eventsBefore, ingressBefore, actionsBefore, auditBefore, technicalBefore).all { it <= now })
        val actionIds = actionIdsToPrune(actionsBefore, now, batchSize)
        pruneActionAudit(actionIds)
        return RetentionResult(pruneEvents(eventsBefore, batchSize), pruneIngress(ingressBefore, now, batchSize),
            pruneActions(actionIds), pruneAudit(auditBefore, batchSize), pruneTechnical(technicalBefore, maxTechnicalRows, batchSize))
    }
}
