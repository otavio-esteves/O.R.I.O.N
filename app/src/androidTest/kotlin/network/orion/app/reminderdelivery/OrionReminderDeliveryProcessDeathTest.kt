package network.orion.app.reminderdelivery

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import network.orion.app.notifications.*
import network.orion.core.actions.*
import network.orion.core.commands.notifications.ValidatedPostNotification
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Capability
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.notifications.NotificationAvailability
import network.orion.core.orchestrator.reminders.*
import network.orion.core.security.*
import network.orion.core.skills.metadata.SkillDescriptor
import network.orion.core.time.*
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.data.reminders.OrionReminderStore
import network.orion.skills.notifications.NotificationPostResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual host SIGKILL uses an explicitly SYNTHETIC independently durable external
 * marker after the committed claim, outside Room and the barrier monitor. It is
 * not NotificationManager proof. Default suite mode simulates that fatal boundary.
 */
@RunWith(AndroidJUnit4::class)
class OrionReminderDeliveryProcessDeathTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun committedReminderDeliveryClaimSurvivesDeathWithoutResending() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val mode = arguments.getString("orion_transaction_mode") ?: "rollback"
        require(mode in setOf("rollback", "prepare", "verify"))
        val fixture = arguments.getString("orion_transaction_fixture")?.let {
            require(UUID.fromString(it).toString() == it); UUID.fromString(it)
        } ?: run { require(mode == "rollback"); UUID.randomUUID() }
        val name = "orion_reminder_delivery_death_$fixture.db"
        val checkpoint = File(context.filesDir, "orion_transaction_$fixture.ready")
        val marker = File(context.filesDir, "orion_reminder_delivery_$fixture.marker")
        val snapshot = File(context.filesDir, "orion_reminder_delivery_$fixture.snapshot")
        if (mode == "verify") {
            require(context.getDatabasePath(name).isFile && checkpoint.isFile && marker.isFile && snapshot.isFile)
            assertNotEquals("Verification must execute in a different process", checkpoint.readText(), Process.myPid().toString())
        }
        else require(!context.getDatabasePath(name).exists() && !checkpoint.exists() && !marker.exists() && !snapshot.exists())
        var elapsed = 100L
        var wall = Instant.parse("2026-11-07T10:00:00Z")
        val due = wall.plusSeconds(60)
        val clock = object : OrionClock {
            override fun wallNow() = wall
            override fun monotonicNow() = MonotonicInstant(BootSessionId(7), elapsed)
        }
        val encryption = OrionFieldEncryption(object : FieldKeyProvider {
            override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 67 }, "AES"))
            override fun keyForVersion(version: Int) = if (version == 1) currentKey() else FieldKeyResult.Unavailable
        })
        val db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
        val barrier = StartupBarrier()
        barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.DEGRADED_SAFE)
        val reminderId = UUID.nameUUIDFromBytes("$fixture/reminder".toByteArray(Charsets.UTF_8))
        var invokes = 0
        lateinit var pipeline: OrionReminderDeliveryPipeline
        val poster = object : OrionNotificationPoster {
            override fun permissionGranted() = true
            override fun availability() = NotificationAvailability.AVAILABLE
            override fun post(actionId: UUID, notification: ValidatedPostNotification,
                permit: OrionNotificationInvocationPermit): NotificationPostResult {
                assertFalse(db.inTransaction())
                assertFalse("External invocation must not hold the admission monitor", Thread.holdsLock(barrier))
                val observer = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
                try { assertEquals(ActionStatus.RUNNING, observer.actions().find(actionId.toString())?.status) }
                finally { observer.close() }
                if (!permit.allows()) return NotificationPostResult.NOT_INVOKED
                assertEquals("O.R.I.O.N.", notification.title)
                assertEquals("Você tem um lembrete.", notification.body)
                val row = checkNotNull(db.actions().find(actionId.toString()))
                val reminder = checkNotNull(db.reminders().find(reminderId.toString()))
                val stored = (pipeline.actions.read(actionId) as ActionReadResult.Ready).action
                durableWrite(snapshot, bindingSnapshot(row, reminder, checkNotNull(stored.confirmation), db.events().count()))
                invokes++
                val previous = if (marker.exists()) marker.readText().trim().toInt() else 0
                durableWrite(marker, "${previous + 1}\n")
                if (mode == "prepare") {
                    durableWrite(checkpoint, Process.myPid().toString())
                    CountDownLatch(1).await(45, TimeUnit.SECONDS)
                    throw AssertionError("Host did not SIGKILL the committed reminder-delivery claim.")
                }
                throw SimulatedExternalDeath()
            }
        }
        pipeline = OrionReminderDeliveryPipeline(db, encryption, clock, barrier, { CoreReadiness.DEGRADED_SAFE },
            { true }, OrionProcessRole.MAIN, { true }, poster)
        val channel = pipeline.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
        try {
            if (mode != "verify") {
                OrionReminderStore(db, encryption, clock).create(reminderId, byteArrayOf(1), Sensitivity.SENSITIVE,
                    due, ZoneOffset.UTC, fixture)
                val gateway = pipeline.gateway(channel)
                val prepared = gateway.prepare(reminderId, 0) as ReminderDeliveryPreparation.Ready
                assertEquals(ReminderDeliveryOutcome.ENABLED, gateway.confirm(prepared.preview))
                wall = due
                channel.onResumed(false); channel.onWindowFocus(false)
                try {
                    pipeline.executeDue(reminderId, prepared.preview.generation)
                    fail("Synthetic external death boundary was not reached")
                } catch (_: SimulatedExternalDeath) { require(mode == "rollback") }
                assertEquals(1, invokes)
            } else wall = due
            val reminder = checkNotNull(db.reminders().find(reminderId.toString()))
            val actionId = UUID.fromString(checkNotNull(reminder.deliveryActionId))
            val row = checkNotNull(db.actions().find(actionId.toString()))
            val receipt = checkNotNull((pipeline.actions.read(actionId) as ActionReadResult.Ready).action.confirmation)
            val originalSnapshot = snapshot.readText()
            assertEquals(ActionStatus.RUNNING, row.status)
            assertEquals(ReminderStatus.PENDING, reminder.status)
            assertNull(reminder.lastFiredAt)
            assertEquals(1, row.attemptCount)
            assertEquals("1", marker.readText().trim())
            assertEquals(originalSnapshot, bindingSnapshot(row, reminder, receipt, db.events().count()))
            val priorVersion = row.version
            elapsed = 31_100 // Injected same-boot expired lease; expiry never proves absence of the effect.
            val deny = object : ActionExecutionAuthority {
                override fun startupBarrierOpen() = false
                override fun descriptorFor(skill: String): SkillDescriptor? = null
                override fun currentPolicyVersion() = "orion_reminder_delivery_recovery_fixture"
                override fun policyAllows(request: NormalizedActionRequest) = false
                override fun capabilityAvailable(capability: Capability) = false
                override fun permissionGranted(permission: String) = false
                override fun userPresent() = false
            }
            barrier.beginRecovery()
            val recovery = OrionActionExecutionStore(db, pipeline.actions, clock, deny)
            assertEquals(1, recovery.recoverAbandoned().recovered)
            val recovered = checkNotNull(db.actions().find(actionId.toString()))
            assertEquals(ActionStatus.MANUAL_REVIEW, recovered.status)
            assertEquals(priorVersion + 1, recovered.version)
            assertEquals(row.executionId, recovered.executionId)
            assertEquals(1, recovered.attemptCount)
            assertEquals(receipt, (pipeline.actions.read(actionId) as ActionReadResult.Ready).action.confirmation)
            assertArrayEquals(row.argumentsEnvelope, recovered.argumentsEnvelope)
            assertArrayEquals(row.targetEnvelope, recovered.targetEnvelope)
            barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.DEGRADED_SAFE)
            assertEquals(ReminderDeliveryOutcome.UNKNOWN, pipeline.executeDue(reminderId, reminder.fireGeneration))
            assertEquals(0, recovery.recoverAbandoned().recovered)
            assertEquals(ReminderStatus.PENDING, db.reminders().find(reminderId.toString())?.status)
            assertNull(db.reminders().find(reminderId.toString())?.lastFiredAt)
            assertEquals("1", marker.readText().trim())
            assertEquals(if (mode == "verify") 0 else 1, invokes)
            db.openHelper.readableDatabase.query("SELECT count(*) FROM orion_domain_event_outbox WHERE eventType = 'REMINDER_FIRED'").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            if (mode == "verify") InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("orion_transaction_verified_pid", Process.myPid().toString())
            })
        } finally {
            channel.close(); db.close()
            if (mode != "prepare") {
                context.deleteDatabase(name); checkpoint.delete(); marker.delete(); snapshot.delete()
            }
        }
    }

    private fun bindingSnapshot(row: ActionRequestEntity, reminder: ReminderEntity,
        receipt: ActionConfirmationSnapshot, eventCount: Int): String = listOf(
        row.actionId, row.executionId, row.version, row.attemptCount,
        digest(row.argumentsEnvelope), digest(row.targetEnvelope), reminder.reminderId,
        reminder.fireGeneration, reminder.scheduledAt, reminder.deliveryActionId, reminder.version,
        receipt.actionId, receipt.normalizedPayloadHash, receipt.confirmedPayloadHash, receipt.confirmationFingerprint,
        receipt.policyVersionAtConfirmation, receipt.confirmedAt, receipt.confirmationExpiresAt, receipt.payloadLockedAt,
        eventCount,
    ).joinToString("\n", postfix = "\n")

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun durableWrite(file: File, value: String) {
        FileOutputStream(file).use { out -> out.write(value.toByteArray(Charsets.UTF_8)); out.fd.sync() }
    }

    private class SimulatedExternalDeath : Error("Synthetic reminder delivery death boundary")
}
