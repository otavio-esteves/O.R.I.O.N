package network.orion.app.reminders

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDateTime
import java.time.Instant
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import network.orion.core.actions.ActionStatus
import network.orion.core.model.*
import network.orion.data.database.*
import network.orion.app.reminderdelivery.OrionReminderDeliveryRetention
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.actions.OrionActionStore
import network.orion.data.events.OrionEventDispatcher
import network.orion.data.events.OrionTechnicalEventConsumer
import network.orion.data.repository.OrionRetentionMaintenance
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** SQLite metadata fixtures only; synthetic action snapshots never authorize Android execution. */
@RunWith(AndroidJUnit4::class)
class OrionReminderSeriesPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_series_${UUID.randomUUID()}.db"
    private lateinit var db: OrionDatabase
    private val seriesDao get() = db.reminderSeries()
    @Before fun open() { db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build() }
    @After fun close() { db.close(); context.deleteDatabase(name) }

    @Test fun finiteManifestAttachesOnlyToItsFirstIndividuallyConfirmedExactBinding() {
        val fixture = fixture(attach = false)
        assertEquals(ActionMutation.APPLIED, seriesDao.attachSeries(fixture.series, fixture.children))
        val row = checkNotNull(db.reminders().find(fixture.id))
        assertEquals(2L, row.version)
        assertEquals(fixture.series.ruleCanonical, row.recurrenceRule)
        assertEquals(fixture.children.first().requestedLocalDateTime, row.localDateTime)
        assertEquals(3, seriesDao.occurrences(fixture.id).size)
        assertEquals(ReminderSeriesState.ACTIVE, seriesDao.series(fixture.id)?.state)
        assertEquals(ActionMutation.CONFLICT, seriesDao.attachSeries(fixture.series, fixture.children))
        assertEquals(3, db.events().count())
    }

    @Test fun draftUnknownAndUnboundChildrenCannotAttachOrPartiallyPersistManifest() {
        for (status in listOf(ActionStatus.NEEDS_CONFIRMATION, ActionStatus.UNKNOWN, ActionStatus.CANCELLED)) {
            val fixture = fixture(attach = false)
            setStatus(fixture.children.last().actionId, status)
            assertEquals(ActionMutation.CONFLICT, seriesDao.attachSeries(fixture.series, fixture.children))
            assertNull(seriesDao.series(fixture.id))
            assertTrue(seriesDao.occurrences(fixture.id).isEmpty())
            assertNull(db.reminders().find(fixture.id)?.recurrenceRule)
        }
        val fixture = fixture(attach = false)
        val changed = fixture.children.mapIndexed { index, child -> if (index == 0) child.copy(generation = 2) else child.copy(generation = child.generation + 1) }
        assertEquals(ActionMutation.CONFLICT, seriesDao.attachSeries(fixture.series, changed))
        assertNull(seriesDao.series(fixture.id))
    }

    @Test fun successfulOccurrenceAdvancesExactlyOnePreconfirmedChildAndCompletesFiniteSeries() {
        val fixture = fixture()
        for (index in fixture.children.indices) {
            val child = fixture.children[index]
            val before = checkNotNull(db.reminders().find(fixture.id))
            assertEquals(child.actionId, before.deliveryActionId)
            assertEquals(child.generation, before.fireGeneration)
            db.runInTransaction {
                setStatus(child.actionId, ActionStatus.SUCCEEDED)
                assertEquals(ActionMutation.APPLIED, db.reminderSchedules().completeDeliveryWithEvent(fixture.id,
                    before.version, child.generation, child.actionId, child.scheduledAt,
                    event(before, before.version + 1, "REMINDER_FIRED", child.scheduledAt)))
                assertEquals(ActionMutation.APPLIED, seriesDao.advanceAfterDelivery(fixture.id, before.version + 1,
                    child.generation, child.actionId, child.scheduledAt))
            }
            val after = checkNotNull(db.reminders().find(fixture.id))
            if (index < fixture.children.lastIndex) {
                val next = fixture.children[index + 1]
                assertEquals(ReminderStatus.PENDING, after.status)
                assertEquals(next.actionId, after.deliveryActionId)
                assertEquals(next.generation, after.fireGeneration)
                assertEquals(next.scheduledAt, after.scheduledAt)
                assertEquals(ReminderPrecision.UNSCHEDULED, after.precision)
                assertEquals(index + 1, seriesDao.series(fixture.id)?.currentIndex)
            } else {
                assertEquals(ReminderStatus.FIRED, after.status)
                assertEquals(ReminderSeriesState.COMPLETED, seriesDao.series(fixture.id)?.state)
            }
        }
        assertEquals(3, fixture.children.count { db.actions().find(it.actionId)?.status == ActionStatus.SUCCEEDED })
    }

    @Test fun unknownExecutionCannotAdvanceAndCancellationPreservesUncertaintyButRevokesFutureChildren() {
        val fixture = fixture()
        setStatus(fixture.children[0].actionId, ActionStatus.UNKNOWN)
        setStatus(fixture.children[1].actionId, ActionStatus.RUNNING)
        val before = checkNotNull(db.reminders().find(fixture.id))
        assertEquals(ActionMutation.CONFLICT, seriesDao.advanceAfterDelivery(fixture.id, before.version,
            before.fireGeneration, checkNotNull(before.deliveryActionId), before.scheduledAt))
        assertEquals(ActionMutation.APPLIED, db.records().cancelReminderWithEvent(fixture.id, before.version,
            fixture.at, event(before, before.version + 1, "REMINDER_CANCELLED", fixture.at)))
        assertEquals(ReminderSeriesState.CANCELLED, seriesDao.series(fixture.id)?.state)
        assertEquals(ActionStatus.UNKNOWN, db.actions().find(fixture.children[0].actionId)?.status)
        assertEquals(ActionStatus.RUNNING, db.actions().find(fixture.children[1].actionId)?.status)
        val last = fixture.children[2]
        assertEquals(ActionStatus.CANCELLED, db.actions().find(last.actionId)?.status)
        assertEquals(ActionStatus.CANCELLED, db.actions().auditFor(last.actionId).single().toStatus)
        assertEquals(0, db.actions().auditFor(fixture.children[0].actionId).size)
        assertEquals(0, db.actions().auditFor(fixture.children[1].actionId).size)
    }

    @Test fun everyMaterialMutationCancelsAllWaitingChildrenWithAtomicVersionedAudit() {
        for (operation in listOf("cancel", "snooze", "edit")) {
            val fixture = fixture()
            val before = checkNotNull(db.reminders().find(fixture.id))
            val result = when (operation) {
                "cancel" -> db.records().cancelReminderWithEvent(fixture.id, before.version, fixture.at,
                    event(before, before.version + 1, "REMINDER_CANCELLED", fixture.at))
                "snooze" -> db.reminders().snoozeWithEvent(fixture.id, before.version, before.scheduledAt + 60_000,
                    "UTC", fixture.at, event(before, before.version + 1, "REMINDER_SNOOZED", fixture.at))
                else -> db.records().updateReminderWithEvent(fixture.id, before.version, byteArrayOf(2),
                    Sensitivity.SENSITIVE, before.scheduledAt + 60_000, "UTC", fixture.at,
                    event(before, before.version + 1, "REMINDER_UPDATED", fixture.at))
            }
            assertEquals(ActionMutation.APPLIED, result)
            assertEquals(ReminderSeriesState.CANCELLED, seriesDao.series(fixture.id)?.state)
            assertNull(db.reminders().find(fixture.id)?.deliveryActionId)
            fixture.children.forEach { child ->
                val action = checkNotNull(db.actions().find(child.actionId))
                assertEquals(ActionStatus.CANCELLED, action.status)
                assertEquals(3L, action.version)
                val audit = db.actions().auditFor(child.actionId).single()
                assertEquals(ActionStatus.CONFIRMED, audit.fromStatus)
                assertEquals(ActionStatus.CANCELLED, audit.toStatus)
                assertEquals(3L, audit.actionVersion)
            }
        }
    }

    @Test fun explicitSeriesPauseRevokesRunningAnchorWithoutReclassifyingUncertainExecution() {
        val fixture = fixture()
        setStatus(fixture.children.first().actionId, ActionStatus.RUNNING)
        assertEquals(ActionMutation.APPLIED, seriesDao.cancelSeries(fixture.id, fixture.at))
        val row = checkNotNull(db.reminders().find(fixture.id))
        assertNull(row.deliveryActionId)
        assertEquals(2L, row.fireGeneration)
        assertEquals(ReminderStatus.PENDING, row.status)
        assertEquals(ReminderSeriesState.CANCELLED, seriesDao.series(fixture.id)?.state)
        assertEquals(ActionStatus.RUNNING, db.actions().find(fixture.children.first().actionId)?.status)
        fixture.children.drop(1).forEach { child ->
            assertEquals(ActionStatus.CANCELLED, db.actions().find(child.actionId)?.status)
        }
    }

    @Test fun domainEventFailureRollsBackReminderSeriesChildCancellationAndAuditTogether() {
        val fixture = fixture()
        val before = checkNotNull(db.reminders().find(fixture.id))
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER orion_reject_series_cancel BEFORE INSERT ON orion_domain_event_outbox
            WHEN NEW.eventType = 'REMINDER_CANCELLED' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END""")
        try {
            assertThrows(SQLiteException::class.java) {
                db.records().cancelReminderWithEvent(fixture.id, before.version, fixture.at,
                    event(before, before.version + 1, "REMINDER_CANCELLED", fixture.at))
            }
            val restored = checkNotNull(db.reminders().find(fixture.id))
            assertEquals(before, restored.copy(contentEnvelope = before.contentEnvelope))
            assertEquals(ReminderSeriesState.ACTIVE, seriesDao.series(fixture.id)?.state)
            fixture.children.forEach { child ->
                assertEquals(ActionStatus.CONFIRMED, db.actions().find(child.actionId)?.status)
                assertTrue(db.actions().auditFor(child.actionId).isEmpty())
            }
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER orion_reject_series_cancel") }
    }

    @Test fun manifestEventFailureLeavesNeitherManifestRowsNorPartialReminderMetadata() {
        val fixture = fixture(attach = false)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER orion_reject_series_attach BEFORE INSERT ON orion_domain_event_outbox
            WHEN NEW.eventType = 'REMINDER_SERIES_ATTACHED' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END""")
        try {
            assertThrows(SQLiteException::class.java) { seriesDao.attachSeries(fixture.series, fixture.children) }
            assertNull(seriesDao.series(fixture.id))
            assertTrue(seriesDao.occurrences(fixture.id).isEmpty())
            assertNull(db.reminders().find(fixture.id)?.recurrenceRule)
            assertEquals(1L, db.reminders().find(fixture.id)?.version)
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER orion_reject_series_attach") }
    }

    @Test fun advancementEventFailureRollsBackCurrentSuccessFiredNextActivationAndSeriesIndex() {
        val fixture = fixture()
        val before = checkNotNull(db.reminders().find(fixture.id))
        val current = fixture.children.first()
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER orion_reject_series_advance BEFORE INSERT ON orion_domain_event_outbox
            WHEN NEW.eventType = 'REMINDER_OCCURRENCE_ADVANCED' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END""")
        try {
            assertThrows(SQLiteException::class.java) {
                db.runInTransaction {
                    setStatus(current.actionId, ActionStatus.SUCCEEDED)
                    db.reminderSchedules().completeDeliveryWithEvent(fixture.id, before.version, current.generation,
                        current.actionId, current.scheduledAt, event(before, before.version + 1, "REMINDER_FIRED", current.scheduledAt))
                    seriesDao.advanceAfterDelivery(fixture.id, before.version + 1, current.generation,
                        current.actionId, current.scheduledAt)
                }
            }
            val restored = checkNotNull(db.reminders().find(fixture.id))
            assertEquals(before, restored.copy(contentEnvelope = before.contentEnvelope))
            assertEquals(0, seriesDao.series(fixture.id)?.currentIndex)
            assertEquals(0L, seriesDao.series(fixture.id)?.version)
            assertEquals(ActionStatus.CONFIRMED, db.actions().find(current.actionId)?.status)
            assertEquals(3, db.events().count())
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER orion_reject_series_advance") }
    }

    @Test fun skippedCivilDayManifestMaySkipDuplicateInstantButRejectsForgedResolution() {
        val rule = OrionReminderRecurrenceRule(OrionReminderRecurrenceFrequency.DAILY, 1, 3,
            LocalDateTime.parse("2011-12-29T00:00"), ZoneId.of("Pacific/Apia"))
        val resolutions = listOf(
            Civil("2011-12-29T00:00", "2011-12-29T00:00", -10 * 3600, "NORMAL"),
            Civil("2011-12-30T00:00", "2011-12-31T00:00", 14 * 3600, "GAP_FIRST_VALID_TIME"),
            Civil("2012-01-01T00:00", "2012-01-01T00:00", 14 * 3600, "NORMAL"),
        )
        val fixture = fixture(rule = rule, civil = resolutions, attach = false)
        assertEquals(ActionMutation.APPLIED, seriesDao.attachSeries(fixture.series, fixture.children))
        assertEquals(resolutions.map { it.requested }, seriesDao.occurrences(fixture.id).map { it.requestedLocalDateTime })
        val forged = fixture(rule = rule, civil = resolutions, attach = false)
        assertThrows(IllegalArgumentException::class.java) {
            seriesDao.attachSeries(forged.series, forged.children.mapIndexed { index, child ->
                if (index == 1) child.copy(resolutionDecision = "NORMAL") else child })
        }
        assertNull(seriesDao.series(forged.id))
    }

    @Test fun safePublicationAndRetentionFreeFullOutboxBeforeExpiredFamilyAddsCancellationFact() {
        val fixture = fixture()
        val now = fixture.children.last().scheduledAt + Duration.ofDays(2).toMillis()
        val old = now - Duration.ofDays(8).toMillis()
        val fill = 10_000 - db.events().count()
        db.openHelper.writableDatabase.execSQL("""WITH RECURSIVE counter(n) AS (
            SELECT 1 UNION ALL SELECT n+1 FROM counter WHERE n < $fill)
            INSERT INTO orion_domain_event_outbox(eventId,aggregateId,aggregateVersion,eventType,schemaVersion,
                payloadSensitivity,occurredAt,correlationId,status,attemptCount,nextAttemptAt,deliveredAt,version)
            SELECT printf('00000000-0000-0000-0000-%012x',n),?,n,'TASK_CREATED',1,
                'SENSITIVE',?,?,'DELIVERED',1,?,?,2 FROM counter""",
            arrayOf<Any>(fixture.id, old, uuid(), old, old))
        assertEquals(10_000, db.events().count())
        val clock = WallClock { Instant.ofEpochMilli(now) }
        val keys = object : FieldKeyProvider {
            override fun currentKey(): FieldKeyResult = FieldKeyResult.Unavailable
            override fun keyForVersion(version: Int): FieldKeyResult = FieldKeyResult.Unavailable
        }
        val expiry = OrionReminderDeliveryRetention(db, OrionActionStore(db, OrionFieldEncryption(keys), clock), clock)
        assertThrows(IllegalStateException::class.java) { expiry.expireBatch() }
        assertEquals(ReminderSeriesState.ACTIVE, seriesDao.series(fixture.id)?.state)
        fixture.children.forEach { assertEquals(ActionStatus.CONFIRMED, db.actions().find(it.actionId)?.status) }
        // These safe stages must precede mutation-producing expiry in MAIN maintenance.
        val dispatcher = OrionEventDispatcher(db.events(), clock, OrionTechnicalEventConsumer.routes(db.events(), clock))
        assertEquals(3, dispatcher.dispatchBatch().delivered)
        assertEquals(50, OrionRetentionMaintenance(db.retention(), clock).runBatch().events)
        assertTrue(expiry.expireBatch() > 0)
        assertEquals(ReminderSeriesState.CANCELLED, seriesDao.series(fixture.id)?.state)
        fixture.children.forEach { assertEquals(ActionStatus.CANCELLED, db.actions().find(it.actionId)?.status) }
        assertTrue(db.events().count() < 10_000)
    }

    private data class Fixture(val id: String, val series: OrionReminderSeriesEntity,
        val children: List<OrionReminderOccurrenceEntity>, val at: Long)
    private data class Civil(val requested: String, val resolved: String, val offset: Int, val decision: String)
    private fun fixture(rule: OrionReminderRecurrenceRule = OrionReminderRecurrenceRule(OrionReminderRecurrenceFrequency.DAILY,
        1, 3, LocalDateTime.parse("2026-11-08T10:00"), ZoneOffset.UTC), civil: List<Civil>? = null,
        attach: Boolean = true): Fixture {
        val id = uuid(); val manifest = uuid()
        val descriptions = civil ?: (0 until rule.count).map { index ->
            val local = rule.startLocalDateTime.plusDays(index.toLong()).toString()
            Civil(local, local, 0, "NORMAL")
        }
        val firstDue = LocalDateTime.parse(descriptions.first().resolved).toInstant(ZoneOffset.ofTotalSeconds(descriptions.first().offset)).toEpochMilli()
        val at = firstDue - 60_000
        val children = descriptions.mapIndexed { index, time ->
            val due = LocalDateTime.parse(time.resolved).toInstant(ZoneOffset.ofTotalSeconds(time.offset)).toEpochMilli()
            val owner = action(due, at)
            OrionReminderOccurrenceEntity(id, index, manifest, owner, index + 1L, due,
                time.requested, time.resolved, time.offset, time.decision)
        }
        db.records().createReminderWithEvent(ReminderEntity(id, byteArrayOf(1), Sensitivity.SENSITIVE, firstDue,
            rule.zoneId.id, at, at), DomainEventOutboxEntity(uuid(), id, 0, "REMINDER_CREATED", 1,
            Sensitivity.SENSITIVE, at, uuid()))
        assertEquals(ActionMutation.APPLIED, db.reminderSchedules().bindDelivery(id, 0, 1, children.first().actionId, at))
        val series = OrionReminderSeriesEntity(id, manifest, OrionReminderRecurrenceCodec.encode(rule), rule.count, createdAt = at)
        if (attach) assertEquals(ActionMutation.APPLIED, seriesDao.attachSeries(series, children))
        return Fixture(id, series, children, at)
    }
    private fun action(due: Long, at: Long): String {
        val values = ContentValues()
        db.openHelper.writableDatabase.query("PRAGMA table_info(orion_action_requests)").use { columns ->
            while (columns.moveToNext()) {
                if (columns.getInt(3) == 0) continue
                when (columns.getString(2)) {
                    "INTEGER" -> values.put(columns.getString(1), 0L)
                    "BLOB" -> values.put(columns.getString(1), byteArrayOf(1))
                    else -> values.put(columns.getString(1), "synthetic")
                }
            }
        }
        val id = uuid(); val hash = "a".repeat(64)
        values.put("actionId", id); values.put("skill", "reminder.deliver"); values.put("status", "CONFIRMED")
        values.put("version", 2); values.put("payloadSensitivity", "SENSITIVE"); values.put("authorizationLevel", "EXTERNAL_ACTION")
        values.put("sideEffectClass", "EXTERNAL_REVERSIBLE"); values.put("recoveryStrategy", "MANUAL_REVIEW")
        values.put("idempotencyMode", "KEYED"); values.put("idempotencyScope", "reminder.deliver")
        values.put("idempotencyKey", id); values.put("correlationId", uuid()); values.put("requiresConfirmation", 1)
        values.put("confirmedAt", at); values.put("payloadLockedAt", at); values.put("confirmationExpiresAt", due + 86_400_000)
        values.put("normalizedPayloadHash", hash); values.put("confirmedPayloadHash", hash)
        values.put("policyVersion", "1"); values.put("policyVersionAtConfirmation", "1")
        db.openHelper.writableDatabase.insert("orion_action_requests", SQLiteDatabase.CONFLICT_ABORT, values)
        return id
    }
    private fun setStatus(id: String, status: ActionStatus) {
        db.openHelper.writableDatabase.execSQL("UPDATE orion_action_requests SET status=? WHERE actionId=?", arrayOf(status.name, id))
    }
    private fun event(row: ReminderEntity, version: Long, type: String, at: Long) = DomainEventOutboxEntity(uuid(),
        row.reminderId, version, type, 1, row.sensitivity, at, uuid())
    private fun uuid() = UUID.randomUUID().toString()
}
