package network.orion.app.scheduling

import android.app.KeyguardManager
import android.app.NotificationManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.app.OrionApplication
import network.orion.core.actions.ActionStatus
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.reminders.ReminderDeliveryOutcome
import network.orion.core.orchestrator.reminders.ReminderDeliveryPreparation
import network.orion.core.security.AndroidKeystoreFieldKeyProvider
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.time.AndroidOrionClock
import network.orion.data.database.OrionDatabaseFactory
import network.orion.data.database.ReminderStatus
import network.orion.data.reminders.OrionReminderStore
import network.orion.scheduler.alarms.AndroidOrionAlarmBackend
import network.orion.scheduler.alarms.OrionAlarmToken
import network.orion.scheduler.alarms.OrionReminderAlarms
import network.orion.scheduler.workers.OrionMaintenanceScheduler
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A persisted due authorization must progress even when the unique worker cannot run. */
@RunWith(AndroidJUnit4::class)
class OrionImmediateReconciliationTest {
    @Test fun systemReconciliationRebuildsLostArtifactsAndDoesNotWaitForADeferredWorker() = runBlocking {
        val app: OrionApplication = ApplicationProvider.getApplicationContext()
        assumeTrue(app.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        assumeTrue(app.getSystemService(PowerManager::class.java).isInteractive &&
            !app.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val runtime = checkNotNull(app.mainRuntime)
        runtime.recover()
        val work = WorkManager.getInstance(app)
        work.cancelUniqueWork(OrionMaintenanceScheduler.RECONCILE_NAME).result.get(5, TimeUnit.SECONDS)
        work.enqueueUniqueWork(OrionMaintenanceScheduler.RECONCILE_NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(OrionMaintenanceWorker::class.java)
                .setInitialDelay(1, TimeUnit.DAYS).build()).result.get(5, TimeUnit.SECONDS)
        val clock = AndroidOrionClock(app.contentResolver)
        val db = OrionDatabaseFactory.open(app)
        val fixture = UUID.randomUUID()
        val id = UUID.nameUUIDFromBytes("$fixture/immediate-reconciliation".toByteArray())
        val delivery = checkNotNull(runtime.reminderDelivery)
        val channel = delivery.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
        val alarms = OrionReminderAlarms(AndroidOrionAlarmBackend(app,
            android.content.ComponentName(app, OrionReminderAlarmReceiver::class.java)))
        var token: OrionAlarmToken? = null
        var actionId: UUID? = null
        try {
            val due = clock.wallNow().plusSeconds(8)
            OrionReminderStore(db, OrionFieldEncryption(AndroidKeystoreFieldKeyProvider.open(app)), clock)
                .create(id, byteArrayOf(1), Sensitivity.SENSITIVE, due, ZoneOffset.UTC, fixture)
            val gateway = delivery.gateway(channel)
            val preview = (gateway.prepare(id, 0) as ReminderDeliveryPreparation.Ready).preview
            actionId = preview.actionId
            assertEquals(ReminderDeliveryOutcome.ENABLED, gateway.confirm(preview))
            withTimeout(5_000) {
                while (db.reminders().find(id.toString())?.registrationConfirmed != true) delay(10)
            }
            val registered = checkNotNull(db.reminders().find(id.toString()))
            token = OrionAlarmToken(id, preview.generation, registered.requestCode)
            // Simulate loss of the derived OS artifact; retain the original DB authority and consent.
            assertTrue(alarms.cancel(token))
            val originalAction = checkNotNull(db.actions().find(actionId.toString()))
            runtime.requestReconciliation(rebuildAlarmArtifacts = true)
            val restored = android.app.PendingIntent.getBroadcast(app, registered.requestCode,
                android.content.Intent(AndroidOrionAlarmBackend.ACTION_REMINDER_DUE)
                    .setComponent(android.content.ComponentName(app, OrionReminderAlarmReceiver::class.java))
                    .setData(android.net.Uri.parse(token.identity)),
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE)
            assertNotNull("A system event must rebuild the artifact even when MAIN was already ready", restored)
            assertEquals(originalAction.normalizedPayloadHash, db.actions().find(actionId.toString())?.normalizedPayloadHash)
            assertEquals(originalAction.confirmationFingerprint, db.actions().find(actionId.toString())?.confirmationFingerprint)
            assertEquals(ActionStatus.CONFIRMED, db.actions().find(actionId.toString())?.status)
            assertTrue(alarms.cancel(token))
            channel.close()
            withTimeout(10_000) { while (clock.wallNow().isBefore(due)) delay(20) }
            assertEquals(ReminderStatus.PENDING, db.reminders().find(id.toString())?.status)
            assertEquals(ActionStatus.CONFIRMED, db.actions().find(actionId.toString())?.status)
            runtime.requestReconciliation()
            assertEquals(ReminderStatus.FIRED, db.reminders().find(id.toString())?.status)
            assertEquals(ActionStatus.SUCCEEDED, db.actions().find(actionId.toString())?.status)
            assertEquals(1, db.actions().find(actionId.toString())?.attemptCount)
            assertEquals(1, app.getSystemService(NotificationManager::class.java).activeNotifications
                .count { it.tag == actionId.toString() && it.id == 0 })
        } finally {
            channel.close()
            token?.let { alarms.cancel(it) }
            actionId?.let { app.getSystemService(NotificationManager::class.java).cancel(it.toString(), 0) }
            db.runInTransaction {
                val sql = db.openHelper.writableDatabase
                sql.execSQL("DELETE FROM orion_ingress_envelopes WHERE correlationId = ?", arrayOf(id.toString()))
                sql.execSQL("DELETE FROM orion_domain_event_outbox WHERE aggregateId = ?", arrayOf(id.toString()))
                actionId?.let {
                    sql.execSQL("DELETE FROM orion_action_audit WHERE actionId = ?", arrayOf(it.toString()))
                    sql.execSQL("DELETE FROM orion_action_requests WHERE actionId = ?", arrayOf(it.toString()))
                }
                sql.execSQL("DELETE FROM orion_reminders WHERE reminderId = ?", arrayOf(id.toString()))
            }
            db.close()
            work.cancelUniqueWork(OrionMaintenanceScheduler.RECONCILE_NAME).result.get(5, TimeUnit.SECONDS)
            runtime.requestReconciliation()
        }
    }
}
