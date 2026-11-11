package network.orion.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.Duration
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.app.tasks.*
import network.orion.app.reminders.*
import network.orion.app.timers.*
import network.orion.app.memories.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.observability.*
import network.orion.core.policy.tasks.OrionPolicyEvidence
import network.orion.core.security.*
import network.orion.core.time.*
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.OrionRetentionMaintenance
import network.orion.data.repository.OrionRetentionPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real SQLite and production kernel/retention paths; only disposable synthetic history. */
@RunWith(AndroidJUnit4::class)
class OrionRetentionMaintenanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_retention_${UUID.randomUUID()}.db"
    private lateinit var db: OrionDatabase
    private val platformClock = AndroidOrionClock(context.contentResolver)
    private val now = Instant.parse("2026-11-10T12:00:00Z")
    private val clock = object : OrionClock {
        override fun wallNow() = now
        override fun monotonicNow() = platformClock.monotonicNow()
    }
    private val crypto = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 49 }, "AES"))
        override fun keyForVersion(version: Int) = currentKey()
    })
    private val barrier = StartupBarrier()

    @Before fun open() { db = openDatabase() }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private fun openDatabase() = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
    private fun reopen() { db.close(); db = openDatabase() }
    private fun technicalCount() = db.openHelper.readableDatabase.query("SELECT count(*) FROM orion_technical_events").use {
        check(it.moveToFirst()); it.getInt(0)
    }

    private fun seedTechnical(count: Int, at: Instant = now.minusSeconds(60)) {
        db.runInTransaction {
            db.openHelper.writableDatabase.compileStatement("""INSERT INTO orion_technical_events
                (technicalEventId,component,eventCode,occurredAt,correlationId) VALUES(?,?,?,?,?)""").use { statement ->
                repeat(count) {
                    statement.bindString(1, UUID.randomUUID().toString())
                    statement.bindString(2, "RETENTION_TEST")
                    statement.bindString(3, "SYNTHETIC_EVENT")
                    statement.bindLong(4, at.toEpochMilli())
                    statement.bindString(5, UUID.randomUUID().toString())
                    statement.executeInsert()
                }
            }
        }
    }

    private fun recoverEveryDomain() {
        val readiness = { CoreReadiness.DEGRADED_SAFE }
        val storage = { true }
        val unavailable = { OrionPolicyEvidence.NOT_SATISFIED }
        val task = OrionTaskKernel(db, crypto, clock, barrier,
            OrionTaskAdmissionOwner(OrionProcessRole.MAIN, barrier, unavailable, unavailable), readiness, storage)
        val reminder = OrionReminderKernel(db, crypto, clock, barrier,
            OrionReminderAdmissionOwner(OrionProcessRole.MAIN, barrier,
                { network.orion.core.policy.reminders.OrionPolicyEvidence.NOT_SATISFIED },
                { network.orion.core.policy.reminders.OrionPolicyEvidence.NOT_SATISFIED }), readiness, storage)
        val timer = OrionTimerKernel(db, crypto, clock, barrier,
            OrionTimerAdmissionOwner(OrionProcessRole.MAIN, barrier, unavailable, unavailable), readiness, storage)
        val memory = OrionMemoryKernel(db, crypto, clock, barrier,
            OrionMemoryAdmissionOwner(OrionProcessRole.MAIN, barrier, unavailable, unavailable), readiness, storage)
        for ((domain, recovery) in listOf("task" to task::recoverActions, "reminder" to reminder::recoverActions,
                "timer" to timer::recoverActions, "memory" to memory::recoverActions)) {
            val before = technicalCount()
            recovery()
            assertEquals("$domain recovery must not perform unreported retention", before, technicalCount())
        }
    }

    @Test fun everyDomainRecoveryPreservesHistoryBeyondTheOldHiddenCapAndItsTtl() {
        seedTechnical(2_100)
        seedTechnical(1, now.minus(Duration.ofDays(91)))
        recoverEveryDomain()
        reopen()
        assertEquals(2_101, technicalCount())
        val pruned = OrionRetentionMaintenance(db.retention(), clock).runBatch()
        assertEquals(1, pruned.technical)
        assertFalse(pruned.needsContinuation(OrionRetentionPolicy().batchSize))
        assertEquals(2_100, technicalCount())
        val snapshot = readMaintenanceSnapshot(context, db, clock, OrionMaintenanceOrigin.RECONCILIATION, true, pruned)
        assertEquals(1L, snapshot.metrics[OrionMaintenanceMetric.PRUNED_TECHNICAL])
    }

    @Test fun declaredCountLimitAndReportedBatchesSurviveDatabaseReopen() {
        val policy = OrionRetentionPolicy()
        seedTechnical(policy.maxTechnicalRows + 125)
        for (expected in listOf(50, 50, 25)) {
            reopen()
            recoverEveryDomain()
            val before = technicalCount()
            val pruned = OrionRetentionMaintenance(db.retention(), clock, policy).runBatch()
            assertEquals(expected, pruned.technical)
            assertEquals(expected, before - technicalCount())
            assertEquals(expected == policy.batchSize, pruned.needsContinuation(policy.batchSize))
            val snapshot = readMaintenanceSnapshot(context, db, clock, OrionMaintenanceOrigin.RECONCILIATION,
                !pruned.needsContinuation(policy.batchSize), pruned)
            assertEquals(expected.toLong(), snapshot.metrics[OrionMaintenanceMetric.PRUNED_TECHNICAL])
        }
        reopen()
        assertEquals(10_000, technicalCount())
        assertEquals(0, OrionRetentionMaintenance(db.retention(), clock, policy).runBatch().technical)
    }

    @Test fun startupCleanupAndMaintenanceShareOnePolicyAndReportEveryDeletionExactlyOnce() {
        val retention = OrionCoreRetentionMaintenance(db, clock)
        seedTechnical(retention.policy.maxTechnicalRows + 75)
        // Same owner is retained between the bounded pre-drain recovery pass and maintenance.
        assertEquals(50, retention.runBatch().technical)
        val current = retention.runBatch()
        assertEquals(25, current.technical)
        assertFalse(current.needsContinuation(retention.policy.batchSize))
        val report = retention.takeReport()
        assertEquals(75L, report.technical)
        val snapshot = readMaintenanceSnapshot(context, db, clock, OrionMaintenanceOrigin.RECONCILIATION, true, report)
        assertEquals(75L, snapshot.metrics[OrionMaintenanceMetric.PRUNED_TECHNICAL])
        assertEquals(OrionRetentionTotals(), retention.takeReport())
        assertEquals(10_000, technicalCount())
        reopen()
        assertEquals(0, OrionCoreRetentionMaintenance(db, clock).runBatch().technical)
    }
}
