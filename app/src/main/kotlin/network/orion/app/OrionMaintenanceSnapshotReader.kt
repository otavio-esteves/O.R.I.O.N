package network.orion.app

import android.content.Context
import android.os.Process
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import network.orion.core.observability.*
import network.orion.core.time.OrionClock
import network.orion.data.database.OrionDatabase
import network.orion.data.database.RetentionResult

/** MAIN-only aggregate read. No business identifiers, content, errors or ciphertext leave this adapter. */
internal fun readMaintenanceSnapshot(context: Context, db: OrionDatabase, clock: OrionClock,
    origin: OrionMaintenanceOrigin, healthy: Boolean, pruned: RetentionResult, sequence: Long = 1): OrionMaintenanceSnapshot =
    readMaintenanceSnapshot(context, db, clock, origin, healthy, OrionRetentionTotals() + pruned, sequence)

internal fun readMaintenanceSnapshot(context: Context, db: OrionDatabase, clock: OrionClock,
    origin: OrionMaintenanceOrigin, healthy: Boolean, pruned: OrionRetentionTotals, sequence: Long = 1): OrionMaintenanceSnapshot {
    val queries = mapOf(
        OrionMaintenanceMetric.ACTION_TOTAL to "SELECT count(*) FROM orion_action_requests",
        OrionMaintenanceMetric.ACTION_QUEUED to "SELECT count(*) FROM orion_action_requests WHERE status IN ('PENDING','NEEDS_CONFIRMATION','CONFIRMED')",
        OrionMaintenanceMetric.ACTION_RUNNING to "SELECT count(*) FROM orion_action_requests WHERE status = 'RUNNING'",
        OrionMaintenanceMetric.ACTION_UNKNOWN to "SELECT count(*) FROM orion_action_requests WHERE status = 'UNKNOWN'",
        OrionMaintenanceMetric.ACTION_MANUAL_REVIEW to "SELECT count(*) FROM orion_action_requests WHERE status = 'MANUAL_REVIEW'",
        OrionMaintenanceMetric.EVENT_TOTAL to "SELECT count(*) FROM orion_domain_event_outbox",
        OrionMaintenanceMetric.EVENT_PENDING to "SELECT count(*) FROM orion_domain_event_outbox WHERE status = 'PENDING'",
        OrionMaintenanceMetric.EVENT_DEAD_LETTER to "SELECT count(*) FROM orion_domain_event_outbox WHERE status = 'DEAD_LETTER'",
        OrionMaintenanceMetric.INGRESS_TOTAL to "SELECT count(*) FROM orion_ingress_envelopes",
        OrionMaintenanceMetric.INGRESS_QUEUED to "SELECT count(*) FROM orion_ingress_envelopes WHERE status = 'QUEUED'",
        OrionMaintenanceMetric.TASK_TOTAL to "SELECT count(*) FROM orion_tasks",
        OrionMaintenanceMetric.REMINDER_TOTAL to "SELECT count(*) FROM orion_reminders",
        OrionMaintenanceMetric.TIMER_TOTAL to "SELECT count(*) FROM orion_timers",
        OrionMaintenanceMetric.MEMORY_TOTAL to "SELECT count(*) FROM orion_memories",
    )
    val values = db.runInTransaction(Callable {
        queries.mapValues { (_, query) -> db.openHelper.readableDatabase.query(query).use {
            check(it.moveToFirst()); it.getLong(0)
        } }.toMutableMap()
    })
    values[OrionMaintenanceMetric.PRUNED_EVENTS] = pruned.events
    values[OrionMaintenanceMetric.PRUNED_INGRESS] = pruned.ingress
    values[OrionMaintenanceMetric.PRUNED_ACTIONS] = pruned.actions
    values[OrionMaintenanceMetric.PRUNED_AUDIT] = pruned.audit
    values[OrionMaintenanceMetric.PRUNED_TECHNICAL] = pruned.technical
    val path = db.openHelper.databaseName?.let(context::getDatabasePath)
    values[OrionMaintenanceMetric.DATABASE_BYTES] = if (path == null) 0 else path.length() + File(path.path + "-wal").length()
    val monotonic = clock.monotonicNow()
    return OrionMaintenanceSnapshot(UUID.randomUUID(), clock.wallNow(), checkNotNull(monotonic.bootSessionId).bootCount,
        Process.myPid(), monotonic.elapsedRealtimeMillis, origin, healthy, values, sequence)
}
