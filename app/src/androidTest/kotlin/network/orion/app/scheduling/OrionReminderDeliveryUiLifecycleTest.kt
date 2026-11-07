package network.orion.app.scheduling

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import network.orion.app.notifications.*
import network.orion.app.reminderdelivery.OrionReminderDeliveryPipeline
import network.orion.app.reminderdelivery.OrionReminderDeliveryPolicy
import network.orion.core.actions.*
import network.orion.core.commands.notifications.ValidatedPostNotification
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.notifications.NotificationAvailability
import network.orion.core.orchestrator.reminders.*
import network.orion.core.security.*
import network.orion.core.time.*
import network.orion.data.database.*
import network.orion.data.reminders.OrionReminderStore
import network.orion.skills.notifications.NotificationPostResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** SQLite reopen/reconstructed UI evidence with synthetic keys; no actual process-death claim. */
@RunWith(AndroidJUnit4::class)
class OrionReminderDeliveryUiLifecycleTest {
    @Test fun cancellingUiJobDuringEnqueueCannotLoseCommittedAuthorizationHandoff() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "orion_delivery_handoff_${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
        val barrier = StartupBarrier().also { it.releaseAfterRecovery(it.beginRecovery(), CoreReadiness.DEGRADED_SAFE) }
        val wall = Instant.parse("2026-11-07T10:00:00Z")
        val clock = object : OrionClock {
            override fun wallNow() = wall
            override fun monotonicNow() = MonotonicInstant(BootSessionId(11), 100)
        }
        var duringEncryption: (() -> Unit)? = null
        val keys = object : FieldKeyProvider {
            override fun currentKey(): FieldKeyResult {
                duringEncryption?.also { duringEncryption = null }?.invoke()
                return FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
            }
            override fun keyForVersion(version: Int) = currentKey()
        }
        val encryption = OrionFieldEncryption(keys)
        val handedOff = java.util.concurrent.CopyOnWriteArrayList<UUID>()
        val wrapperCallbacks = java.util.concurrent.atomic.AtomicInteger()
        val poster = object : OrionNotificationPoster {
            override fun permissionGranted() = true
            override fun availability() = NotificationAvailability.AVAILABLE
            override fun post(actionId: UUID, notification: ValidatedPostNotification,
                permit: OrionNotificationInvocationPermit): NotificationPostResult = error("Registration handoff cannot post inline")
        }
        val graph = OrionReminderDeliveryPipeline(db, encryption, clock, barrier, { CoreReadiness.DEGRADED_SAFE },
            { true }, OrionProcessRole.MAIN, { true }, poster, onAuthorized = { id ->
                assertFalse(db.inTransaction()); assertFalse(Thread.holdsLock(barrier))
                assertNotNull(db.reminders().find(id.toString())?.deliveryActionId)
                handedOff += id
            })
        val ui = OrionReminderDeliveryUiGateway({ graph }, { wrapperCallbacks.incrementAndGet(); Unit }, { graph.drainUi(100); Unit })
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            ui.onResumed(true); ui.onWindowFocus(true)
            val id = UUID.randomUUID()
            OrionReminderStore(db, encryption, clock).create(id, byteArrayOf(1), Sensitivity.SENSITIVE,
                wall.plusSeconds(60), ZoneOffset.UTC, UUID.randomUUID())
            val preview = (ui.prepare(id, 0) as ReminderDeliveryPreparation.Ready).preview
            val confirmation = uiScope.launch(start = CoroutineStart.LAZY) { ui.confirm(preview) }
            // The receipt has been captured before this first confirmation-envelope key lookup.
            duringEncryption = { confirmation.cancel() }
            confirmation.start(); withTimeout(10_000) { confirmation.join() }
            assertTrue(confirmation.isCancelled)
            assertEquals(ActionStatus.CONFIRMED, db.actions().find(preview.actionId.toString())?.status)
            assertEquals(wall.toEpochMilli(), db.actions().find(preview.actionId.toString())?.confirmedAt)
            assertEquals(listOf(id), handedOff.toList())
            assertEquals(0, wrapperCallbacks.get()) // Its cancelled caller never receives the successful return.
            ui.processPending()
            assertEquals(listOf(id), handedOff.toList())
        } finally { uiScope.cancel(); ui.close(); db.close(); context.deleteDatabase(name) }
    }

    @Test fun recreatedGraphEagerlyBindsFocusedUiAndReplaysOriginalConfirmationWithoutAnotherGesture() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "orion_delivery_ui_${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
        var db = open()
        var barrier = StartupBarrier().also { it.releaseAfterRecovery(it.beginRecovery(), CoreReadiness.DEGRADED_SAFE) }
        var wall = Instant.parse("2026-11-07T10:00:00Z")
        var closeOnEncryption = false
        var gateClosures = 0
        var posts = 0
        val clock = object : OrionClock {
            override fun wallNow() = wall
            override fun monotonicNow() = MonotonicInstant(BootSessionId(11), 100)
        }
        val keys = object : FieldKeyProvider {
            override fun currentKey(): FieldKeyResult {
                if (closeOnEncryption) {
                    closeOnEncryption = false
                    gateClosures++
                    barrier.beginRecovery() // After capture/admission, during durable confirmation envelope encryption.
                }
                return FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
            }
            override fun keyForVersion(version: Int) = currentKey()
        }
        val encryption = OrionFieldEncryption(keys)
        val authorizationCallbacks = java.util.concurrent.CopyOnWriteArrayList<UUID>()
        val poster = object : OrionNotificationPoster {
            override fun permissionGranted() = true
            override fun availability() = NotificationAvailability.AVAILABLE
            override fun post(actionId: UUID, notification: ValidatedPostNotification,
                permit: OrionNotificationInvocationPermit): NotificationPostResult {
                posts++
                error("UI preparation/receipt replay must not invoke a notification")
            }
        }
        fun newGraph() = OrionReminderDeliveryPipeline(db, encryption, clock, barrier,
            { CoreReadiness.DEGRADED_SAFE }, { true }, OrionProcessRole.MAIN, { true }, poster, onAuthorized = { id ->
                assertFalse(db.inTransaction()); assertFalse(Thread.holdsLock(barrier))
                authorizationCallbacks += id
            })
        var graph = newGraph()
        var enabledCallbacks = 0
        var processingCallbacks = 0
        var ui = OrionReminderDeliveryUiGateway({ graph }, { enabledCallbacks++ }, { processingCallbacks++; graph.drainUi(100) })
        try {
            ui.onResumed(true); ui.onWindowFocus(true); ui.onCoreRecovered()
            val id = UUID.randomUUID()
            OrionReminderStore(db, encryption, clock).create(id, byteArrayOf(1), Sensitivity.SENSITIVE,
                wall.plusSeconds(60), ZoneOffset.UTC, UUID.randomUUID())
            val preview = (ui.prepare(id, 0) as ReminderDeliveryPreparation.Ready).preview
            val originalGestureAt = wall
            val expectedBinding = ActionConfirmationBinding(clock).prepare(OrionReminderDeliveryPolicy.normalized(preview))
            closeOnEncryption = true
            assertEquals(ReminderDeliveryOutcome.QUEUED, ui.confirm(preview))
            assertEquals(1, gateClosures)
            assertTrue(authorizationCallbacks.isEmpty())
            assertFalse(barrier.isOpen())
            assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(preview.actionId.toString())?.status)
            assertNull(db.reminders().find(id.toString())?.deliveryActionId)
            db.openHelper.readableDatabase.query("SELECT count(*) FROM orion_ingress_envelopes WHERE idempotencyScope='reminder.delivery.ui' AND status='QUEUED'").use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
            ui.close()
            db.close()
            db = open()
            wall = wall.plusSeconds(10) // Replay must retain the earlier original gesture timestamp.
            barrier = StartupBarrier()
            graph = newGraph()
            ui = OrionReminderDeliveryUiGateway({ graph }, { enabledCallbacks++ }, { processingCallbacks++; graph.drainUi(100) })
            // A fresh surface with no displayed preview eagerly binds to the new graph.
            ui.onResumed(true); ui.onWindowFocus(true)
            assertTrue(barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.DEGRADED_SAFE))
            ui.onCoreRecovered()
            ui.processPending()
            val action = checkNotNull(db.actions().find(preview.actionId.toString()))
            assertEquals(ActionStatus.CONFIRMED, action.status)
            assertEquals(originalGestureAt.toEpochMilli(), action.confirmedAt)
            assertEquals(originalGestureAt.toEpochMilli(), action.payloadLockedAt)
            assertEquals(expectedBinding.normalizedPayloadHash, action.confirmedPayloadHash)
            assertEquals(expectedBinding.confirmationFingerprint, action.confirmationFingerprint)
            assertEquals(OrionReminderDeliveryPolicy.expiresAt(preview.scheduledAt).toEpochMilli(), action.confirmationExpiresAt)
            assertEquals(preview.actionId.toString(), db.reminders().find(id.toString())?.deliveryActionId)
            assertEquals(1, processingCallbacks)
            assertEquals(0, enabledCallbacks) // No second human confirmation or callback was manufactured.
            assertEquals(listOf(id), authorizationCallbacks.toList())
            ui.processPending()
            assertEquals(listOf(id), authorizationCallbacks.toList())
            assertEquals(0, posts)
        } finally {
            ui.close(); db.close(); context.deleteDatabase(name)
        }
    }
}
