package network.orion.core.observability

import java.time.Instant
import java.util.Collections
import java.util.UUID

enum class OrionMaintenanceOrigin {
    SYSTEM_TRIGGER, HEARTBEAT, RECONCILIATION, WORKER_UNCLASSIFIED;
    companion object {
        fun forWorker(id: UUID, heartbeatIds: Set<UUID>?, reconciliationIds: Set<UUID>?): OrionMaintenanceOrigin {
            if (heartbeatIds == null || reconciliationIds == null) return WORKER_UNCLASSIFIED
            val heartbeat = heartbeatIds?.contains(id) == true
            val reconciliation = reconciliationIds?.contains(id) == true
            return when {
                heartbeat && !reconciliation -> HEARTBEAT
                reconciliation && !heartbeat -> RECONCILIATION
                else -> WORKER_UNCLASSIFIED
            }
        }
    }
}

enum class OrionMaintenanceMetric(val key: String) {
    ACTION_TOTAL("actionTotal"), ACTION_QUEUED("actionQueued"), ACTION_RUNNING("actionRunning"),
    ACTION_UNKNOWN("actionUnknown"), ACTION_MANUAL_REVIEW("actionManualReview"),
    EVENT_TOTAL("eventTotal"), EVENT_PENDING("eventPending"), EVENT_DEAD_LETTER("eventDeadLetter"),
    INGRESS_TOTAL("ingressTotal"), INGRESS_QUEUED("ingressQueued"),
    TASK_TOTAL("taskTotal"), REMINDER_TOTAL("reminderTotal"), TIMER_TOTAL("timerTotal"), MEMORY_TOTAL("memoryTotal"),
    PRUNED_EVENTS("prunedEvents"), PRUNED_INGRESS("prunedIngress"), PRUNED_ACTIONS("prunedActions"),
    PRUNED_AUDIT("prunedAudit"), PRUNED_TECHNICAL("prunedTechnical"), DATABASE_BYTES("databaseBytes"),
}

/** One completed maintenance pass; OS log retention is bounded and sampled, not durable domain truth. */
class OrionMaintenanceSnapshot(
    val correlationId: UUID,
    val timestamp: Instant,
    val bootCount: Int,
    val processId: Int,
    val elapsedMillis: Long,
    val origin: OrionMaintenanceOrigin,
    val healthy: Boolean,
    metrics: Map<OrionMaintenanceMetric, Long>,
    val sequence: Long = 1,
) {
    val metrics: Map<OrionMaintenanceMetric, Long> = Collections.unmodifiableMap(metrics.toMap())
    init {
        require(bootCount >= 0 && processId > 0 && elapsedMillis >= 0 && sequence > 0)
        require(this.metrics.keys == OrionMaintenanceMetric.entries.toSet() && this.metrics.values.all { it >= 0 })
    }
    fun toLogLine(): String = "{\"schemaVersion\":1,\"correlationId\":\"$correlationId\",\"component\":\"SCHEDULER\"," +
        "\"event\":\"MAINTENANCE_PASS_FINISHED\",\"severity\":\"${if (healthy) "INFO" else "WARN"}\"," +
        "\"timestampMillis\":${timestamp.toEpochMilli()},\"bootCount\":$bootCount,\"processId\":$processId," +
        "\"elapsedMillis\":$elapsedMillis,\"sequence\":$sequence,\"origin\":\"${origin.name}\",\"healthy\":$healthy,\"metrics\":{" +
        OrionMaintenanceMetric.entries.joinToString(",") { "\"${it.key}\":${metrics.getValue(it)}" } + "}}"

    companion object { const val LOG_TAG = "orion_maintenance" }
}
