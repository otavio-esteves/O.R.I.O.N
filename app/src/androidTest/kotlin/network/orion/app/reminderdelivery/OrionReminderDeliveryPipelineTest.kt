package network.orion.app.reminderdelivery

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import network.orion.app.notifications.*
import network.orion.core.actions.*
import network.orion.core.commands.notifications.ValidatedPostNotification
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.model.OrionReminderRecurrenceFrequency
import network.orion.core.model.OrionReminderRecurrenceRule
import network.orion.core.orchestrator.notifications.NotificationAvailability
import network.orion.core.orchestrator.reminders.*
import network.orion.core.security.*
import network.orion.core.time.*
import network.orion.data.database.*
import network.orion.data.reminders.OrionReminderStore
import network.orion.skills.notifications.NotificationPostResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite, synthetic MAIN UI and platform proof. Not physical process-death qualification. */
@RunWith(AndroidJUnit4::class)
class OrionReminderDeliveryPipelineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_delivery_${UUID.randomUUID()}.db"
    private lateinit var db: OrionDatabase
    private lateinit var pipeline: OrionReminderDeliveryPipeline
    private lateinit var gateway: ReminderDeliveryGateway
    private lateinit var channel: OrionReminderDeliveryAdmissions.UiChannel
    private val barrier = StartupBarrier()
    private var wall = Instant.parse("2026-11-07T10:00:00Z")
    private var monotonic = MonotonicInstant(BootSessionId(7), 100)
    private var permission = true
    private var present = true
    private var invocations = 0
    private var result = NotificationPostResult.POSTED
    private var beforePermit: (() -> Unit)? = null
    private var afterInvocation: (() -> Unit)? = null
    private var duringAvailability: (() -> Unit)? = null
    private val clock = object : OrionClock {
        override fun wallNow() = wall
        override fun monotonicNow() = monotonic
    }
    private val keys = object : FieldKeyProvider {
        override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
        override fun keyForVersion(version: Int) = currentKey()
    }
    private val encryption = OrionFieldEncryption(keys)
    private val poster = object : OrionNotificationPoster {
        override fun permissionGranted() = permission
        override fun availability(): NotificationAvailability {
            duringAvailability?.also { duringAvailability = null }?.invoke()
            return if (permission) NotificationAvailability.AVAILABLE else NotificationAvailability.PERMISSION_DENIED
        }
        override fun post(actionId: UUID, notification: ValidatedPostNotification,
            permit: OrionNotificationInvocationPermit): NotificationPostResult {
            assertFalse(db.inTransaction()); assertFalse(Thread.holdsLock(barrier))
            beforePermit?.invoke()
            if (!permit.allows()) return NotificationPostResult.NOT_INVOKED
            if (result != NotificationPostResult.NOT_INVOKED) invocations++
            afterInvocation?.invoke()
            assertEquals("O.R.I.O.N.", notification.title)
            assertEquals("Você tem um lembrete.", notification.body)
            return result
        }
    }
    @Before fun open() {
        db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
        barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.DEGRADED_SAFE)
        pipeline = OrionReminderDeliveryPipeline(db, encryption, clock, barrier, { CoreReadiness.DEGRADED_SAFE },
            { true }, OrionProcessRole.MAIN, { present }, poster)
        channel = pipeline.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
        gateway = pipeline.gateway(channel)
    }
    @After fun close() { channel.close(); db.close(); context.deleteDatabase(name) }
    private fun create(): UUID {
        val id = UUID.randomUUID()
        OrionReminderStore(db, encryption, clock).create(id, byteArrayOf(1), Sensitivity.SENSITIVE,
            wall.plusSeconds(60), ZoneOffset.UTC, UUID.randomUUID())
        return id
    }
    private suspend fun enable(id: UUID): ReminderDeliveryPreview {
        val prepared = gateway.prepare(id, checkNotNull(db.reminders().find(id.toString())).version)
            as ReminderDeliveryPreparation.Ready
        assertEquals(ReminderDeliveryOutcome.ENABLED, gateway.confirm(prepared.preview))
        return prepared.preview
    }

    @Test fun oldReminderCreationAloneNeverAuthorizesNotification() {
        val id = create()
        wall = wall.plusSeconds(61)
        assertEquals(ReminderDeliveryOutcome.REJECTED, pipeline.executeDue(id, 0))
        assertEquals(ReminderStatus.PENDING, db.reminders().find(id.toString())?.status)
        assertEquals(0, invocations)
    }

    @Test fun explicitConfirmedOneShotFiresOnceWithoutUserPresenceAndCommitsFactAtomically() = runBlocking {
        val id = create()
        val preview = enable(id)
        assertFalse(pipeline.dueAllowed(preview.actionId))
        assertEquals(ReminderDeliveryOutcome.REJECTED, pipeline.executeDue(id, preview.generation))
        wall = preview.scheduledAt
        present = false
        channel.onResumed(false); channel.onWindowFocus(false)
        assertTrue(pipeline.dueAllowed(preview.actionId))
        assertEquals(ReminderDeliveryOutcome.POSTED, pipeline.executeDue(id, preview.generation))
        val row = checkNotNull(db.reminders().find(id.toString()))
        assertEquals(ReminderStatus.FIRED, row.status)
        assertEquals(wall.toEpochMilli(), row.lastFiredAt)
        assertEquals(ActionStatus.SUCCEEDED, db.actions().find(preview.actionId.toString())?.status)
        assertEquals(1, db.reminders().historicalEvents(id.toString(), row.version, "REMINDER_FIRED").size)
        assertEquals(ReminderDeliveryOutcome.POSTED, pipeline.executeDue(id, preview.generation))
        assertEquals(1, invocations)
    }

    @Test fun committedDeliveryRemainsVisibleAfterActualActionRetentionAndDatabaseReopen() = runBlocking {
        val id = create(); val preview = enable(id); wall = preview.scheduledAt
        assertEquals(ReminderDeliveryOutcome.POSTED, pipeline.executeDue(id, preview.generation))
        val before = checkNotNull(db.reminders().find(id.toString()))
        wall = wall.plus(java.time.Duration.ofDays(91))
        val pruned = network.orion.data.repository.OrionRetentionMaintenance(db.retention(), clock).runBatch()
        assertEquals(1, pruned.actions)
        assertNull(db.actions().find(preview.actionId.toString()))
        fun assertHistory() {
            val summary = checkNotNull(network.orion.data.reminders.OrionReminderQuery(db, encryption, clock).find(id))
            assertEquals(network.orion.core.queries.reminders.ReminderAlertState.DELIVERED, summary.alertState)
            assertEquals(network.orion.core.queries.reminders.ReminderState.FIRED, summary.state)
            val retained = checkNotNull(db.reminders().find(id.toString()))
            assertEquals(before.version, retained.version)
            assertEquals(before.lastFiredAt, retained.lastFiredAt)
            assertArrayEquals(before.contentEnvelope, retained.contentEnvelope)
            assertEquals(1, invocations)
        }
        assertHistory()
        db.close()
        db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
        assertHistory()
    }

    @Test fun retentionKeepsUncertainDeliveryAndNeverCreatesSuccessfulHistoryOrReplay() = runBlocking {
        val id = create(); val preview = enable(id); wall = preview.scheduledAt
        result = NotificationPostResult.UNKNOWN
        assertEquals(ReminderDeliveryOutcome.UNKNOWN, pipeline.executeDue(id, preview.generation))
        wall = wall.plus(java.time.Duration.ofDays(91))
        assertEquals(0, network.orion.data.repository.OrionRetentionMaintenance(db.retention(), clock).runBatch().actions)
        assertEquals(ActionStatus.UNKNOWN, db.actions().find(preview.actionId.toString())?.status)
        assertEquals(network.orion.core.queries.reminders.ReminderAlertState.UNCERTAIN,
            network.orion.data.reminders.OrionReminderQuery(db, encryption, clock).find(id)?.alertState)
        assertEquals(ReminderDeliveryOutcome.UNKNOWN, pipeline.executeDue(id, preview.generation))
        assertEquals(1, invocations)
    }

    @Test fun revokedPermissionAndStartupBarrierNeverMarkReminderDelivered() = runBlocking {
        val id = create(); val preview = enable(id); wall = preview.scheduledAt
        permission = false
        assertEquals(ReminderDeliveryOutcome.UNAVAILABLE, pipeline.executeDue(id, preview.generation))
        assertEquals(ReminderStatus.PENDING, db.reminders().find(id.toString())?.status)
        assertEquals(ActionStatus.CONFIRMED, db.actions().find(preview.actionId.toString())?.status)
        permission = true; barrier.beginRecovery()
        assertEquals(ReminderDeliveryOutcome.UNAVAILABLE, pipeline.executeDue(id, preview.generation))
        assertEquals(0, invocations)
    }

    @Test fun uncertainInvocationNeverRepeatsAndDoesNotBecomeFired() = runBlocking {
        val id = create(); val preview = enable(id); wall = preview.scheduledAt
        result = NotificationPostResult.UNKNOWN
        assertEquals(ReminderDeliveryOutcome.UNKNOWN, pipeline.executeDue(id, preview.generation))
        assertEquals(ActionStatus.UNKNOWN, db.actions().find(preview.actionId.toString())?.status)
        assertEquals(ReminderStatus.PENDING, db.reminders().find(id.toString())?.status)
        assertEquals(ReminderDeliveryOutcome.UNKNOWN, pipeline.executeDue(id, preview.generation))
        assertEquals(1, invocations)
    }

    @Test fun adapterProvenNotInvokedFailsWithoutFiringOrAutomaticRetry() = runBlocking {
        val id = create(); val preview = enable(id); wall = preview.scheduledAt
        result = NotificationPostResult.NOT_INVOKED
        assertEquals(ReminderDeliveryOutcome.UNAVAILABLE, pipeline.executeDue(id, preview.generation))
        assertEquals(ActionStatus.FAILED, db.actions().find(preview.actionId.toString())?.status)
        assertEquals(ReminderStatus.PENDING, db.reminders().find(id.toString())?.status)
        assertEquals(ReminderDeliveryOutcome.REJECTED, pipeline.executeDue(id, preview.generation))
        assertEquals(1, db.actions().find(preview.actionId.toString())?.attemptCount)
        assertEquals(0, invocations)
    }

    @Test fun unexpectedAdapterOwnershipResultIsUncertainAndNeverResent() = runBlocking {
        val id = create(); val preview = enable(id); wall = preview.scheduledAt
        result = NotificationPostResult.NOT_OWNED
        assertEquals(ReminderDeliveryOutcome.UNKNOWN, pipeline.executeDue(id, preview.generation))
        assertEquals(ActionStatus.UNKNOWN, db.actions().find(preview.actionId.toString())?.status)
        assertEquals(ReminderStatus.PENDING, db.reminders().find(id.toString())?.status)
        assertEquals(ReminderDeliveryOutcome.UNKNOWN, pipeline.executeDue(id, preview.generation))
        assertEquals(1, invocations)
    }

    @Test fun alteredPreviewAndExpiredConsentCannotExecute() = runBlocking {
        val id = create()
        val prepared = (gateway.prepare(id, 0) as ReminderDeliveryPreparation.Ready).preview
        assertEquals(ReminderDeliveryOutcome.REJECTED, gateway.confirm(prepared.copy(generation = prepared.generation + 1)))
        assertEquals(ReminderDeliveryOutcome.ENABLED, gateway.confirm(prepared))
        wall = OrionReminderDeliveryPolicy.expiresAt(prepared.scheduledAt)
        assertEquals(ReminderDeliveryOutcome.REJECTED, pipeline.executeDue(id, prepared.generation))
        assertEquals(ReminderStatus.PENDING, db.reminders().find(id.toString())?.status)
        assertEquals(0, invocations)
    }

    @Test fun structuralFactAcknowledgementSurvivesClockAndPermissionChangesWithoutGrantingInvocation() = runBlocking {
        val id = create(); val preview = enable(id)
        wall = wall.minusSeconds(120)
        permission = false
        assertTrue(pipeline.dueReferenceAllowed(preview.actionId))
        assertFalse(pipeline.dueAllowed(preview.actionId))
        assertEquals(ReminderDeliveryOutcome.REJECTED, pipeline.executeDue(id, preview.generation))
        wall = OrionReminderDeliveryPolicy.expiresAt(preview.scheduledAt)
        assertTrue(pipeline.dueReferenceAllowed(preview.actionId))
        assertFalse(pipeline.dueAllowed(preview.actionId))
        assertEquals(0, invocations)
        db.openHelper.writableDatabase.execSQL("UPDATE orion_action_requests SET confirmedPayloadHash = ? WHERE actionId = ?",
            arrayOf("0".repeat(64), preview.actionId.toString()))
        assertFalse(pipeline.dueReferenceAllowed(preview.actionId))
    }

    @Test fun changedGenerationInsideSlowPlatformPreparationInvalidatesPermit() = runBlocking {
        val id = create(); val preview = enable(id); wall = preview.scheduledAt
        beforePermit = {
            val row = checkNotNull(db.reminders().find(id.toString()))
            db.openHelper.writableDatabase.execSQL("UPDATE orion_reminders SET fireGeneration = fireGeneration + 1 WHERE reminderId = ?",
                arrayOf(row.reminderId))
        }
        assertEquals(ReminderDeliveryOutcome.UNAVAILABLE, pipeline.executeDue(id, preview.generation))
        assertEquals(0, invocations)
        assertEquals(ReminderStatus.PENDING, db.reminders().find(id.toString())?.status)
    }

    @Test fun generationRevokedDuringCapabilitySamplingCannotReachNotificationInvocation() = runBlocking {
        val id = create(); val preview = enable(id); wall = preview.scheduledAt
        beforePermit = {
            // The adapter has begun its final permit. Initial policy sees the old
            // binding; its following capability read races a committed revocation.
            duringAvailability = {
                db.openHelper.writableDatabase.execSQL("UPDATE orion_reminders SET fireGeneration=fireGeneration+1 WHERE reminderId=?",
                    arrayOf(id.toString()))
            }
        }
        assertEquals(ReminderDeliveryOutcome.UNAVAILABLE, pipeline.executeDue(id, preview.generation))
        assertEquals(0, invocations)
        assertEquals(ActionStatus.FAILED, db.actions().find(preview.actionId.toString())?.status)
        assertEquals(ReminderStatus.PENDING, db.reminders().find(id.toString())?.status)
    }

    @Test fun eventInsertFailureAfterPlatformProofRollsBackSuccessAndBecomesUnknown() = runBlocking {
        val id = create(); val preview = enable(id); wall = preview.scheduledAt
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER orion_test_reject_fired BEFORE INSERT ON orion_domain_event_outbox
            WHEN NEW.eventType = 'REMINDER_FIRED' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END""")
        assertEquals(ReminderDeliveryOutcome.UNKNOWN, pipeline.executeDue(id, preview.generation))
        assertEquals(ReminderStatus.PENDING, db.reminders().find(id.toString())?.status)
        assertEquals(ActionStatus.UNKNOWN, db.actions().find(preview.actionId.toString())?.status)
        assertEquals(1, invocations)
        assertEquals(ReminderDeliveryOutcome.UNKNOWN, pipeline.executeDue(id, preview.generation))
        assertEquals(1, invocations)
    }

    private fun dailyRule(id: UUID) = OrionReminderRecurrenceRule(OrionReminderRecurrenceFrequency.DAILY, 1, 3,
        Instant.ofEpochMilli(checkNotNull(db.reminders().find(id.toString())).scheduledAt).atOffset(ZoneOffset.UTC).toLocalDateTime(),
        ZoneOffset.UTC)

    @Test fun finiteBatchRecordsIndependentOriginalReceiptsAndAdvancesOnlyAfterEachProvenDelivery() = runBlocking {
        val id = create()
        val preview = (gateway.prepareSeries(id, 0, dailyRule(id)) as ReminderSeriesPreparation.Ready).preview
        assertEquals(ReminderDeliveryOutcome.ENABLED, gateway.confirmSeries(preview))
        val originalTimes = preview.occurrences.map {
            val stored = checkNotNull(db.actions().find(it.delivery.actionId.toString()))
            assertEquals(ActionStatus.CONFIRMED, stored.status)
            assertEquals(it.delivery.confirmationFingerprint, stored.confirmationFingerprint)
            checkNotNull(stored.confirmedAt)
        }
        assertEquals(1, originalTimes.distinct().size)
        channel.onResumed(false); channel.onWindowFocus(false); present = false
        preview.occurrences.forEachIndexed { index, occurrence ->
            wall = occurrence.delivery.scheduledAt
            assertEquals(ReminderDeliveryOutcome.POSTED, pipeline.executeDue(id, occurrence.delivery.generation))
            val row = checkNotNull(db.reminders().find(id.toString()))
            assertEquals(if (index == preview.occurrences.lastIndex) ReminderStatus.FIRED else ReminderStatus.PENDING, row.status)
            assertEquals(ActionStatus.SUCCEEDED, db.actions().find(occurrence.delivery.actionId.toString())?.status)
            if (index < preview.occurrences.lastIndex)
                assertEquals(preview.occurrences[index + 1].delivery.actionId.toString(), row.deliveryActionId)
        }
        assertEquals(3, invocations)
        assertEquals(ReminderSeriesState.COMPLETED, db.reminderSeries().series(id.toString())?.state)
    }

    @Test fun unconfirmedFiniteManifestNeverEnablesAnyOccurrence() = runBlocking {
        val id = create()
        val preview = (gateway.prepareSeries(id, 0, dailyRule(id)) as ReminderSeriesPreparation.Ready).preview
        wall = preview.occurrences.last().delivery.scheduledAt
        preview.occurrences.forEach {
            assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(it.delivery.actionId.toString())?.status)
            assertEquals(ReminderDeliveryOutcome.REJECTED, pipeline.executeDue(id, it.delivery.generation))
        }
        assertNull(db.reminderSeries().series(id.toString()))
        assertNull(db.reminders().find(id.toString())?.deliveryActionId)
        assertEquals(0, invocations)
    }

    @Test fun changingOneDisclosedOccurrenceInvalidatesTheEntireBatchConfirmation() = runBlocking {
        val id = create()
        val preview = (gateway.prepareSeries(id, 0, dailyRule(id)) as ReminderSeriesPreparation.Ready).preview
        val changed = preview.occurrences.toMutableList()
        changed[1] = changed[1].copy(delivery = changed[1].delivery.copy(scheduledAt = changed[1].delivery.scheduledAt.plusSeconds(1)))
        assertEquals(ReminderDeliveryOutcome.REJECTED, gateway.confirmSeries(preview.copy(occurrences = changed)))
        assertNull(db.reminderSeries().series(id.toString()))
        assertEquals(ReminderDeliveryOutcome.ENABLED, gateway.confirmSeries(preview))
    }

    @Test fun unknownOccurrencePausesSeriesAndCancellationPreservesUnknownWhileRevokingFutureChildren() = runBlocking {
        val id = create()
        val preview = (gateway.prepareSeries(id, 0, dailyRule(id)) as ReminderSeriesPreparation.Ready).preview
        assertEquals(ReminderDeliveryOutcome.ENABLED, gateway.confirmSeries(preview))
        wall = preview.occurrences.first().delivery.scheduledAt; result = NotificationPostResult.UNKNOWN
        assertEquals(ReminderDeliveryOutcome.UNKNOWN, pipeline.executeDue(id, preview.occurrences.first().delivery.generation))
        wall = preview.occurrences.last().delivery.scheduledAt
        assertEquals(ReminderDeliveryOutcome.REJECTED, pipeline.executeDue(id, preview.occurrences.last().delivery.generation))
        val row = checkNotNull(db.reminders().find(id.toString()))
        assertEquals(ActionMutation.APPLIED, OrionReminderStore(db, encryption, clock).cancel(id, row.version, UUID.randomUUID()))
        assertEquals(ActionStatus.UNKNOWN, db.actions().find(preview.occurrences.first().delivery.actionId.toString())?.status)
        preview.occurrences.drop(1).forEach { assertEquals(ActionStatus.CANCELLED, db.actions().find(it.delivery.actionId.toString())?.status) }
        assertEquals(1, invocations)
    }

    @Test fun interruptedBatchConfirmationRollsBackAllChildrenThenReplaysOriginalReceiptsAtomically() = runBlocking {
        val id = create()
        val preview = (gateway.prepareSeries(id, 0, dailyRule(id)) as ReminderSeriesPreparation.Ready).preview
        val second = preview.occurrences[1].delivery.actionId.toString()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER orion_test_reject_second_confirmation BEFORE UPDATE ON orion_action_requests
            WHEN NEW.actionId = '$second' AND NEW.status = 'CONFIRMED' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END""")
        try {
            gateway.confirmSeries(preview)
            fail("Injected second-child failure must abort the confirmation transaction")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertNull(db.reminderSeries().series(id.toString()))
        assertNull(db.reminders().find(id.toString())?.deliveryActionId)
        preview.occurrences.forEach { assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(it.delivery.actionId.toString())?.status) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER orion_test_reject_second_confirmation")
        assertTrue(pipeline.drainUi(100) > 0)
        assertNotNull(db.reminderSeries().series(id.toString()))
        preview.occurrences.forEach { assertEquals(ActionStatus.CONFIRMED, db.actions().find(it.delivery.actionId.toString())?.status) }
        assertEquals(0, invocations)
    }

    @Test fun seriesRevokedDuringPlatformPreparationCannotInvokeAlreadyClaimedChild() = runBlocking {
        val id = create()
        val preview = (gateway.prepareSeries(id, 0, dailyRule(id)) as ReminderSeriesPreparation.Ready).preview
        assertEquals(ReminderDeliveryOutcome.ENABLED, gateway.confirmSeries(preview))
        val first = preview.occurrences.first().delivery; wall = first.scheduledAt
        beforePermit = { assertEquals(ActionMutation.APPLIED, db.reminderSeries().cancelSeries(id.toString(), wall.toEpochMilli())) }
        assertEquals(ReminderDeliveryOutcome.UNAVAILABLE, pipeline.executeDue(id, first.generation))
        assertEquals(0, invocations)
        assertEquals(ReminderStatus.PENDING, db.reminders().find(id.toString())?.status)
    }

    @Test fun clockJumpAfterProvenInvocationRevokesExpiredFutureChildrenWithoutLosingCurrentSuccess() = runBlocking {
        val id = create()
        val preview = (gateway.prepareSeries(id, 0, dailyRule(id)) as ReminderSeriesPreparation.Ready).preview
        assertEquals(ReminderDeliveryOutcome.ENABLED, gateway.confirmSeries(preview))
        val first = preview.occurrences.first().delivery; wall = first.scheduledAt
        afterInvocation = { wall = OrionReminderDeliveryPolicy.expiresAt(preview.occurrences.last().delivery.scheduledAt) }
        assertEquals(ReminderDeliveryOutcome.POSTED, pipeline.executeDue(id, first.generation))
        assertEquals(ActionStatus.SUCCEEDED, db.actions().find(first.actionId.toString())?.status)
        assertEquals(ReminderStatus.FIRED, db.reminders().find(id.toString())?.status)
        assertEquals(ReminderSeriesState.CANCELLED, db.reminderSeries().series(id.toString())?.state)
        preview.occurrences.drop(1).forEach { assertEquals(ActionStatus.CANCELLED, db.actions().find(it.delivery.actionId.toString())?.status) }
        assertEquals(1, invocations)
    }

    @Test fun replacingDailyWithWeeklyThenDailyCancelsOnlyOldDraftsAndStillAllowsFreshExactApproval() = runBlocking {
        val id = create(); val rule = dailyRule(id)
        val first = (gateway.prepareSeries(id, 0, rule) as ReminderSeriesPreparation.Ready).preview
        val weekly = (gateway.prepareSeries(id, 0, rule.copy(frequency = OrionReminderRecurrenceFrequency.WEEKLY))
            as ReminderSeriesPreparation.Ready).preview
        first.occurrences.forEach { assertEquals(ActionStatus.CANCELLED, db.actions().find(it.delivery.actionId.toString())?.status) }
        val again = (gateway.prepareSeries(id, 0, rule) as ReminderSeriesPreparation.Ready).preview
        assertNotEquals(first.seriesId, again.seriesId)
        weekly.occurrences.forEach { assertEquals(ActionStatus.CANCELLED, db.actions().find(it.delivery.actionId.toString())?.status) }
        assertEquals(ReminderDeliveryOutcome.REJECTED, gateway.confirmSeries(first))
        assertEquals(ReminderDeliveryOutcome.ENABLED, gateway.confirmSeries(again))
        db.openHelper.readableDatabase.query("SELECT count(*) FROM orion_action_requests WHERE skill='reminder.deliver' AND status IN ('PENDING','NEEDS_CONFIRMATION')").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        assertEquals(0, invocations)
    }

    @Test fun boundedExpiryCancelsAbandonedDraftsAndOverdueConfirmedActionsButPreservesUnknownAndFutureConsent() = runBlocking {
        val oldDraft = create()
        val draft = (gateway.prepare(oldDraft, 0) as ReminderDeliveryPreparation.Ready).preview
        val expiredId = create(); val expired = enable(expiredId)
        val uncertainId = create(); val uncertain = enable(uncertainId)
        val futureId = create()
        // A separate explicitly consented future alert remains outside the expiry selection.
        val futureRow = checkNotNull(db.reminders().find(futureId.toString()))
        db.openHelper.writableDatabase.execSQL("UPDATE orion_reminders SET scheduledAt=? WHERE reminderId=?",
            arrayOf<Any>(wall.plusSeconds(3 * 86400).toEpochMilli(), futureRow.reminderId))
        val future = enable(futureId)
        wall = uncertain.scheduledAt; result = NotificationPostResult.UNKNOWN
        assertEquals(ReminderDeliveryOutcome.UNKNOWN, pipeline.executeDue(uncertainId, uncertain.generation))
        val unknownBefore = checkNotNull(db.actions().find(uncertain.actionId.toString()))
        val unknownAudit = db.actions().auditFor(unknownBefore.actionId)
        wall = OrionReminderDeliveryPolicy.expiresAt(expired.scheduledAt)
        val cleanup = OrionReminderDeliveryRetention(db, pipeline.actions, clock)
        assertEquals(1, cleanup.expireBatch(1))
        cleanup.expireBatch(100)
        assertEquals(ActionStatus.CANCELLED, db.actions().find(draft.actionId.toString())?.status)
        assertEquals(ActionStatus.CANCELLED, db.actions().find(expired.actionId.toString())?.status)
        assertEquals(ActionStatus.CONFIRMED, db.actions().find(future.actionId.toString())?.status)
        val after = checkNotNull(db.actions().find(uncertain.actionId.toString()))
        assertEquals(unknownBefore.version, after.version)
        assertArrayEquals(unknownBefore.argumentsEnvelope, after.argumentsEnvelope)
        assertEquals(unknownAudit, db.actions().auditFor(unknownBefore.actionId))
        assertEquals(1, invocations)
    }

    @Test fun identicalPersistedProposalReplayKeepsNonceAndDeduplicatesWithoutNewChildren() = runBlocking {
        val id = create(); val rule = dailyRule(id)
        val preview = (gateway.prepareSeries(id, 0, rule) as ReminderSeriesPreparation.Ready).preview
        val initial = OrionReminderSeriesPlan.prepare(id, 0, 0, preview.occurrences.first().delivery.scheduledAt,
            rule, clock.wallNow(), preview.seriesId)
        val bytes = OrionReminderSeriesWire(false, initial).encode()
        val replay = network.orion.data.repository.OrionDurableIngressStore(db, encryption, clock, barrier,
            network.orion.data.repository.LocalIngressConsumer { error("Enqueue replay must not consume") },
            consumerScope = OrionReminderSeriesWire.SCOPE)
        val outcome = try {
            replay.enqueue(network.orion.core.ingress.DurableIngressRequest(UUID.randomUUID(), OrionReminderSeriesWire.SCOPE,
                preview.seriesId, OrionReminderSeriesWire.SOURCE, OrionReminderSeriesWire.TYPE, 1, Sensitivity.SENSITIVE,
                false, null, bytes, java.time.Duration.ofDays(1).toMillis(), java.time.Duration.ofDays(455).toMillis(),
                correlationId = preview.seriesId))
        } finally { bytes.fill(0) }
        assertEquals(network.orion.core.ingress.DurableIngressOutcome.DEDUP, outcome)
        preview.occurrences.forEach { assertEquals(ActionStatus.NEEDS_CONFIRMATION, db.actions().find(it.delivery.actionId.toString())?.status) }
        db.openHelper.readableDatabase.query("SELECT count(*) FROM orion_action_requests WHERE skill='reminder.deliver'").use {
            assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0))
        }
    }

    @Test fun thousandDraftQuotaRejectsNewProposalWithoutGrantingAuthorityOrAffectingOtherDrafts() = runBlocking {
        val id = create()
        val policy = IdempotencyKeyPolicy("MAIN_UI", "reminder.deliver", java.time.Duration.ofDays(90), 1,
            network.orion.data.actions.OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)
        db.runInTransaction {
            repeat(OrionReminderDeliveryRetention.MAX_DRAFTS) {
                val other = UUID.randomUUID()
                val action = UUID.randomUUID()
                val preview = ReminderDeliveryPreview(other, 0, 1, wall.plusSeconds(60), action, 0, "",
                    network.orion.core.skills.metadata.reminders.OrionDeliverReminderDescriptor.POLICY_VERSION)
                assertEquals(network.orion.data.actions.ActionStoreOutcome.REGISTERED,
                    pipeline.actions.register(OrionReminderDeliveryPolicy.normalized(preview), policy, action.toString(),
                        other, Sensitivity.SENSITIVE, true).outcome)
            }
        }
        val prepared = gateway.prepare(id, 0) as ReminderDeliveryPreparation.Blocked
        assertEquals(ReminderDeliveryOutcome.REJECTED, prepared.outcome)
        db.openHelper.readableDatabase.query("SELECT count(*) FROM orion_action_requests WHERE skill='reminder.deliver' AND status='PENDING'").use {
            assertTrue(it.moveToFirst()); assertEquals(1000, it.getInt(0))
        }
        assertNull(db.reminders().find(id.toString())?.deliveryActionId)
        assertEquals(0, invocations)
    }
}
