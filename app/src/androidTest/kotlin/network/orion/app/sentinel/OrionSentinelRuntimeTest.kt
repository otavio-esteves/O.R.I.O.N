package network.orion.app.sentinel

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.app.notifications.OrionAndroidNotificationPoster
import network.orion.app.resources.OrionAndroidResourceProfileSnapshot
import network.orion.core.actions.ActionStatus
import network.orion.core.health.CoreReadiness
import network.orion.core.health.OrionHealthStatus
import network.orion.core.ingress.DurableIngressOutcome
import network.orion.core.ingress.DurableIngressRequest
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.resources.OrionMemoryPressure
import network.orion.core.resources.OrionResourceProfiles
import network.orion.core.security.FieldKeyProvider
import network.orion.core.security.FieldKeyResult
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.sentinel.OrionProactivityFeedbackKind
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionClock
import network.orion.data.database.ActionMutation
import network.orion.data.database.DomainEventOutboxEntity
import network.orion.data.database.DurableIngressStatus
import network.orion.data.database.OrionDatabase
import network.orion.data.database.OrionSentinelFeedback
import network.orion.data.database.OrionSentinelSuggestion
import network.orion.data.database.TaskEntity
import network.orion.data.database.TaskStatus
import network.orion.data.repository.LocalIngressConsumer
import network.orion.data.repository.LocalIngressDisposition
import network.orion.data.repository.OrionDurableIngressStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The host fixture grants notifications to this APK before running this suite. */
@RunWith(AndroidJUnit4::class)
class OrionSentinelRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var db: OrionDatabase
    private lateinit var runtime: OrionSentinelRuntime
    private val barrier = StartupBarrier()
    private var now = Instant.parse("2026-11-21T12:00:00Z")
    private var saver = false
    private val posted = mutableSetOf<String>()
    private val clock = object : OrionClock {
        override fun wallNow() = now
        override fun monotonicNow() = MonotonicInstant(BootSessionId(5), android.os.SystemClock.elapsedRealtime())
    }
    private val encryption = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
        override fun keyForVersion(version: Int) = currentKey()
    })

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(context, OrionDatabase::class.java).build()
        barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.DEGRADED_SAFE)
        OrionAndroidNotificationPoster(context, clock).initializeChannels()
        runtime = OrionSentinelRuntime(context, db, encryption, clock, barrier, { CoreReadiness.DEGRADED_SAFE },
            { true }, { OrionHealthStatus.DEGRADED }, zone = { ZoneOffset.UTC }, observeResources = {
                val policy = OrionResourceProfiles.select(75, 0, saver, OrionMemoryPressure.NONE, false)
                OrionAndroidResourceProfileSnapshot(75, false, 0, saver, true, false, policy)
            })
    }

    @After fun finish() {
        posted.forEach { notifications.cancel(it, 0) }
        db.close()
    }

    @Test fun heartbeatPostsOneGenericSuggestionAndPersistsFeedback() {
        taskOpen(30, Sensitivity.SECRET)
        val task = taskOpen(26, Sensitivity.PERSONAL)
        runtime.maintain(true, null)
        assertFalse(runtime.enabled())
        assertEquals(0, db.sentinel().reservationCount())
        assertTrue(runtime.setEnabled(true))
        runtime.maintain(false, null)
        assertEquals(0, db.sentinel().reservationCount())
        saver = true
        assertEquals(OrionHealthStatus.DEGRADED, runtime.maintain(true, null))
        assertEquals(0, db.sentinel().reservationCount())
        saver = false
        assertEquals(OrionHealthStatus.HEALTHY, runtime.maintain(true, null))
        val suggestion = db.sentinel().recent(0L).single()
        posted += suggestion.suggestionId
        assertEquals(task.toString(), suggestion.taskId)
        val notification = notifications.activeNotifications.single { it.tag == suggestion.suggestionId }.notification
        assertEquals(OrionAndroidNotificationPoster.PROACTIVE_CHANNEL, notification.channelId)
        val open = checkNotNull(notification.contentIntent)
        val dismiss = checkNotNull(notification.deleteIntent)
        val snooze = checkNotNull(notification.actions.single().actionIntent)
        assertTrue(open.isImmutable && open.isActivity)
        assertTrue(dismiss.isImmutable && dismiss.isBroadcast)
        assertTrue(snooze.isImmutable && snooze.isBroadcast)
        assertEquals(listOf("SHOWN"), db.sentinel().feedback(suggestion.suggestionId).map { it.kind })

        now = now.plusSeconds(90)
        repeat(2) { assertTrue(runtime.receive(UUID.fromString(suggestion.suggestionId), OrionProactivityFeedbackKind.OPENED)) }
        val feedback = db.sentinel().feedback(suggestion.suggestionId).associateBy { it.kind }
        assertEquals(setOf("SHOWN", "OPENED"), feedback.keys)
        assertEquals(90_000L, feedback.getValue("OPENED").timeToActionMillis)
        assertEquals(ActionMutation.APPLIED, db.tasks().changeStatusWithEvent(task.toString(), 0, TaskStatus.COMPLETED,
            now.toEpochMilli(), taskEvent(task, now, Sensitivity.PERSONAL, "TASK_COMPLETED", 1)))
        runtime.maintain(true, now)
        assertTrue(db.sentinel().feedback(suggestion.suggestionId).any { it.kind == "COMPLETED" })
        assertEquals(1, notifications.activeNotifications.count { it.tag == suggestion.suggestionId })
        assertTrue(runtime.setEnabled(false))
    }

    @Test fun quietHoursAndTopicCooldownDoNotReserveOtherTasks() {
        now = Instant.parse("2026-11-21T22:00:00Z")
        taskOpen(50, Sensitivity.PERSONAL)
        taskOpen(40, Sensitivity.PERSONAL)
        assertTrue(runtime.setEnabled(true))
        runtime.maintain(true, null)
        assertEquals(0, db.sentinel().reservationCount())

        now = now.plus(Duration.ofHours(10))
        runtime.maintain(true, null)
        val first = db.sentinel().recent(0).single()
        posted += first.suggestionId
        now = now.plus(Duration.ofHours(2))
        runtime.maintain(true, null)
        assertEquals(1, db.sentinel().reservationCount())
        assertTrue(runtime.receive(UUID.fromString(first.suggestionId), OrionProactivityFeedbackKind.SNOOZED))
        now = now.plus(Duration.ofHours(23))
        runtime.maintain(true, null)
        assertEquals(1, db.sentinel().reservationCount())
    }

    @Test fun unacknowledgedSuggestionRecordsIgnoredOnlyOnce() {
        taskOpen(48, Sensitivity.PERSONAL)
        assertTrue(runtime.setEnabled(true))
        runtime.maintain(true, null)
        val first = db.sentinel().recent(0).single()
        posted += first.suggestionId
        now = now.plus(Duration.ofHours(24))
        repeat(2) { runtime.maintain(true, null) }
        assertEquals(1, db.sentinel().reservationCount())
        assertEquals(setOf("SHOWN", "IGNORED"), db.sentinel().feedback(first.suggestionId).map { it.kind }.toSet())
    }

    @Test fun oneRemainingOutboxSlotCommitsDeliveryAndShown() {
        taskOpen(48, Sensitivity.PERSONAL)
        assertTrue(runtime.setEnabled(true))
        db.openHelper.writableDatabase.execSQL("""
            INSERT INTO orion_domain_event_outbox
                (eventId, aggregateId, aggregateVersion, eventType, schemaVersion, payloadSensitivity,
                occurredAt, correlationId, status, attemptCount, nextAttemptAt, version)
            WITH RECURSIVE fixture(id) AS (SELECT 1 UNION ALL SELECT id + 1 FROM fixture WHERE id < 9997)
            SELECT 'fixture-' || id, 'fixture', 0, 'TASK_CREATED', 1, 'PERSONAL',
                0, 'fixture', 'PENDING', 0, 0, 0 FROM fixture
        """.trimIndent())
        assertEquals(9999, db.events().count())
        assertEquals(OrionHealthStatus.HEALTHY, runtime.maintain(true, null))
        val id = db.sentinel().recent(0).single().suggestionId
        posted += id
        assertTrue(notifications.activeNotifications.any { it.tag == id })
        assertEquals("SUCCEEDED", db.actions().find(id)?.status?.name)
        assertEquals("SHOWN", db.sentinel().feedback(id).single().kind)
        assertEquals(10000, db.events().count())
    }

    @Test fun committedSnoozeIsReconciledWithoutAnotherCallback() {
        taskOpen(48, Sensitivity.PERSONAL)
        assertTrue(runtime.setEnabled(true))
        assertEquals(OrionHealthStatus.HEALTHY, runtime.maintain(true, null))
        val id = db.sentinel().recent(0).single().suggestionId
        posted += id
        // Persist the fact while deliberately omitting the Android cancellation.
        db.sentinel().record(OrionSentinelFeedback(id, "SNOOZED", now.toEpochMilli(), 0))
        assertTrue(notifications.activeNotifications.any { it.tag == id })
        repeat(2) { runtime.maintain(false, null) }
        val deadline = android.os.SystemClock.elapsedRealtime() + 1000
        while (notifications.activeNotifications.any { it.tag == id } &&
            android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        assertFalse(notifications.activeNotifications.any { it.tag == id })
        assertTrue(db.sentinel().wasSnoozed(id))
    }

    @Test fun expiredPrepareDoesNotStarveLaterTasks() {
        val older = taskOpen(60, Sensitivity.PERSONAL)
        val next = taskOpen(48, Sensitivity.PERSONAL)
        assertTrue(runtime.setEnabled(true))
        val key = UUID.nameUUIDFromBytes("orion_sentinel/v1/PREPARE/$older".toByteArray())
        val id = UUID.randomUUID()
        val store = OrionDurableIngressStore(db, encryption, clock, barrier,
            LocalIngressConsumer { LocalIngressDisposition.REJECTED }, "sentinel")
        assertEquals(DurableIngressOutcome.QUEUED, store.enqueue(
            DurableIngressRequest(id, "sentinel", key, "orion_heartbeat", "PREPARE", 1, Sensitivity.PERSONAL,
                true, older, null, 60_000, Duration.ofDays(90).toMillis(), correlationId = key),
        ))
        now = now.plusSeconds(61)
        assertEquals(OrionHealthStatus.HEALTHY, runtime.maintain(true, null))
        val delivered = db.sentinel().recent(0).single()
        posted += delivered.suggestionId
        assertEquals(DurableIngressStatus.EXPIRED, db.ingress().find(id.toString())?.status)
        assertEquals(next.toString(), delivered.taskId)
    }

    @Test fun snoozeWorksForVisibleNotificationWhenShownCommitFailed() {
        taskOpen(48, Sensitivity.PERSONAL)
        assertTrue(runtime.setEnabled(true))
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER orion_test_reject_shown BEFORE INSERT ON orion_sentinel_feedback
            WHEN NEW.kind = 'SHOWN' BEGIN SELECT RAISE(ABORT, 'synthetic'); END
        """.trimIndent())
        assertEquals(OrionHealthStatus.DEGRADED, runtime.maintain(true, null))
        val id = db.sentinel().recent(0).single().suggestionId
        posted += id
        assertEquals(ActionStatus.UNKNOWN, db.actions().find(id)?.status)
        assertTrue(notifications.activeNotifications.any { it.tag == id })
        assertTrue(runtime.receive(UUID.fromString(id), OrionProactivityFeedbackKind.SNOOZED))
        val stop = android.os.SystemClock.elapsedRealtime() + 1_000
        while (notifications.activeNotifications.any { it.tag == id } &&
            android.os.SystemClock.elapsedRealtime() < stop) Thread.sleep(10)
        assertFalse(notifications.activeNotifications.any { it.tag == id })
        assertEquals("SNOOZED", db.sentinel().feedback(id).single().kind)
        assertEquals(ActionStatus.UNKNOWN, db.actions().find(id)?.status)
    }

    @Test fun expiredReservationsReleaseCapacityWithoutAllowingClockRollbackReplay() {
        assertTrue(runtime.setEnabled(true))
        val initial = now
        val old = now.minus(Duration.ofDays(35))
        val first = UUID.randomUUID()
        db.runInTransaction {
            repeat(512) { index ->
                val id = if (index == 0) first else UUID.randomUUID()
                val created = old.minus(Duration.ofDays(2))
                db.tasks().createWithEvent(TaskEntity(id.toString(), byteArrayOf(1), Sensitivity.PERSONAL,
                    created.toEpochMilli(), created.toEpochMilli()), taskEvent(id, created, Sensitivity.PERSONAL, "TASK_CREATED", 0))
                db.sentinel().reserve(OrionSentinelSuggestion(UUID.randomUUID().toString(), id.toString(), old.toEpochMilli()))
            }
        }
        repeat(21) { runtime.maintain(false, null) }
        assertEquals(0, db.sentinel().reservationCount())
        assertEquals(initial.toEpochMilli(), db.sentinel().consent()?.evaluationFloor)
        now = old
        runtime.maintain(true, null)
        assertEquals(0, db.sentinel().reservationCount())
        now = initial
        taskOpen(48, Sensitivity.PERSONAL)
        assertEquals(OrionHealthStatus.HEALTHY, runtime.maintain(true, null))
        posted += db.sentinel().recent(0).single().suggestionId
    }

    private fun taskOpen(hoursAgo: Long, sensitivity: Sensitivity): UUID {
        val id = UUID.randomUUID()
        val created = now.minus(Duration.ofHours(hoursAgo))
        db.tasks().createWithEvent(TaskEntity(id.toString(), byteArrayOf(1), sensitivity,
            created.toEpochMilli(), created.toEpochMilli()), taskEvent(id, created, sensitivity, "TASK_CREATED", 0))
        return id
    }

    private fun taskEvent(id: UUID, at: Instant, sensitivity: Sensitivity, type: String, version: Long) =
        DomainEventOutboxEntity(UUID.randomUUID().toString(), id.toString(), version, type, 1, sensitivity,
            at.toEpochMilli(), UUID.randomUUID().toString())
}
