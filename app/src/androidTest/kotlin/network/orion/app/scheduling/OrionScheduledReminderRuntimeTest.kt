package network.orion.app.scheduling

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.os.Bundle
import android.os.Build
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.app.OrionApplication
import network.orion.core.actions.ActionStatus
import network.orion.core.health.OrionHealthStatus
import network.orion.core.health.OrionSubsystem
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.reminders.*
import network.orion.core.security.AndroidKeystoreFieldKeyProvider
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.time.AndroidOrionClock
import network.orion.data.database.*
import network.orion.data.reminders.OrionReminderStore
import network.orion.scheduler.alarms.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit dedicated-target host protocol. Default suite mode performs no fixture
 * writes. Host must observe a real notification BEFORE verification instrumentation,
 * so its startup cannot substitute for autonomous production boot/return recovery.
 */
@RunWith(AndroidJUnit4::class)
class OrionScheduledReminderRuntimeTest {
    private val app: OrionApplication get() = ApplicationProvider.getApplicationContext()

    @Test fun scheduledReminderSurvivesExplicitHostLifecycleBoundary() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val mode = arguments.getString("orion_scheduled_mode") ?: return@runBlocking
        require(mode in setOf("prepare", "verify", "cleanup", "resume_doze", "inspect_denied", "inspect_approximate", "inspect_exact", "inspect_power_save"))
        val scenario = arguments.getString("orion_scheduled_scenario")
        require(scenario == null || scenario in setOf("reboot", "force_stop", "time_change", "timezone_change", "doze", "background_restriction",
            "notification_denied", "exact_access", "battery_saver", "soak"))
        require(mode != "resume_doze" || scenario == "doze")
        require(mode != "inspect_denied" || scenario == "notification_denied")
        require(mode !in setOf("inspect_approximate", "inspect_exact") || scenario == "exact_access")
        require(mode != "inspect_power_save" || scenario == "battery_saver")
        require(mode != "cleanup" || scenario == "soak")
        val delaySeconds = arguments.getString("orion_scheduled_delay_seconds")?.toLong() ?: 60L
        require(delaySeconds in 60L..86_400L)
        require(delaySeconds == 60L || scenario in setOf("notification_denied", "exact_access", "battery_saver", "soak"))
        val rawFixture = checkNotNull(arguments.getString("orion_scheduled_fixture"))
        val fixture = UUID.fromString(rawFixture).also { require(it.toString() == rawFixture) }
        val id = UUID.nameUUIDFromBytes("$fixture/runtime-reminder".toByteArray(Charsets.UTF_8))
        val metadataFile = File(app.filesDir, "orion_scheduled_runtime_$fixture.json")
        val runtime = checkNotNull(app.mainRuntime)
        withTimeout(30_000) { runtime.health.first { it.core.readiness.isStartupBarrierOpen } }
        withTimeout(30_000) { while (!runtime.taskStartupBarrier.isOpen() || runtime.reminderDelivery == null) delay(10) }
        val db = OrionDatabaseFactory.open(app)
        try {
            if (mode == "cleanup") {
                cleanupSoakRuntimeReminder(app, db, fixture)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("orion_scheduled_cleanup", "OWNED_FIXTURE_REMOVED")
                })
            } else if (mode == "prepare") {
                assertTrue("Unlock the dedicated target before preparing a new confirmation",
                    app.getSystemService(android.os.PowerManager::class.java).isInteractive &&
                        !app.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
                require(!metadataFile.exists() && db.reminders().find(id.toString()) == null)
                writeRuntimeFixtureMetadata(metadataFile, JSONObject().put("fixtureId", rawFixture).put("reminderId", id.toString()))
                val clock = AndroidOrionClock(app.contentResolver)
                val preparedAt = clock.wallNow()
                val due = preparedAt.plusSeconds(delaySeconds)
                val previousForceStop = runtime.schedulingDiagnostics()?.lastForceStopDetectedAt?.toEpochMilli()
                val store = OrionReminderStore(db,
                    OrionFieldEncryption(AndroidKeystoreFieldKeyProvider.open(app)), clock)
                // Synthetic setup only; authorization uses the real explicit delivery gateway below.
                store.create(id, byteArrayOf(1), Sensitivity.SENSITIVE, due, ZoneOffset.UTC, fixture)
                val delivery = checkNotNull(runtime.reminderDelivery)
                val channel = delivery.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
                try {
                    val gateway = delivery.gateway(channel)
                    val prepared = gateway.prepare(id, 0) as ReminderDeliveryPreparation.Ready
                    assertEquals(ReminderDeliveryOutcome.ENABLED, gateway.confirm(prepared.preview))
                    runtime.performMaintenance() // Preparation only. Verification never manually drives production.
                    val row = checkNotNull(db.reminders().find(id.toString()))
                    assertEquals(ReminderStatus.PENDING, row.status)
                    assertNotEquals(ReminderPrecision.UNSCHEDULED, row.precision)
                    assertEquals(row.fireGeneration, row.lastScheduledGeneration)
                    val action = checkNotNull(db.actions().find(checkNotNull(row.deliveryActionId)))
                    assertEquals(ActionStatus.CONFIRMED, action.status)
                    val metadata = JSONObject().put("fixtureId", fixture.toString()).put("reminderId", id.toString())
                        .put("actionId", action.actionId).put("generation", row.fireGeneration)
                        .put("requestCode", row.requestCode).put("scheduledAtEpochMillis", row.scheduledAt)
                        .put("preparedAtEpochMillis", preparedAt.toEpochMilli())
                        .put("previousForceStopDetectedAtEpochMillis", previousForceStop ?: JSONObject.NULL)
                        .put("preparedPid", Process.myPid()).put("precision", row.precision.name)
                        .put("normalizedPayloadHash", action.normalizedPayloadHash)
                        .put("confirmationFingerprint", action.confirmationFingerprint)
                    if (scenario == "doze") {
                        // A near wake-from-idle artifact can itself block OEM idle entry.
                        // Remove only this derived artifact, retaining its confirmed authority.
                        val alarms = OrionReminderAlarms(AndroidOrionAlarmBackend(app,
                            ComponentName(app, OrionReminderAlarmReceiver::class.java)))
                        assertTrue(alarms.cancel(OrionAlarmToken(id, row.fireGeneration, row.requestCode)))
                        metadata.put("dozeAlarmTemporarilyRemoved", true)
                    }
                    writeRuntimeFixtureMetadata(metadataFile, metadata)
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                        putString("orion_scheduled_prepared_pid", Process.myPid().toString())
                    })
                } finally { channel.close() }
            } else if (mode == "resume_doze") {
                require(metadataFile.isFile)
                val metadata = JSONObject(metadataFile.readText())
                require(metadata.getString("fixtureId") == fixture.toString() && metadata.getString("reminderId") == id.toString())
                require(metadata.getBoolean("dozeAlarmTemporarilyRemoved"))
                val original = checkNotNull(db.reminders().find(id.toString()))
                val actionId = metadata.getString("actionId")
                val action = checkNotNull(db.actions().find(actionId))
                assertEquals(ActionStatus.CONFIRMED, action.status)
                assertEquals(metadata.getString("normalizedPayloadHash"), action.normalizedPayloadHash)
                assertEquals(metadata.getString("confirmationFingerprint"), action.confirmationFingerprint)
                assertEquals(actionId, original.deliveryActionId)
                assertEquals(metadata.getLong("generation"), original.fireGeneration)
                assertEquals(metadata.getLong("scheduledAtEpochMillis"), original.scheduledAt)
                db.reminderSchedules().invalidateRegistrationObservation(id.toString(), original.fireGeneration)
                runtime.requestReconciliation() // Existing consent only; no resumed/focused UI channel.
                val registered = checkNotNull(db.reminders().find(id.toString()))
                assertTrue(registered.registrationConfirmed)
                assertEquals(ReminderPrecision.EXACT, registered.precision)
                assertEquals(original.fireGeneration, registered.fireGeneration)
                assertEquals(original.scheduledAt, registered.scheduledAt)
                assertEquals(actionId, registered.deliveryActionId)
                assertEquals(action.normalizedPayloadHash, db.actions().find(actionId)?.normalizedPayloadHash)
                assertEquals(action.confirmationFingerprint, db.actions().find(actionId)?.confirmationFingerprint)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("orion_scheduled_doze_registration", "ORIGINAL_DOZE_BINDING_REGISTERED")
                })
            } else if (mode.startsWith("inspect_")) {
                val metadata = JSONObject(metadataFile.readText())
                require(metadata.getString("fixtureId") == fixture.toString() && metadata.getString("reminderId") == id.toString())
                val row = checkNotNull(db.reminders().find(id.toString()))
                val action = checkNotNull(db.actions().find(metadata.getString("actionId")))
                assertEquals(metadata.getString("normalizedPayloadHash"), action.normalizedPayloadHash)
                assertEquals(metadata.getString("confirmationFingerprint"), action.confirmationFingerprint)
                assertEquals(metadata.getLong("scheduledAtEpochMillis"), row.scheduledAt)
                assertEquals(ActionStatus.CONFIRMED, action.status)
                assertEquals(ReminderStatus.PENDING, row.status)
                assertEquals(0, action.attemptCount)
                when (mode) {
                    "inspect_denied" -> {
                        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                            app.checkSelfPermission("android.permission.POST_NOTIFICATIONS"))
                        assertFalse(app.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
                        assertFalse(app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == action.actionId })
                        assertTrue(AndroidOrionClock(app.contentResolver).wallNow().toEpochMilli() >= row.scheduledAt)
                        assertEquals(OrionHealthStatus.DEGRADED, runtime.health.value.subsystems[OrionSubsystem.PERMISSIONS])
                    }
                    "inspect_approximate", "inspect_exact" -> {
                        val exact = mode == "inspect_exact"
                        assertEquals(exact, app.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms())
                        assertEquals(if (exact) ReminderPrecision.EXACT else ReminderPrecision.APPROXIMATE, row.precision)
                        assertTrue(row.registrationConfirmed)
                    }
                    "inspect_power_save" -> assertTrue(app.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode)
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("orion_scheduled_inspection", mode.uppercase())
                })
            } else {
                require(metadataFile.isFile)
                val metadata = JSONObject(metadataFile.readText())
                require(metadata.getString("fixtureId") == fixture.toString() && metadata.getString("reminderId") == id.toString())
                val actionId = UUID.fromString(metadata.getString("actionId"))
                try {
                    val manager = checkNotNull(app.getSystemService(NotificationManager::class.java))
                    withTimeout(15_000) {
                        while (db.reminders().find(id.toString())?.status != ReminderStatus.FIRED ||
                            db.actions().find(actionId.toString())?.status != ActionStatus.SUCCEEDED ||
                            manager.activeNotifications.none { it.tag == actionId.toString() && it.id == 0 }) delay(100)
                    }
                    val row = checkNotNull(db.reminders().find(id.toString()))
                    val action = checkNotNull(db.actions().find(actionId.toString()))
                    assertEquals(metadata.getString("normalizedPayloadHash"), action.normalizedPayloadHash)
                    assertEquals(metadata.getString("confirmationFingerprint"), action.confirmationFingerprint)
                    assertEquals(metadata.getLong("generation"), row.fireGeneration)
                    assertEquals(metadata.getLong("scheduledAtEpochMillis"), row.scheduledAt)
                    assertEquals(actionId.toString(), row.deliveryActionId)
                    assertNotNull(row.lastFiredAt)
                    assertTrue(checkNotNull(row.lastFiredAt) >= row.scheduledAt)
                    assertEquals(1, action.attemptCount)
                    val events = db.reminders().historicalEvents(id.toString(), row.version, "REMINDER_FIRED")
                    assertEquals(1, events.size)
                    val notification = manager.activeNotifications.single { it.tag == actionId.toString() && it.id == 0 }.notification
                    assertEquals("orion_reminders", notification.channelId)
                    assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
                    assertEquals("O.R.I.O.N.", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
                    assertEquals("Você tem um lembrete.", notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
                    assertEquals("Lembrete", notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
                    delay(300)
                    assertEquals(1, db.actions().find(actionId.toString())?.attemptCount)
                    assertEquals(1, manager.activeNotifications.count { it.tag == actionId.toString() && it.id == 0 })
                    if (scenario == "battery_saver") {
                        assertTrue(app.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode)
                    }
                    var forceStopDetectedAt: Long? = null
                    if (scenario == "background_restriction") {
                        withTimeout(5_000) {
                            while (runtime.schedulingDiagnostics()?.backgroundRestricted != true ||
                                runtime.health.value.subsystems[OrionSubsystem.PERMISSIONS] != OrionHealthStatus.DEGRADED) delay(100)
                        }
                    }
                    if (scenario == "force_stop" && Build.VERSION.SDK_INT >= 35) {
                        val preparedAt = metadata.getLong("preparedAtEpochMillis")
                        val previous = if (metadata.isNull("previousForceStopDetectedAtEpochMillis")) null
                            else metadata.getLong("previousForceStopDetectedAtEpochMillis")
                        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                            putString("orion_scheduled_primary_force_stop_observation",
                                runtime.schedulingDiagnostics()?.forceStopSignal ?: "UNAVAILABLE")
                        })
                        withTimeout(5_000) {
                            while (true) {
                                val detected = runtime.schedulingDiagnostics()?.lastForceStopDetectedAt?.toEpochMilli()
                                if (detected != null && detected >= preparedAt && detected != previous) {
                                    forceStopDetectedAt = detected
                                    break
                                }
                                delay(100)
                            }
                        }
                    }
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                        putString("orion_scheduled_verified_pid", Process.myPid().toString())
                        putString("orion_scheduled_delivery", "REAL_NOTIFICATION_AND_COMMITTED_FIRED")
                        forceStopDetectedAt?.let { putString("orion_scheduled_force_stop_detected_at", it.toString()) }
                        if (scenario == "background_restriction") {
                            putString("orion_scheduled_background_restricted", "true")
                            putString("orion_scheduled_permissions_health", "DEGRADED")
                        }
                    })
                } finally {
                    cleanup(db, fixture, id, actionId, metadata.getLong("generation"), metadata.getInt("requestCode"))
                    metadataFile.delete()
                }
            }
        } finally { db.close() }
    }

    private fun cleanup(db: OrionDatabase, fixture: UUID, reminderId: UUID, actionId: UUID, generation: Long, requestCode: Int) {
        app.getSystemService(NotificationManager::class.java)?.cancel(actionId.toString(), 0)
        OrionReminderAlarms(AndroidOrionAlarmBackend(app,
            ComponentName(app, OrionReminderAlarmReceiver::class.java))).cancel(OrionAlarmToken(reminderId, generation, requestCode))
        db.runInTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("DELETE FROM orion_ingress_envelopes WHERE correlationId = ?", arrayOf(reminderId.toString()))
            sql.execSQL("DELETE FROM orion_domain_event_outbox WHERE aggregateId = ?", arrayOf(reminderId.toString()))
            sql.execSQL("DELETE FROM orion_technical_events WHERE correlationId IN (?, ?, ?)",
                arrayOf(fixture.toString(), reminderId.toString(), actionId.toString()))
            sql.execSQL("DELETE FROM orion_action_audit WHERE actionId = ?", arrayOf(actionId.toString()))
            sql.execSQL("DELETE FROM orion_action_requests WHERE actionId = ?", arrayOf(actionId.toString()))
            sql.execSQL("DELETE FROM orion_reminders WHERE reminderId = ?", arrayOf(reminderId.toString()))
        }
    }
}
