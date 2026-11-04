package network.orion.app

import android.content.Context
import android.os.Process
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import network.orion.data.database.OrionDatabase
import network.orion.data.database.TechnicalEventEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable technical fixtures only. Host SIGKILL evidence requires the separate recorder. */
@RunWith(AndroidJUnit4::class)
class TransactionProcessDeathTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun committedBaselineSurvivesWhileInterruptedMutationRollsBack() {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("orion_transaction_mode") ?: "rollback"
        require(mode in setOf("rollback", "prepare", "verify"))
        val fixture = args.getString("orion_transaction_fixture")?.let {
            require(UUID.fromString(it).toString() == it); UUID.fromString(it)
        } ?: run { require(mode == "rollback"); UUID.randomUUID() }
        val name = "orion_transaction_death_$fixture.db"
        val checkpoint = File(context.filesDir, "orion_transaction_$fixture.ready")
        val baseline = marker(fixture, "baseline")
        val pending = marker(fixture, "uncommitted")
        if (mode == "verify") require(context.getDatabasePath(name).isFile) { "Missing prepared transaction fixture." }
        else require(!context.getDatabasePath(name).exists()) { "Transaction fixture already exists." }
        val db = Room.databaseBuilder(context, OrionDatabase::class.java, name).build()
        var cleanup = mode != "prepare"
        try {
            if (mode != "verify") {
                db.runInTransaction { db.records().appendTechnical(event(baseline, fixture)) }
                try {
                    db.runInTransaction {
                        db.records().appendTechnical(event(pending, fixture))
                        if (mode == "prepare") {
                            FileOutputStream(checkpoint).use { stream ->
                                stream.write(Process.myPid().toString().toByteArray(Charsets.US_ASCII))
                                stream.fd.sync()
                            }
                            // Never commit on timeout. The host must kill this exact PID after checkpoint.
                            CountDownLatch(1).await(45, TimeUnit.SECONDS)
                            throw AssertionError("Host did not interrupt the open transaction before timeout.")
                        }
                        throw Rollback()
                    }
                } catch (_: Rollback) { /* Ordinary suite invocation proves the same rollback assertions. */ }
            }
            db.openHelper.readableDatabase.query(
                "SELECT technicalEventId FROM orion_technical_events ORDER BY technicalEventId"
            ).use { cursor ->
                assertEquals(1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(baseline, cursor.getString(0))
                assertNotEquals(pending, cursor.getString(0))
            }
            if (mode == "verify") {
                assertTrue("Host checkpoint must survive process death", checkpoint.isFile)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("orion_transaction_verified_pid", Process.myPid().toString())
                })
                cleanup = true
            }
        } finally {
            db.close()
            if (cleanup) { context.deleteDatabase(name); checkpoint.delete() }
        }
    }

    private fun marker(fixture: UUID, label: String) =
        UUID.nameUUIDFromBytes("$fixture/$label".toByteArray(Charsets.US_ASCII)).toString()
    private fun event(id: String, fixture: UUID) = TechnicalEventEntity(id, "TRANSACTION_TEST", "SYNTHETIC_MARKER", 1L, fixture.toString())
    private class Rollback : RuntimeException()
}
