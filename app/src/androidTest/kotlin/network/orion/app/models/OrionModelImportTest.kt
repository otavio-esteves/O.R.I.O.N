package network.orion.app.models

import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import network.orion.core.time.WallClock
import network.orion.data.database.ModelRegistryEntity
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.models.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionModelImportTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    @Test fun copyBeforeReadyDedupAndReadOnlyDescriptorPreserveExactContent() = fixture { db, files, root ->
        val bytes = model()
        val expected = expected(bytes)
        val result = files.importModel(expected, { ByteArrayInputStream(bytes) }) as OrionModelImportResult.Ready
        assertFalse(result.reused)
        val row = checkNotNull(db.models().find(expected.sha256))
        assertEquals("READY", row.status)
        assertEquals(2L, row.version)
        assertEquals("llama", row.architecture)
        assertEquals(2048, row.contextLimit)
        assertEquals("F32", row.quantization)
        assertTrue(File(root, "orion_model_files/verified/${expected.sha256}.gguf").isFile)
        assertTrue((files.importModel(expected, { error("Duplicate input opened") }) as OrionModelImportResult.Ready).reused)
        val fd = checkNotNull(files.openReadOnly(expected.sha256))
        try { Os.write(fd.fileDescriptor, byteArrayOf(1), 0, 1); fail("Descriptor was writable") }
        catch (_: ErrnoException) { }
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { assertArrayEquals(bytes, it.readBytes()) }
    }

    @Test fun trustedFrameworkBaseNormalizesAliasesButControlledStoreSymlinksRemainRejected() = fixture { db, _, root ->
        val alias = File(root, "framework_no_backup_alias")
        val base = File(root, "framework_private_base").also { check(it.mkdir()) }
        Os.symlink(base.path, alias.path)
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = alias
        }
        val files = OrionModelImportCoordinator.open(context, db, WallClock { Instant.ofEpochMilli(200) },
            maximumModelBytes = 1024 * 1024, reserveBytes = 100)
        val bytes = model()
        val expected = expected(bytes)
        assertTrue(files.importModel(expected, { ByteArrayInputStream(bytes) }) is OrionModelImportResult.Ready)
        assertEquals("READY", db.models().find(expected.sha256)?.status)

        // A different trusted base is legitimate; its controlled child cannot be a link.
        val secondBase = File(root, "second_private_base").also { check(it.mkdir()) }
        val outside = File(root, "outside_store").also { check(it.mkdir()) }
        Os.symlink(outside.path, File(secondBase, "orion_model_files").path)
        val secondContext = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = secondBase
        }
        val second = OrionModelImportCoordinator.open(secondContext, db, WallClock { Instant.ofEpochMilli(200) },
            maximumModelBytes = 1024 * 1024, reserveBytes = 100)
        val newBytes = model(context = 4096)
        val rejected = second.importModel(expected(newBytes), { error("Unsafe child opened source") })
        assertEquals(OrionModelImportResult.Rejected(OrionModelImportBlock.FILE_REJECTED,
            OrionModelFileFailure.UNSAFE_PATH), rejected)
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }

    @Test fun interruptionAfterFileCommitLeavesNoReadyAndUnregisteredCleanupIsBounded() = fixture { db, files, root ->
        val bytes = model()
        val expected = expected(bytes)
        val path = File(root, "orion_model_files/verified/${expected.sha256}.gguf")
        val cancelled = CancellationException("synthetic post-file checkpoint")
        try {
            files.importModel(expected, { ByteArrayInputStream(bytes) }) { if (path.exists()) throw cancelled }
            fail("Cancellation swallowed")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
        assertNull(db.models().find(expected.sha256))
        assertTrue(path.exists())
        val result = files.cleanupUnregisteredFiles(limit = 1)
        assertEquals(1, result.deleted)
        assertFalse(path.exists())
    }

    @Test fun interruptionAfterRegistrationIsRecoveredWithoutReimportOrInference() = fixture { db, files, _ ->
        val bytes = model()
        val expected = expected(bytes)
        try {
            files.importModel(expected, { ByteArrayInputStream(bytes) }) {
                if (db.models().find(expected.sha256) != null) throw CancellationException("synthetic imported checkpoint")
            }
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
        assertEquals("IMPORTED", db.models().find(expected.sha256)?.status)
        assertNull(files.openReadOnly(expected.sha256))
        val recovered = files.recoverPage(limit = 1)
        assertEquals(1, recovered.ready)
        assertEquals("READY", db.models().find(expected.sha256)?.status)
        files.openReadOnly(expected.sha256)?.close() ?: fail("Recovered file unavailable")
    }

    @Test fun selectedRecoveryHandlesLaterInterruptedImportsAfterCompletedScanWithoutTouchingOtherRows() = fixture { db, files, _ ->
        assertEquals(0, files.recoverPage().inspected)
        val unrelatedBytes = model(context = 1024)
        val unrelated = expected(unrelatedBytes)
        assertTrue(files.importModel(unrelated, { ByteArrayInputStream(unrelatedBytes) }) is OrionModelImportResult.Ready)
        assertEquals(1, files.recoverPage().inspected)
        val unchanged = checkNotNull(db.models().find(unrelated.sha256))
        var sourceOpens = 0
        for ((index, state) in listOf("IMPORTED", "VALIDATING").withIndex()) {
            val bytes = model(context = 2048L + index)
            val expected = expected(bytes)
            try {
                files.importModel(expected, { sourceOpens++; ByteArrayInputStream(bytes) }) {
                    if (db.models().find(expected.sha256) != null) throw CancellationException("late import checkpoint")
                }
                fail("Cancellation swallowed")
            } catch (_: CancellationException) { }
            if (state == "VALIDATING") assertEquals(1, db.models().beginValidation(expected.sha256, 0))
            assertEquals(state, db.models().find(expected.sha256)?.status)
            assertTrue(files.recoverSelected(expected.sha256))
            assertEquals("READY", db.models().find(expected.sha256)?.status)
            assertEquals(unchanged, db.models().find(unrelated.sha256))
        }
        assertEquals(2, sourceOpens)
        assertFalse(files.recoverSelected("missing"))
        assertTrue(files.recoverSelected(unrelated.sha256))
        assertEquals(unchanged, db.models().find(unrelated.sha256))
    }

    @Test fun missingPendingAndMissingReadyRowsBecomeQuarantined() = fixture { db, files, root ->
        val missingHash = "e".repeat(64)
        db.models().registerImported(imported(missingHash, 16))
        val bytes = model()
        val expected = expected(bytes)
        files.importModel(expected, { ByteArrayInputStream(bytes) })
        assertTrue(File(root, "orion_model_files/verified/${expected.sha256}.gguf").delete())
        val result = files.recoverPage()
        assertEquals(2, result.invalidated)
        assertEquals("QUARANTINED", db.models().find(missingHash)?.status)
        assertEquals("QUARANTINED", db.models().find(expected.sha256)?.status)
    }

    @Test fun alteredReadyFileIsNeverDeliveredAndNotSilentlyRepaired() = fixture { db, files, root ->
        val bytes = model()
        val expected = expected(bytes)
        files.importModel(expected, { ByteArrayInputStream(bytes) })
        val path = File(root, "orion_model_files/verified/${expected.sha256}.gguf")
        path.writeBytes(bytes.copyOf().also { it[it.lastIndex] = 1 })
        assertNull(files.openReadOnly(expected.sha256))
        assertEquals("QUARANTINED", db.models().find(expected.sha256)?.status)
        val outcome = files.importModel(expected, { error("Must not silently repair") })
        assertEquals(OrionModelImportResult.Rejected(OrionModelImportBlock.MODEL_UNAVAILABLE), outcome)
        assertEquals(0, files.cleanupUnregisteredFiles().deleted)
        assertTrue(path.exists())
    }

    @Test fun temporarilyUnreadableReadyFilePreservesRegistryAndRecoversAfterAccessReturns() = fixture { db, files, root ->
        val bytes = model()
        val expected = expected(bytes)
        files.importModel(expected, { ByteArrayInputStream(bytes) })
        val before = checkNotNull(db.models().find(expected.sha256))
        val path = File(root, "orion_model_files/verified/${expected.sha256}.gguf")
        Os.chmod(path.path, 0)
        try {
            assertNull(files.openReadOnly(expected.sha256))
            assertEquals(before, db.models().find(expected.sha256))
            val pass = files.recoverPage()
            assertEquals(0, pass.invalidated)
            assertEquals(1, pass.conflicts)
            assertEquals(before, db.models().find(expected.sha256))
        } finally { Os.chmod(path.path, 384) } // Owner read/write, no public access.
        files.openReadOnly(expected.sha256)?.close() ?: fail("Recovered access was unavailable")
        assertEquals(before, db.models().find(expected.sha256))
    }

    @Test fun badModelAndForgedPrivatePathNeverReachReady() = fixture { db, files, _ ->
        val bad = "not a GGUF".toByteArray()
        val outcome = files.importModel(expected(bad), { ByteArrayInputStream(bad) })
        assertEquals(OrionModelImportResult.Rejected(OrionModelImportBlock.FILE_REJECTED,
            OrionModelFileFailure.FORMAT_INVALID), outcome)
        assertEquals(0, db.models().countAll())
        val hash = "d".repeat(64)
        db.models().registerImported(imported(hash, 16).copy(filePath = "/outside/model.gguf"))
        assertEquals(1, files.recoverPage().invalidated)
        assertEquals("QUARANTINED", db.models().find(hash)?.status)
    }

    @Test fun registryCapacityRejectsNewInputButPreservesExistingReadyModel() = fixture { db, files, _ ->
        val bytes = model()
        val expected = expected(bytes)
        files.importModel(expected, { ByteArrayInputStream(bytes) })
        repeat(127) { index -> db.models().registerImported(imported("%064x".format(index + 1), 16)) }
        assertEquals(128, db.models().countAll())
        val newBytes = model(context = 4096)
        assertEquals(OrionModelImportResult.Rejected(OrionModelImportBlock.REGISTRY_CAPACITY),
            files.importModel(expected(newBytes), { error("At-capacity source opened") }))
        assertTrue((files.importModel(expected, { error("Duplicate source opened") }) as OrionModelImportResult.Ready).reused)
    }

    private fun expected(bytes: ByteArray) = OrionExpectedModelFile(
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size.toLong())
    private fun imported(hash: String, size: Long) = ModelRegistryEntity(hash, "Fixture", "verified/$hash.gguf",
        hash, size, null, null, null, "IMPORTED", 100, null)

    private fun model(context: Long = 2048): ByteArray {
        val out = ByteArrayOutputStream()
        fun number(value: Long, size: Int) { repeat(size) { out.write((value ushr (it * 8)).toInt() and 255) } }
        fun text(value: String) { val bytes = value.toByteArray(); number(bytes.size.toLong(), 8); out.write(bytes) }
        number(0x46554747, 4); number(3, 4); number(1, 8); number(2, 8)
        text("general.architecture"); number(8, 4); text("llama")
        text("llama.context_length"); number(4, 4); number(context, 4)
        text("weight"); number(1, 4); number(4, 8); number(0, 4); number(0, 8)
        while (out.size() % 32 != 0) out.write(0)
        out.write(ByteArray(16))
        return out.toByteArray()
    }

    private fun fixture(block: (OrionDatabase, OrionModelImportCoordinator, File) -> Unit) {
        val id = UUID.randomUUID().toString()
        val root = File(app.noBackupFilesDir, "orion_model_test_$id").also { check(it.mkdirs()) }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val name = "orion_model_import_$id.db"
        try {
            val db = Room.databaseBuilder(app, OrionDatabase::class.java, name).build()
            try {
                val files = OrionModelImportCoordinator.open(context, db, WallClock { Instant.ofEpochMilli(200) },
                    maximumModelBytes = 1024 * 1024, reserveBytes = 100)
                block(db, files, root)
            } finally { db.close() }
        } finally { app.deleteDatabase(name); root.deleteRecursively() }
    }
}
