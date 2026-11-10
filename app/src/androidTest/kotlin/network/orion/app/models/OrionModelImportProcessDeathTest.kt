package network.orion.app.models

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Process
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import network.orion.core.time.WallClock
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.models.OrionExpectedModelFile
import network.orion.data.repository.models.OrionModelImportCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic tiny model only. Actual death evidence requires the existing host recorder. */
@RunWith(AndroidJUnit4::class)
class OrionModelImportProcessDeathTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    @Test fun verifiedFileBeforeRegistryCommitRemainsUnregistered() = exercise(registered = false)
    @Test fun importedRegistryBeforeReadyRequiresSelectedValidation() = exercise(registered = true)

    private fun exercise(registered: Boolean) {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("orion_transaction_mode") ?: "rollback"
        require(mode in setOf("rollback", "prepare", "verify"))
        val fixture = args.getString("orion_transaction_fixture")?.also { require(UUID.fromString(it).toString() == it) }
            ?: run { require(mode == "rollback"); UUID.randomUUID().toString() }
        val name = "orion_model_death_$fixture.db"
        val root = File(app.noBackupFilesDir, "orion_model_death_$fixture")
        val marker = File(app.filesDir, "orion_transaction_$fixture.ready")
        if (mode == "verify") {
            require(root.isDirectory && app.getDatabasePath(name).isFile && marker.isFile)
            assertNotEquals(marker.readText(), Process.myPid().toString())
        } else {
            require(!root.exists() && !app.getDatabasePath(name).exists() && !marker.exists())
            check(root.mkdirs())
        }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val db = Room.databaseBuilder(app, OrionDatabase::class.java, name)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("INSERT INTO orion_storage_metadata(id,formatVersion) VALUES(1,1)")
                }
            }).build()
        try {
            val files = OrionModelImportCoordinator.open(context, db, WallClock { Instant.ofEpochMilli(200) }, 1024 * 1024, 100)
            val bytes = tinyModel()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val expected = OrionExpectedModelFile(hash, bytes.size.toLong())
            val published = File(root, "orion_model_files/verified/$hash.gguf")
            if (mode != "verify") {
                try {
                    files.importModel(expected, { bytes.inputStream() }) {
                        val row = db.models().find(hash)
                        val reached = if (registered) row?.status == "IMPORTED" else published.isFile && row == null
                        if (reached) {
                            assertFalse("Checkpoint must be outside a Room transaction", db.inTransaction())
                            if (mode == "prepare") {
                                FileOutputStream(marker).use { stream ->
                                    stream.write(Process.myPid().toString().toByteArray(Charsets.US_ASCII)); stream.fd.sync()
                                }
                                CountDownLatch(1).await(45, TimeUnit.SECONDS)
                                throw AssertionError("Host did not kill the exact checkpoint PID before timeout.")
                            }
                            throw CancellationException("Synthetic import checkpoint")
                        }
                    }
                    fail("Import passed its required checkpoint")
                } catch (_: CancellationException) { }
            }
            db.verifyStructure()
            assertTrue(published.isFile)
            if (registered) {
                assertEquals("IMPORTED", db.models().find(hash)?.status)
                assertNull(files.openReadOnly(hash))
                assertTrue(files.recoverSelected(hash))
                assertEquals("READY", db.models().find(hash)?.status)
                files.openReadOnly(hash)?.close() ?: fail("Recovered model could not reopen")
            } else {
                assertNull(db.models().find(hash))
                assertFalse(files.recoverSelected(hash))
                assertEquals(1, files.cleanupUnregisteredFiles().deleted)
                assertFalse(published.exists())
                assertEquals(0, db.models().countAll())
            }
            if (mode == "verify") InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("orion_transaction_verified_pid", Process.myPid().toString())
            })
        } finally {
            db.close()
            if (mode != "prepare") { app.deleteDatabase(name); root.deleteRecursively(); marker.delete() }
        }
    }

    private fun tinyModel(): ByteArray {
        val out = ByteArrayOutputStream()
        fun number(value: Long, size: Int) { repeat(size) { out.write((value ushr (it * 8)).toInt() and 255) } }
        fun text(value: String) { val bytes = value.toByteArray(); number(bytes.size.toLong(), 8); out.write(bytes) }
        number(0x46554747, 4); number(3, 4); number(1, 8); number(2, 8)
        text("general.architecture"); number(8, 4); text("llama")
        text("llama.context_length"); number(4, 4); number(2048, 4)
        text("weight"); number(1, 4); number(4, 8); number(0, 4); number(0, 8)
        while (out.size() % 32 != 0) out.write(0)
        out.write(ByteArray(16))
        return out.toByteArray()
    }
}
