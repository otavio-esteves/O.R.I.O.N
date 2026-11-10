package network.orion.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import network.orion.core.model.Sensitivity
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.database.*
import network.orion.data.events.*
import network.orion.data.tasks.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Android SQLite evidence. DAO fixture blobs are synthetic; encrypted-adapter test exercises real AES-GCM. */
@RunWith(AndroidJUnit4::class)
class TransactionCorePersistenceTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), OrionDatabase::class.java)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "orion_transaction_test_${UUID.randomUUID()}.db"
    private val upgradeName = "orion_transaction_upgrade_${UUID.randomUUID()}.db"
    private lateinit var database: OrionDatabase

    @Before fun open() { database = openDatabase() }
    @After fun close() {
        database.close()
        context.deleteDatabase(name)
        context.deleteDatabase(upgradeName)
    }

    @Test fun migrationTwoToThreePreservesPendingAndUnknownActionsAndValidatesAllTables() {
        val pendingId = uuid()
        val unknownId = uuid()
        migrations.createDatabase(upgradeName, 2).use { old ->
            old.execSQL("INSERT INTO orion_storage_metadata(id, formatVersion) VALUES(1, 1)")
            insertOldAction(old, pendingId, "PENDING")
            insertOldAction(old, unknownId, "UNKNOWN")
        }
        migrations.runMigrationsAndValidate(upgradeName, 3, true, OrionDatabaseMigrations.MIGRATION_2_3).use { migrated ->
            for ((id, status) in listOf(pendingId to "PENDING", unknownId to "UNKNOWN")) {
                migrated.query("SELECT status,version,argumentsEnvelope FROM orion_action_requests WHERE actionId = ?", arrayOf(id)).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(status, it.getString(0))
                    assertEquals(7L, it.getLong(1))
                    assertArrayEquals(byteArrayOf(1, 2, 3), it.getBlob(2))
                }
            }
            migrated.query("SELECT formatVersion FROM orion_storage_metadata WHERE id = 1").use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
            migrated.query("SELECT count(*) FROM orion_domain_event_outbox").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test fun keyProvisioningEmptinessProofIncludesEveryEncryptedTable() {
        val sql = database.openHelper.writableDatabase
        assertFalse(database.records().hasEncryptedRecords())
        val records = listOf(
            "orion_tasks" to { val task = task(); database.tasks().createWithEvent(task, event(task.taskId)); Unit },
            "orion_reminders" to {
                val id = uuid()
                database.records().createReminderWithEvent(ReminderEntity(id, byteArrayOf(1), Sensitivity.SECRET,
                    500, "UTC", 100, 100), event(id, "REMINDER_CREATED"))
            },
            "orion_memories" to {
                val id = uuid()
                database.records().createMemoryWithClaimsAndEvent(MemoryEntity(id, byteArrayOf(1), Sensitivity.SECRET,
                    MemoryOrigin.USER_DECLARED, 1.0, true, 100, 100), emptyList(), event(id, "MEMORY_CREATED"))
            },
            "orion_ingress_envelopes" to { database.ingress().enqueue(ingress()); Unit },
            "orion_action_requests" to { insertOldAction(sql, uuid(), "UNKNOWN") },
        )
        records.forEach { (table, insert) ->
            database.runInTransaction {
                insert()
                assertTrue(table, database.records().hasEncryptedRecords())
                sql.execSQL("DELETE FROM $table")
            }
            assertFalse(table, database.records().hasEncryptedRecords())
        }
    }

    @Test fun upgradeFromOneTraversesBothExplicitMigrations() {
        migrations.createDatabase(upgradeName, 1).use {
            it.execSQL("INSERT INTO orion_storage_metadata(id, formatVersion) VALUES(1, 1)")
        }
        migrations.runMigrationsAndValidate(upgradeName, 3, true,
            OrionDatabaseMigrations.MIGRATION_1_2, OrionDatabaseMigrations.MIGRATION_2_3).close()
    }

    @Test fun failedEventInsertionRollsBackTaskAndSuccessfulCasCommitsExactlyOneEvent() {
        val first = task()
        val event = event(first.taskId)
        database.tasks().createWithEvent(first, event)
        val second = task()
        assertThrows(SQLiteConstraintException::class.java) {
            database.tasks().createWithEvent(second, event.copy(aggregateId = second.taskId))
        }
        assertNull(database.tasks().find(second.taskId))
        assertEquals(1, database.events().count())
        assertEquals(ActionMutation.APPLIED, database.tasks().changeStatusWithEvent(first.taskId, 0,
            TaskStatus.COMPLETED, 200, event(first.taskId, "TASK_COMPLETED", 1, 200)))
        assertEquals(ActionMutation.CONFLICT, database.tasks().changeStatusWithEvent(first.taskId, 0,
            TaskStatus.CANCELLED, 200, event(first.taskId, "TASK_CANCELLED", 1, 200)))
        assertEquals(2, database.events().count())
        assertEquals(1L, database.tasks().find(first.taskId)?.version)
    }

    @Test fun concurrentTaskMutationsHaveOneWinnerAndOneCorrespondingEvent() {
        val task = task()
        database.tasks().createWithEvent(task, event(task.taskId))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED).map { status ->
                executor.submit(Callable {
                    start.await(10, TimeUnit.SECONDS)
                    database.tasks().changeStatusWithEvent(task.taskId, 0, status, 200,
                        event(task.taskId, if (status == TaskStatus.COMPLETED) "TASK_COMPLETED" else "TASK_CANCELLED", 1, 200))
                })
            }
            start.countDown()
            assertEquals(listOf(ActionMutation.APPLIED, ActionMutation.CONFLICT).toSet(), results.map { it.get(10, TimeUnit.SECONDS) }.toSet())
            assertEquals(2, database.events().count())
        } finally { executor.shutdownNow() }
    }

    @Test fun sensitivityDowngradeAndMismatchedAggregateCannotCommitState() {
        val task = task()
        assertThrows(IllegalArgumentException::class.java) {
            database.tasks().createWithEvent(task, event(task.taskId).copy(payloadSensitivity = Sensitivity.PUBLIC))
        }
        assertThrows(IllegalArgumentException::class.java) { database.tasks().createWithEvent(task, event(uuid())) }
        assertNull(database.tasks().find(task.taskId))
        assertEquals(0, database.events().count())
    }

    @Test fun consumerProjectionAndReceiptAreAtomicAndDuplicateDeliveryIsHarmless() {
        val event = event(uuid())
        database.events().append(event)
        val projection = TechnicalEventEntity(uuid(), "CORE", "TASK_INDEXED", 200, uuid())
        assertEquals(EventConsumption.APPLIED, database.events().consumeTechnical(event.eventId, "index", projection))
        assertEquals(EventConsumption.DEDUP, database.events().consumeTechnical(event.eventId, "index", projection.copy(technicalEventId = uuid())))
        assertThrows(SQLiteConstraintException::class.java) {
            database.events().consumeTechnical(event.eventId, "other", projection)
        }
        assertEquals(0, database.events().consumed(event.eventId, "other"))
        assertEquals(1, database.events().consumed(event.eventId, "index"))
        database.close()
        database = openDatabase()
        assertEquals(1, database.events().consumed(event.eventId, "index"))
    }

    @Test fun retryBudgetIsPersistedBeforeDeliveryAndExhaustionBecomesDeadLetter() {
        val event = event(uuid())
        database.events().append(event)
        var now = 100L
        var calls = 0
        val consumer = DurableEventConsumer { calls++; DurableDeliveryResult.RETRY_LATER }
        fun dispatcher() = OrionEventDispatcher(database.events(), WallClock { Instant.ofEpochMilli(now) },
            mapOf("TASK_CREATED" to listOf(consumer)), EventRetryPolicy(maxAttempts = 2) { 0.0 })
        assertEquals(1, dispatcher().dispatchBatch().deferred)
        assertEquals(1, database.events().find(event.eventId)?.attemptCount)
        assertEquals(0, dispatcher().dispatchBatch().examined)
        now = database.events().find(event.eventId)!!.nextAttemptAt
        database.close(); database = openDatabase()
        assertEquals(1, dispatcher().dispatchBatch().deadLettered)
        assertEquals(DomainEventDeliveryStatus.DEAD_LETTER, database.events().find(event.eventId)?.status)
        now += 100_000
        assertEquals(0, dispatcher().dispatchBatch().examined)
        assertEquals(2, calls)
    }

    @Test fun interruptedLastAttemptAndUnknownSchemaNeverRetryForever() {
        val interrupted = event(uuid())
        val poison = event(uuid()).copy(schemaVersion = 99)
        database.events().append(interrupted)
        database.events().append(poison)
        database.events().reserveAttempt(interrupted.eventId, 0, 100, 200, 1)
        database.close(); database = openDatabase()
        var calls = 0
        val dispatcher = OrionEventDispatcher(database.events(), WallClock { Instant.ofEpochMilli(200) },
            mapOf("TASK_CREATED" to listOf(DurableEventConsumer { calls++; DurableDeliveryResult.ACCEPTED })), EventRetryPolicy(maxAttempts = 1))
        assertEquals(2, dispatcher.dispatchBatch().deadLettered)
        assertEquals(0, calls)
    }

    @Test fun slowConsumerCannotGiveLaterEventsAnAlreadyElapsedBackoff() {
        val first = event(uuid(), at = 100)
        val second = event(uuid(), at = 101)
        database.events().append(first)
        database.events().append(second)
        var now = 101L
        val seen = mutableListOf<String>()
        val consumer = DurableEventConsumer {
            seen += it.eventId
            if (it.eventId == first.eventId) {
                now += 10_000
                DurableDeliveryResult.ACCEPTED
            } else DurableDeliveryResult.RETRY_LATER
        }
        val dispatcher = OrionEventDispatcher(database.events(), WallClock { Instant.ofEpochMilli(now) },
            mapOf("TASK_CREATED" to listOf(consumer)), EventRetryPolicy { 0.0 })
        val report = dispatcher.dispatchBatch()
        assertEquals(listOf(first.eventId, second.eventId), seen)
        assertEquals(1, report.delivered)
        assertEquals(1, report.deferred)
        val deferred = checkNotNull(database.events().find(second.eventId))
        assertEquals(now + 500, deferred.nextAttemptAt)
        assertEquals(1, deferred.attemptCount)
        assertEquals(0, dispatcher.dispatchBatch().examined)
    }

    @Test fun rollbackDuringBatchLeavesLaterEventUnreservedUntilDueAgain() {
        val first = event(uuid(), at = 100)
        val second = event(uuid(), at = 101)
        database.events().append(first)
        database.events().append(second)
        var now = 101L
        val seen = mutableListOf<String>()
        val consumer = DurableEventConsumer {
            seen += it.eventId
            if (it.eventId == first.eventId) now = 100
            DurableDeliveryResult.ACCEPTED
        }
        val dispatcher = OrionEventDispatcher(database.events(), WallClock { Instant.ofEpochMilli(now) },
            mapOf("TASK_CREATED" to listOf(consumer)), EventRetryPolicy { 0.0 })
        assertEquals(1, dispatcher.dispatchBatch().delivered)
        assertEquals(listOf(first.eventId), seen)
        val pending = checkNotNull(database.events().find(second.eventId))
        assertEquals(DomainEventDeliveryStatus.PENDING, pending.status)
        assertEquals(0, pending.attemptCount)
        assertEquals(0L, pending.version)
        now = 101
        assertEquals(1, dispatcher.dispatchBatch().delivered)
        assertEquals(listOf(first.eventId, second.eventId), seen)
    }

    @Test fun encryptedTaskSurvivesReopenWithoutRawSecretInDatabaseOrOutbox() {
        val secret = "orion-secret-task-fixture-413A9D"
        val id = UUID.randomUUID()
        val encryption = encryption()
        var store = OrionTaskStore(database, encryption, WallClock { Instant.ofEpochMilli(100) })
        assertTrue(store.create(id, secret.toByteArray(), Sensitivity.SECRET, UUID.randomUUID()) is TaskCreationResult.Created)
        assertEquals(secret, String((store.readContent(id) as FieldDecryptionResult.Plaintext).copyBytes()))
        database.close()
        val dbFile = context.getDatabasePath(name)
        dbFile.parentFile!!.listFiles()!!.filter { it.name.startsWith(name) }.forEach {
            assertFalse(it.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        }
        database = openDatabase()
        store = OrionTaskStore(database, encryption, WallClock { Instant.ofEpochMilli(200) })
        assertEquals(secret, String((store.readContent(id) as FieldDecryptionResult.Plaintext).copyBytes()))
        assertEquals(1, database.events().count())
        val missing = object : FieldKeyProvider {
            override fun currentKey() = FieldKeyResult.Unavailable
            override fun keyForVersion(version: Int) = FieldKeyResult.Unavailable
        }
        val blocked = OrionTaskStore(database, OrionFieldEncryption(missing), WallClock { Instant.EPOCH }).readContent(id)
        assertEquals(FieldCryptoFailure.KEY_UNAVAILABLE, (blocked as FieldDecryptionResult.Failure).reason)
    }

    @Test fun inferredMemoryCannotBeCreatedConfirmedAndClaimsShareAtomicCommit() {
        val id = uuid()
        val memory = MemoryEntity(id, byteArrayOf(1), Sensitivity.SECRET, MemoryOrigin.LLM_INFERRED,
            0.3, true, 100, 100)
        assertThrows(IllegalArgumentException::class.java) {
            database.records().createMemoryWithClaimsAndEvent(memory, emptyList(), event(id, "MEMORY_CREATED"))
        }
        assertNull(database.records().memory(id))
        val claim = MemoryClaimEntity(uuid(), id, byteArrayOf(2), Sensitivity.SECRET,
            MemoryOrigin.LLM_INFERRED, 0.3, false, validFrom = 100)
        database.records().createMemoryWithClaimsAndEvent(memory.copy(userConfirmed = false), listOf(claim), event(id, "MEMORY_CREATED"))
        assertEquals(1, database.records().claims(id).size)
        assertFalse(database.records().memory(id)!!.userConfirmed)
    }

    @Test fun retentionIsBoundedAndPreservesUnresolvedEventsIngressAndReviewAudit() {
        val delivered = event(uuid())
        val pending = event(uuid())
        val dead = event(uuid())
        listOf(delivered, pending, dead).forEach { database.events().append(it) }
        database.events().delivered(delivered.eventId, 0, 150)
        database.events().deadLetter(dead.eventId, 0)
        val consumed = ingress()
        val failed = ingress()
        listOf(consumed, failed).forEach {
            database.ingress().enqueue(it)
            database.ingress().transition(it.ingressId, 0, DurableIngressStatus.PROCESSING, 200)
        }
        database.ingress().transition(consumed.ingressId, 1, DurableIngressStatus.CONSUMED, 300)
        database.ingress().transition(failed.ingressId, 1, DurableIngressStatus.FAILED, 300)
        val reviewAudit = AuditEntity(uuid(), uuid(), 0, "MANUAL_REVIEW", 100, uuid(), true)
        database.records().appendAudit(reviewAudit)
        database.records().appendAudit(reviewAudit.copy(auditId = uuid(), preserveUntilReview = false))
        val report = database.retention().prune(10_000, 5_000, 5_000, 5_000, 5_000, 5_000, 100, 1)
        assertEquals(1, report.events); assertEquals(1, report.ingress); assertEquals(1, report.audit)
        assertNull(database.events().find(delivered.eventId))
        assertNotNull(database.events().find(pending.eventId)); assertNotNull(database.events().find(dead.eventId))
        assertNull(database.ingress().find(consumed.ingressId)); assertNotNull(database.ingress().find(failed.ingressId))
        database.openHelper.readableDatabase.query("SELECT count(*) FROM orion_audit WHERE preserveUntilReview = 1").use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
    }

    @Test fun terminalIngressKeepsDedupKeyUntilItsRetentionDeadline() {
        val ingress = ingress().copy(idempotencyRetainUntil = 50_000)
        database.ingress().enqueue(ingress)
        database.ingress().transition(ingress.ingressId, 0, DurableIngressStatus.REJECTED, 200)
        assertEquals(0, database.retention().prune(10_000, 5_000, 5_000, 5_000, 5_000, 5_000, 100, 10).ingress)
        assertEquals(IngressRegistration.DEDUP, database.ingress().enqueue(ingress.copy(ingressId = uuid())))
        assertEquals(1, database.retention().prune(60_000, 5_000, 5_000, 5_000, 5_000, 5_000, 100, 10).ingress)
    }

    @Test fun actionPruningPreservesUncertainRunningReviewAndUnexpiredIdempotency() {
        val unknown = uuid()
        val review = uuid()
        val running = uuid()
        val cancelled = uuid()
        val held = uuid()
        val sql = database.openHelper.writableDatabase
        insertOldAction(sql, unknown, "UNKNOWN")
        insertOldAction(sql, review, "MANUAL_REVIEW")
        insertOldAction(sql, running, "RUNNING")
        insertOldAction(sql, cancelled, "CANCELLED")
        insertOldAction(sql, held, "SUCCEEDED")
        sql.execSQL("UPDATE orion_action_requests SET idempotencyRetainUntil = 50000 WHERE actionId = ?", arrayOf(held))
        assertEquals(1, database.retention().prune(10_000, 5_000, 5_000, 5_000, 5_000, 5_000, 100, 10).actions)
        assertNull(database.actions().find(cancelled))
        listOf(unknown, review, running, held).forEach { assertNotNull(database.actions().find(it)) }
        assertEquals(1, database.retention().prune(60_000, 5_000, 5_000, 5_000, 5_000, 5_000, 100, 10).actions)
        listOf(unknown, review, running).forEach { assertNotNull(database.actions().find(it)) }
    }

    @Test fun technicalCountRetentionRunsInBoundedBatches() {
        repeat(105) { index ->
            database.records().appendTechnical(TechnicalEventEntity(uuid(), "CORE", "TASK_INDEXED", 100L + index, uuid()))
        }
        fun prune() = database.retention().prune(10_000, 0, 0, 0, 0, 0, 100, 2)
        assertEquals(2, prune().technical)
        assertEquals(2, prune().technical)
        assertEquals(1, prune().technical)
        assertEquals(0, prune().technical)
        database.openHelper.readableDatabase.query("SELECT count(*) FROM orion_technical_events").use {
            assertTrue(it.moveToFirst()); assertEquals(100, it.getInt(0))
        }
    }

    @Test fun retentionContinuationDrainsEligibleBacklogAndStopsWithoutPruningReviewData() {
        repeat(105) { index ->
            database.records().appendTechnical(TechnicalEventEntity(uuid(), "CORE", "TASK_INDEXED", 100L + index, uuid()))
        }
        val unknown = uuid()
        insertOldAction(database.openHelper.writableDatabase, unknown, "UNKNOWN")
        var passes = 0
        var removed = 0
        do {
            val batch = database.retention().prune(10_000, 5000, 5000, 5000, 5000, 5000, 100, 50)
            removed += batch.technical
            passes++
            assertTrue("Cleanup must terminate", passes <= 3)
        } while (batch.needsContinuation(50))
        assertEquals(3, passes)
        assertEquals(105, removed)
        assertNotNull(database.actions().find(unknown))
    }

    private fun task() = TaskEntity(uuid(), byteArrayOf(1, 2), Sensitivity.SECRET, 100, 100)
    private fun event(id: String, type: String = "TASK_CREATED", version: Long = 0, at: Long = 100) =
        DomainEventOutboxEntity(uuid(), id, version, type, 1, Sensitivity.SECRET, at, uuid())
    private fun ingress() = IngressEnvelopeEntity(uuid(), "task.create", uuid(), "a".repeat(64), 1,
        "CreateTask", "UI", 0, uuid(), Sensitivity.SECRET, byteArrayOf(1), 100, 1_000, 2_000)
    private fun uuid() = UUID.randomUUID().toString()
    private fun encryption(): OrionFieldEncryption {
        val keys = object : FieldKeyProvider {
            override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 7 }, "AES"))
            override fun keyForVersion(version: Int): FieldKeyResult = if (version == 1) currentKey() else FieldKeyResult.Unavailable
        }
        return OrionFieldEncryption(keys)
    }
    private fun openDatabase() = Room.databaseBuilder(context, OrionDatabase::class.java, name)
        .addMigrations(OrionDatabaseMigrations.MIGRATION_1_2, OrionDatabaseMigrations.MIGRATION_2_3, OrionDatabaseMigrations.MIGRATION_3_4, OrionDatabaseMigrations.MIGRATION_4_5)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) { db.execSQL("INSERT INTO orion_storage_metadata(id, formatVersion) VALUES(1, 1)") }
        }).build()

    private fun insertOldAction(db: SupportSQLiteDatabase, id: String, status: String) {
        val values = ContentValues().apply {
            put("actionId", id); put("skill", "task.create"); put("argumentSchemaVersion", 1)
            put("argumentsEnvelope", byteArrayOf(1, 2, 3)); put("targetEnvelope", byteArrayOf(4, 5))
            put("payloadSensitivity", "SECRET"); put("authorizationLevel", "SAFE_WRITE")
            put("sideEffectClass", "LOCAL_MUTATION"); put("recoveryStrategy", "RETRY_SAFE")
            put("idempotencyMode", "KEYED"); put("policyVersion", "1"); put("status", status)
            put("createdAt", 100L); put("updatedAt", 200L); put("idempotencyScope", "task.create")
            put("idempotencyKey", uuid()); put("idempotencyKeyIssuer", "CORE")
            put("canonicalPayloadSchemaVersion", 1); put("idempotencyRetainUntil", 1_000L)
            put("correlationId", uuid()); put("requiresConfirmation", true)
            put("normalizedPayloadHash", "a".repeat(64)); put("confirmationFingerprint", "b".repeat(64))
            put("version", 7L); put("attemptCount", 2)
        }
        db.insert("orion_action_requests", 0, values)
    }
}
