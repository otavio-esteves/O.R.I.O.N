package network.orion.app

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.core.actions.*
import network.orion.core.ingress.*
import network.orion.core.model.*
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.data.repository.*
import network.orion.data.tasks.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Compatible with the schema-3 baseline: the host copies this fixture to the prior source checkout. */
@RunWith(AndroidJUnit4::class)
class UpgradePersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = WallClock { Instant.parse("2026-11-04T12:00:00Z") }

    @Test fun installedUpgradePreservesEncryptedRecordsAndUncertainActions() {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("orion_upgrade_mode") ?: "local"
        require(mode in setOf("local", "prepare", "verify"))
        val fixture = args.getString("orion_upgrade_fixture")?.let {
            require(UUID.fromString(it).toString() == it); UUID.fromString(it)
        } ?: run { require(mode == "local"); UUID.randomUUID() }
        fun id(kind: String) = UUID.nameUUIDFromBytes("$fixture/$kind".toByteArray(Charsets.UTF_8))
        val ids = listOf("task", "reminder", "memory", "claim", "pending", "unknown", "ingress").associateWith(::id)
        val marker = File(context.filesDir, "orion_upgrade_$fixture.properties")
        val database = OrionDatabaseFactory.open(context)
        var cleanup = mode == "local"
        try {
            val keys = AndroidKeystoreFieldKeyProvider.open(context)
            if (mode != "verify" && keys.currentKey() is FieldKeyResult.Unavailable) {
                // Baseline has no provisioning bootstrap. Never create keys over any encrypted row/artifact.
                val tables = mutableListOf("orion_action_requests", "orion_tasks", "orion_reminders", "orion_memories",
                    "orion_memory_claims", "orion_ingress_envelopes")
                // The same fixture source can build against schema3 and the additive timer schema4.
                database.openHelper.readableDatabase.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'orion_timers'",
                ).use { if (it.moveToFirst()) tables += "orion_timers" }
                tables.forEach { table -> database.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                } }
                val temporary = File(context.noBackupFilesDir, "orion_temporary_payloads")
                assertTrue(!temporary.exists() || temporary.listFiles()?.isEmpty() == true)
                assertEquals(FieldKeyLifecycleResult.APPLIED, keys.provisionNewInstallation())
            }
            val key = keys.currentKey() as FieldKeyResult.Available
            val encryption = OrionFieldEncryption(keys)
            val taskStore = OrionTaskStore(database, encryption, clock)
            val actionStore = OrionActionStore(database, encryption, clock)
            val content = "orion-upgrade-synthetic-$fixture"
            fun encrypted(entity: String, field: String = "content"): ByteArray = FieldEnvelopeCodec.encode(
                (encryption.encrypt(content.toByteArray(), FieldEncryptionContext(entity, field, 1)) as FieldEncryptionResult.Encrypted).envelope)
            fun event(aggregate: UUID, type: String) = DomainEventOutboxEntity(id("event.$type").toString(),
                aggregate.toString(), 0, type, 1, Sensitivity.SECRET, clock.wallNow().toEpochMilli(), fixture.toString())
            if (mode != "verify") {
                require(!marker.exists())
                database.runInTransaction {
                    assertTrue(taskStore.create(ids.getValue("task"), content.toByteArray(), Sensitivity.SECRET, fixture) is TaskCreationResult.Created)
                    val reminder = ids.getValue("reminder")
                    val at = clock.wallNow().toEpochMilli()
                    database.records().createReminderWithEvent(ReminderEntity(reminder.toString(), encrypted("reminder/$reminder"),
                        Sensitivity.SECRET, at + 86_400_000, "UTC", at, at), event(reminder, "REMINDER_CREATED"))
                    val memory = ids.getValue("memory")
                    val claim = ids.getValue("claim")
                    database.records().createMemoryWithClaimsAndEvent(MemoryEntity(memory.toString(), encrypted("memory/$memory"),
                        Sensitivity.SECRET, MemoryOrigin.LLM_INFERRED, 0.3, false, at, at),
                        listOf(MemoryClaimEntity(claim.toString(), memory.toString(), encrypted("memory/$memory/claim/$claim", "statement"),
                            Sensitivity.SECRET, MemoryOrigin.LLM_INFERRED, 0.3, false, validFrom = at)), event(memory, "MEMORY_CREATED"))
                    for (kind in listOf("pending", "unknown")) {
                        val request = NormalizedActionRequest(ids.getValue(kind), "upgrade.fixture", "{\"fixture\":\"$fixture\"}",
                            "synthetic", AuthorizationLevel.SAFE_WRITE, 1, SideEffectClass.EXTERNAL_IRREVERSIBLE,
                            RecoveryStrategy.NO_RETRY, IdempotencyMode.KEYED, "1")
                        assertEquals(ActionStoreOutcome.REGISTERED, actionStore.register(request,
                            IdempotencyKeyPolicy("CORE", "upgrade.fixture", Duration.ofDays(30), 1,
                                OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT), id("key.$kind").toString(), fixture,
                            Sensitivity.SECRET, true).outcome)
                        if (kind == "unknown") {
                            // Persist a legal synthetic uncertain history; no Skill/external invocation occurs.
                            assertEquals(ActionStoreOutcome.APPLIED, actionStore.requireConfirmation(request.actionId, 0).outcome)
                            val confirmation = ActionConfirmationBinding(clock).recordConfirmation(request, clock.wallNow().plusSeconds(60))
                            assertEquals(ActionStoreOutcome.APPLIED, actionStore.confirm(request.actionId, 1, confirmation, "1").outcome)
                            val execution = id("execution").toString()
                            val row = database.actions().find(request.actionId.toString())!!
                            assertEquals(ActionMutation.APPLIED, database.actions().claimConfirmed(row.actionId, 2,
                                row.normalizedPayloadHash, row.confirmationFingerprint, "1", execution, "upgrade_fixture", 4, 100, 1000, at,
                                ActionAuditEntity(id("audit.claim").toString(), row.actionId, 3, ActionStatus.CONFIRMED, ActionStatus.RUNNING, at, fixture.toString())))
                            assertEquals(ActionMutation.APPLIED, database.actions().finishExecution(row.actionId, 3, execution, 4, 200,
                                ActionStatus.UNKNOWN, at, ActionAuditEntity(id("audit.unknown").toString(), row.actionId, 4,
                                    ActionStatus.RUNNING, ActionStatus.UNKNOWN, at, fixture.toString())))
                        }
                    }
                    val ingress = OrionDurableIngressStore(database, encryption, clock, StartupBarrier(), LocalIngressConsumer {
                        throw AssertionError("Upgrade fixture must never drain ingress")
                    })
                    assertEquals(DurableIngressOutcome.QUEUED, ingress.enqueue(DurableIngressRequest(ids.getValue("ingress"),
                        "upgrade.fixture", id("key.ingress"), "UI", "UpgradeFixture", 1, Sensitivity.SECRET,
                        false, null, content.toByteArray(), 86_400_000, 172_800_000, correlationId = fixture)))
                }
                val properties = Properties().apply {
                    setProperty("version", installedVersion().toString()); setProperty("pid", Process.myPid().toString())
                    setProperty("keyVersion", key.version.toString()); setProperty("schema", database.openHelper.readableDatabase.version.toString())
                }
                FileOutputStream(marker).use { output -> properties.store(output, "Synthetic upgrade fixture"); output.fd.sync() }
            }
            if (mode != "prepare") {
                require(marker.isFile)
                val properties = Properties().also { marker.inputStream().use(it::load) }
                if (mode == "verify") {
                    assertTrue(installedVersion() > properties.getProperty("version").toLong())
                    assertNotEquals(properties.getProperty("pid"), Process.myPid().toString())
                    val app = context.applicationContext as OrionApplication
                    val health = runBlocking {
                        withTimeout(30_000) { checkNotNull(app.mainRuntime).health.first { it.core.readiness.isStartupBarrierOpen } }
                    }
                    assertEquals("HEALTHY", health.subsystems.entries.single { it.key.name == "FIELD_KEYS" }.value.name)
                }
                assertEquals(properties.getProperty("keyVersion").toInt(), key.version)
                assertEquals(5, database.openHelper.readableDatabase.version)
                assertEquals(content, String((taskStore.readContent(ids.getValue("task")) as FieldDecryptionResult.Plaintext).copyBytes()))
                for (kind in listOf("reminder", "memory")) {
                    val recordId = ids.getValue(kind)
                    val bytes = if (kind == "reminder") database.records().reminder(recordId.toString())!!.contentEnvelope
                        else database.records().memory(recordId.toString())!!.contentEnvelope
                    val parsed = FieldEnvelopeCodec.decode(bytes) as FieldEnvelopeDecodingResult.Decoded
                    assertEquals(content, String((encryption.decrypt(parsed.envelope, FieldEncryptionContext("$kind/$recordId", "content", 1))
                        as FieldDecryptionResult.Plaintext).copyBytes()))
                }
                assertFalse(database.records().memory(ids.getValue("memory").toString())!!.userConfirmed)
                assertEquals(1, database.records().claims(ids.getValue("memory").toString()).size)
                val claim = database.records().claims(ids.getValue("memory").toString()).single()
                val claimEnvelope = FieldEnvelopeCodec.decode(claim.statementEnvelope) as FieldEnvelopeDecodingResult.Decoded
                assertEquals(content, String((encryption.decrypt(claimEnvelope.envelope,
                    FieldEncryptionContext("memory/${ids.getValue("memory")}/claim/${ids.getValue("claim")}", "statement", 1))
                    as FieldDecryptionResult.Plaintext).copyBytes()))
                assertEquals(ActionStatus.PENDING, (actionStore.read(ids.getValue("pending")) as ActionReadResult.Ready).action.status)
                assertEquals(ActionStatus.UNKNOWN, (actionStore.read(ids.getValue("unknown")) as ActionReadResult.Ready).action.status)
                assertEquals(1, database.actions().find(ids.getValue("unknown").toString())!!.attemptCount)
                assertEquals(5, database.actions().auditFor(ids.getValue("unknown").toString()).size)
                assertEquals(DurableIngressStatus.QUEUED, database.ingress().find(ids.getValue("ingress").toString())!!.status)
                for (kind in listOf("task", "reminder", "memory")) {
                    database.openHelper.readableDatabase.query("SELECT status,attemptCount FROM orion_domain_event_outbox WHERE aggregateId = ?",
                        arrayOf(ids.getValue(kind).toString())).use {
                        assertEquals(1, it.count); assertTrue(it.moveToFirst()); assertTrue(it.getString(0) in setOf("PENDING", "DELIVERED"))
                        assertTrue(it.getInt(1) in 0..5) // Recovery may publish a committed fact, never replay a domain action.
                    }
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("orion_upgrade_verified_pid", Process.myPid().toString())
                    putString("orion_upgrade_version", installedVersion().toString())
                })
                cleanup = true
            }
        } finally {
            try {
                if (cleanup) {
                    database.runInTransaction {
                        val sql = database.openHelper.writableDatabase
                        for (kind in listOf("pending", "unknown")) {
                            sql.execSQL("DELETE FROM orion_action_audit WHERE actionId = ?", arrayOf(ids.getValue(kind).toString()))
                            sql.execSQL("DELETE FROM orion_action_requests WHERE actionId = ?", arrayOf(ids.getValue(kind).toString()))
                        }
                        listOf("task" to "orion_tasks", "reminder" to "orion_reminders", "memory" to "orion_memories").forEach { (kind, table) ->
                            sql.execSQL("DELETE FROM $table WHERE ${kind}Id = ?", arrayOf(ids.getValue(kind).toString()))
                            sql.execSQL("DELETE FROM orion_domain_event_outbox WHERE aggregateId = ?", arrayOf(ids.getValue(kind).toString()))
                        }
                        sql.execSQL("DELETE FROM orion_ingress_envelopes WHERE ingressId = ?", arrayOf(ids.getValue("ingress").toString()))
                    }
                    marker.delete()
                }
            } finally { database.close() }
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersion(): Long = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
}
