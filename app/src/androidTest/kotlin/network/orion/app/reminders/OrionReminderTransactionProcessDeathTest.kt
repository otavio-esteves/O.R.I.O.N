package network.orion.app.reminders

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec
import network.orion.core.actions.*
import network.orion.core.commands.reminders.CreateReminder
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.policy.reminders.*
import network.orion.core.security.*
import network.orion.core.time.*
import network.orion.data.actions.OrionActionStore
import network.orion.data.database.OrionDatabase
import network.orion.data.reminders.OrionOwnedReminderStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic fixtures only. SIGKILL/new-PID evidence requires the host recorder's reminder scenario. */
@RunWith(AndroidJUnit4::class)
class OrionReminderTransactionProcessDeathTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun interruptedOwnedReminderExecutionRollsBackThenFreshReplayCreatesExactlyOnce() {
        val arguments = InstrumentationRegistry.getArguments()
        val mode = arguments.getString("orion_transaction_mode") ?: "rollback"
        require(mode in setOf("rollback", "prepare", "verify"))
        val fixture = arguments.getString("orion_transaction_fixture")?.let {
            require(UUID.fromString(it).toString() == it); UUID.fromString(it)
        } ?: run { require(mode == "rollback"); UUID.randomUUID() }
        val name = "orion_reminder_transaction_death_$fixture.db"
        val checkpoint = File(context.filesDir, "orion_transaction_$fixture.ready")
        if (mode == "verify") require(context.getDatabasePath(name).isFile)
        else require(!context.getDatabasePath(name).exists())
        val armed = AtomicBoolean(false)
        val observed = AtomicBoolean(false)
        val reference = AtomicReference<OrionDatabase>()
        val key = UUID.nameUUIDFromBytes("$fixture/key".toByteArray())
        val db = Room.databaseBuilder(context, OrionDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback({ sql, _ ->
                if (armed.get() && sql.contains("TRANSACTION") && !sql.startsWith("BEGIN")) {
                    val database = reference.get()
                    if (database.actions().findByKey("reminder.create", key.toString())?.status == ActionStatus.SUCCEEDED) {
                        armed.set(false)
                        // The checkpoint is AFTER all local writes, while SQLite still owns the uncommitted transaction.
                        assertEquals(1, count(database, "orion_reminders"))
                        assertEquals(1, count(database, "orion_domain_event_outbox"))
                        assertTrue(database.inTransaction())
                        observed.set(true)
                        if (mode == "prepare") {
                            FileOutputStream(checkpoint).use { stream ->
                                stream.write(Process.myPid().toString().toByteArray(Charsets.US_ASCII)); stream.fd.sync()
                            }
                            CountDownLatch(1).await(45, TimeUnit.SECONDS)
                            throw AssertionError("Host did not kill the owned reminder transaction at its checkpoint.")
                        }
                        // Room dispatches query callbacks in a coroutine even with a direct
                        // executor. Never throw a synthetic rollback from that coroutine.
                    }
                }
            }, Executor { it.run() }).build()
        reference.set(db)
        val clock = object : OrionClock {
            override fun wallNow() = Instant.ofEpochMilli(1000)
            override fun monotonicNow() = MonotonicInstant(BootSessionId(7), 100)
        }
        val encryption = OrionFieldEncryption(object : FieldKeyProvider {
            override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 13 }, "AES"))
            override fun keyForVersion(version: Int) = currentKey()
        })
        val barrier = StartupBarrier()
        barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.READY)
        val owner = OrionReminderAdmissionOwner(OrionProcessRole.MAIN, barrier,
            { OrionPolicyEvidence.SATISFIED }, { OrionPolicyEvidence.SATISFIED })
        val channel = owner.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
        val command = CreateReminder(UUID.nameUUIDFromBytes("$fixture/reminder".toByteArray()), "Synthetic durable reminder", Instant.ofEpochMilli(60_000), java.time.ZoneId.of("UTC"), key, fixture)
        fun admission() = (channel.captureCreateFromUser(command) as OrionReminderAdmissionResult.Admitted).request
        val actions = OrionActionStore(db, encryption, clock)
        val preparation = OrionCreateReminderActionStore(db, actions, clock, barrier)
        val runner = OrionCreateReminderRunner(db, actions, OrionOwnedReminderStore(db, encryption, clock), clock, barrier)
        try {
            if (mode != "verify") {
                val pending = (preparation.prepare(admission()) as OrionReminderActionResult.Ready).preview
                val confirmed = (preparation.confirm(admission(), pending) as OrionReminderActionResult.Ready).preview
                armed.set(true)
                try {
                    // This mirrors the enclosing durable-ingress transaction. Acquire
                    // the barrier before SQLite, preserving the production lock order.
                    barrier.whileOpen {
                        db.runInTransaction {
                            runner.execute(admission(), confirmed)
                            assertTrue("The fixture must observe all staged local writes", observed.get())
                            throw Rollback()
                        }
                    }
                } catch (_: Rollback) { /* Default suite run exercises the same interrupted boundary. */ }
            }
            val row = checkNotNull(db.actions().findByKey("reminder.create", key.toString()))
            assertEquals(ActionStatus.CONFIRMED, row.status)
            assertEquals(2L, row.version)
            assertEquals(3, db.actions().auditFor(row.actionId).size)
            assertEquals(0, count(db, "orion_reminders")); assertEquals(0, count(db, "orion_domain_event_outbox"))
            if (mode == "verify") {
                assertTrue(checkpoint.isFile)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("orion_transaction_verified_pid", Process.myPid().toString())
                })
            }
            val restored = OrionReminderActionPreview(admission().reminder, UUID.fromString(row.actionId), row.version,
                row.confirmationFingerprint, row.policyVersion, row.status)
            assertTrue(runner.execute(admission(), restored) is OrionReminderExecutionResult.Completed)
            assertTrue((runner.execute(admission(), restored) as OrionReminderExecutionResult.Completed).deduplicated)
            assertEquals(1, count(db, "orion_reminders")); assertEquals(1, count(db, "orion_domain_event_outbox"))
            assertEquals(ActionStatus.SUCCEEDED, db.actions().find(row.actionId)?.status)
            assertEquals(5, db.actions().auditFor(row.actionId).size)
        } finally {
            channel.close(); db.close()
            if (mode != "prepare") { context.deleteDatabase(name); checkpoint.delete() }
        }
    }

    private fun count(db: OrionDatabase, table: String) = db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }
    private class Rollback : RuntimeException()
}
