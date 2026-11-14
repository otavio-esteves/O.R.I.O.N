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
import network.orion.data.database.ModelRegistryEntity
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.models.OrionExpectedModelFile
import network.orion.data.repository.models.OrionModelImportCoordinator
import network.orion.data.repository.models.OrionModelImportResult
import network.orion.data.repository.models.OrionModelRemovalOutcome
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic tiny model only. Actual death evidence requires the existing host recorder. */
@RunWith(AndroidJUnit4::class)
class OrionModelImportProcessDeathTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    @Test fun verifiedFileBeforeRegistryCommitRemainsUnregistered() = exercise(registered = false)
    @Test fun importedRegistryBeforeReadyRequiresSelectedValidation() = exercise(registered = true)
    @Test fun rejectedModelUnlinkBeforeCommitPreservesRejectedMetadata() = exercise(registered = true, rejectedRemoval = true)

    private fun exercise(registered: Boolean, rejectedRemoval: Boolean = false) {
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
            val metadata = File(root, "rejected_before_unlink.txt")
            if (mode != "verify") {
                if (rejectedRemoval) {
                    assertTrue(files.importModel(expected, { bytes.inputStream() }) is OrionModelImportResult.Ready)
                    val ready = checkNotNull(db.models().find(hash))
                    assertEquals(1, db.models().invalidate(hash, ready.version, true))
                    val rejected = checkNotNull(db.models().find(hash))
                    assertEquals("QUARANTINED", rejected.status)
                    assertEquals(3L, rejected.version)
                    writeSynced(metadata, snapshot(rejected))
                    try {
                        files.removeRejected(hash, rejected.version) {
                            if (!published.exists()) {
                                // removeRejected invokes this check after unlink AND
                                // directory fsync, while its CAS DELETE is uncommitted.
                                assertTrue("Removal checkpoint must hold the Room writer transaction", db.inTransaction())
                                assertNull("CAS must precede unlink", db.models().find(hash))
                                interruptAtCheckpoint(mode, marker)
                            }
                        }
                        fail("Removal passed its required checkpoint")
                    } catch (_: CancellationException) { }
                } else try {
                    files.importModel(expected, { bytes.inputStream() }) {
                        val row = db.models().find(hash)
                        val reached = if (registered) row?.status == "IMPORTED" else published.isFile && row == null
                        if (reached) {
                            assertFalse("Checkpoint must be outside a Room transaction", db.inTransaction())
                            interruptAtCheckpoint(mode, marker)
                        }
                    }
                    fail("Import passed its required checkpoint")
                } catch (_: CancellationException) { }
            }
            db.verifyStructure()
            if (rejectedRemoval) {
                require(metadata.isFile)
                val original = checkNotNull(db.models().find(hash))
                assertEquals(metadata.readText(), snapshot(original))
                assertEquals("QUARANTINED", original.status)
                assertEquals(3L, original.version)
                assertFalse(published.exists())
                assertNull(files.openReadOnly(hash))
                assertFalse(files.recoverSelected(hash))
                assertEquals(0, files.recoverPage().ready)
                assertEquals(metadata.readText(), snapshot(checkNotNull(db.models().find(hash))))
                assertFalse(published.exists())
                // Only this fresh explicit management call may finish cleanup.
                // No file restoration, source opening, native load or inference.
                assertEquals(OrionModelRemovalOutcome.REMOVED, files.removeRejected(hash, original.version))
                assertNull(db.models().find(hash))
                assertEquals(0, db.models().countAll())
                assertFalse(published.exists())
            } else if (registered) {
                assertTrue(published.isFile)
                assertEquals("IMPORTED", db.models().find(hash)?.status)
                assertNull(files.openReadOnly(hash))
                assertTrue(files.recoverSelected(hash))
                assertEquals("READY", db.models().find(hash)?.status)
                files.openReadOnly(hash)?.close() ?: fail("Recovered model could not reopen")
            } else {
                assertTrue(published.isFile)
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

    private fun interruptAtCheckpoint(mode: String, marker: File): Nothing {
        if (mode == "prepare") {
            writeSynced(marker, Process.myPid().toString())
            CountDownLatch(1).await(45, TimeUnit.SECONDS)
            throw AssertionError("Host did not kill the exact checkpoint PID before timeout.")
        }
        throw CancellationException("Synthetic model checkpoint")
    }

    private fun writeSynced(file: File, value: String) {
        FileOutputStream(file).use { stream ->
            stream.write(value.toByteArray(Charsets.UTF_8)); stream.fd.sync()
        }
    }

    /** All fields of owned synthetic technical metadata, including its original version. */
    private fun snapshot(row: ModelRegistryEntity): String = listOf(row.modelId, row.displayName,
        row.filePath, row.sha256, row.sizeBytes.toString(), row.architecture.orEmpty(), row.quantization.orEmpty(),
        row.contextLimit?.toString().orEmpty(), row.status, row.createdAt.toString(),
        row.lastValidatedAt?.toString().orEmpty(), row.version.toString()).joinToString("\n")

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
