package network.orion.app.scheduling

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import network.orion.app.notifications.OrionAndroidNotificationPoster
import network.orion.app.reminderdelivery.OrionReminderDeliveryPipeline
import network.orion.core.actions.ActionStatus
import network.orion.core.commands.reminders.*
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
import network.orion.scheduler.alarms.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual NotificationManager registration; synthetic consent/time on a dedicated granted target. */
@RunWith(AndroidJUnit4::class)
class OrionReminderDeliveryPlatformTest {
    @Test fun confirmedBackgroundAlertUsesReminderChannelAndCommitsOnlyAfterPlatformProof() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val platformClock = AndroidOrionClock(context.contentResolver)
        var wall = platformClock.wallNow()
        val clock = object : OrionClock {
            override fun wallNow() = wall
            override fun monotonicNow() = platformClock.monotonicNow()
        }
        val poster = OrionAndroidNotificationPoster(context, clock, OrionAndroidNotificationPoster.REMINDER_CHANNEL)
        poster.initializeChannels()
        assumeTrue("Requires explicitly granted notifications and enabled reminder channel",
            poster.availability() == NotificationAvailability.AVAILABLE)
        assumeTrue(clock.monotonicNow().bootSessionId != null)
        val name = "orion_delivery_android_${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
        val keys = object : FieldKeyProvider {
            override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 81 }, "AES"))
            override fun keyForVersion(version: Int) = currentKey()
        }
        val crypto = OrionFieldEncryption(keys)
        val barrier = StartupBarrier().apply { releaseAfterRecovery(beginRecovery(), CoreReadiness.DEGRADED_SAFE) }
        val delivery = OrionReminderDeliveryPipeline(db, crypto, clock, barrier, { CoreReadiness.DEGRADED_SAFE },
            { true }, OrionProcessRole.MAIN, { true }, poster)
        val channel = delivery.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
        val id = UUID.randomUUID()
        val at = wall.plusSeconds(60)
        val prepared = (OrionCreateReminderPreparer.prepare(CreateReminder(id, "Synthetic private reminder", at,
            ZoneOffset.UTC, id, id)) as CreateReminderPreparation.Ready).reminder
        var actionId: UUID? = null
        try {
            OrionReminderStore(db, crypto, clock).create(id, prepared.canonicalNormalizedArguments.toByteArray(),
                Sensitivity.SENSITIVE, at, ZoneOffset.UTC, id)
            val gateway = delivery.gateway(channel)
            val preview = (gateway.prepare(id, 0) as ReminderDeliveryPreparation.Ready).preview
            actionId = preview.actionId
            assertEquals(ReminderDeliveryOutcome.ENABLED, gateway.confirm(preview))
            channel.close()
            wall = at
            val cursors = object : OrionReconciliationCursors {
                override suspend fun read(name: String): String? = null
                override suspend fun write(name: String, cursor: String?) = Unit
            }
            val alarms = object : OrionReminderAlarmPort {
                override fun schedule(token: OrionAlarmToken, triggerAt: Instant, requirements: AlarmRequirements) = AlarmRegistrationResult.Unavailable
                override fun cancel(token: OrionAlarmToken) = true
            }
            val coordinator = OrionSchedulingCoordinator(db, crypto, clock, barrier, { CoreReadiness.DEGRADED_SAFE },
                { true }, cursors, delivery, alarms)
            assertNotNull(coordinator.acceptAlarm(id, preview.generation))
            assertEquals(ReminderDeliveryOutcome.POSTED, coordinator.deliverAcceptedAlarm(id, preview.generation))
            val actual = context.getSystemService(NotificationManager::class.java).activeNotifications
                .single { it.tag == preview.actionId.toString() && it.id == 0 }.notification
            assertEquals(OrionAndroidNotificationPoster.REMINDER_CHANNEL, actual.channelId)
            assertEquals(Notification.VISIBILITY_PRIVATE, actual.visibility)
            assertEquals("Você tem um lembrete.", actual.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals("Lembrete", actual.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(ActionStatus.SUCCEEDED, db.actions().find(preview.actionId.toString())!!.status)
            assertEquals(ReminderStatus.FIRED, db.reminders().find(id.toString())!!.status)
        } finally {
            actionId?.let { context.getSystemService(NotificationManager::class.java).cancel(it.toString(), 0) }
            channel.close(); db.close(); context.deleteDatabase(name)
        }
    }
}
