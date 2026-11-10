package network.orion.data.repository.models

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrionPrivateModelFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "GGUF-test-fixture".toByteArray()
    private fun expected(content: ByteArray = bytes) = OrionExpectedModelFile(
        MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }, content.size.toLong())
    private fun store(root: File, free: Long = Long.MAX_VALUE,
        validator: OrionModelFileValidator = OrionModelFileValidator { true }) =
        OrionPrivateModelFiles(root, 1024 * 1024, 100, validator) { free }
    private fun ready(root: File) = File(root, "verified").listFiles().orEmpty()
    private fun staging(root: File) = File(root, "staging").listFiles().orEmpty()
    private fun quarantine(root: File) = File(root, "quarantine").listFiles().orEmpty()

    @Test fun exactCopyIsPublishedAndDedupRevalidatesWithoutOpeningSource() {
        val root = temporary.newFolder()
        var validations = 0
        val files = store(root, validator = OrionModelFileValidator { validations++; it.readBytes().contentEquals(bytes) })
        val first = files.stage(expected(), { ByteArrayInputStream(bytes) }) as OrionModelFileImport.Verified
        assertFalse(first.reused)
        assertEquals(expected().sha256 + ".gguf", first.file.privateFileName)
        assertArrayEquals(bytes, ready(root).single().readBytes())
        assertTrue(staging(root).isEmpty())
        val second = files.stage(expected(), { error("Duplicate source opened") }) as OrionModelFileImport.Verified
        assertTrue(second.reused)
        assertEquals(2, validations)
    }

    @Test fun tamperedExistingCopyIsRejectedAndNeverOverwritten() {
        val root = temporary.newFolder()
        val files = store(root)
        files.stage(expected(), { ByteArrayInputStream(bytes) })
        val corrupt = bytes.copyOf().also { it[0] = 0 }
        ready(root).single().writeBytes(corrupt)
        assertEquals(OrionModelFileImport.Rejected(OrionModelFileFailure.HASH_MISMATCH),
            files.stage(expected(), { error("Must not overwrite") }))
        assertArrayEquals(corrupt, ready(root).single().readBytes())
    }

    @Test fun lowSpaceDoesNotOpenSourceAndCannotOverflowArithmetic() {
        val root = temporary.newFolder()
        assertEquals(OrionModelFileImport.Rejected(OrionModelFileFailure.LOW_SPACE),
            store(root, free = bytes.size.toLong() + 99).stage(expected(), { error("Source opened") }))
        val files = OrionPrivateModelFiles(root, Long.MAX_VALUE, Long.MAX_VALUE, OrionModelFileValidator { true }) { Long.MAX_VALUE }
        assertEquals(OrionModelFileImport.Rejected(OrionModelFileFailure.LOW_SPACE),
            files.stage(OrionExpectedModelFile(expected().sha256, Long.MAX_VALUE), { error("Source opened") }))
        assertTrue(ready(root).isEmpty())
    }

    @Test fun truncatedAndOversizedCopiesNeverPublish() {
        for (content in listOf(bytes.dropLast(1).toByteArray(), bytes + 1.toByte())) {
            val root = temporary.newFolder()
            assertEquals(OrionModelFileImport.Rejected(OrionModelFileFailure.SIZE_MISMATCH),
                store(root).stage(expected(), { ByteArrayInputStream(content) }))
            assertTrue(ready(root).isEmpty())
            assertTrue(staging(root).isEmpty())
            assertEquals(1, quarantine(root).size)
        }
    }

    @Test fun digestMismatchIsQuarantinedAndFormatValidatorIsNotCalled() {
        val root = temporary.newFolder()
        val wrong = bytes.copyOf().also { it[0] = 0 }
        val files = store(root, validator = OrionModelFileValidator { error("Invalid digest reached parser") })
        assertEquals(OrionModelFileImport.Rejected(OrionModelFileFailure.HASH_MISMATCH),
            files.stage(expected(), { ByteArrayInputStream(wrong) }))
        assertEquals(1, quarantine(root).size)
        assertTrue(ready(root).isEmpty())
    }

    @Test fun formatFailureIsNeverAReceiptAndQuarantineCountIsBounded() {
        val root = temporary.newFolder()
        val files = store(root, validator = OrionModelFileValidator { false })
        repeat(7) {
            assertEquals(OrionModelFileImport.Rejected(OrionModelFileFailure.FORMAT_INVALID),
                files.stage(expected(), { ByteArrayInputStream(bytes) }))
        }
        assertEquals(4, quarantine(root).size)
        assertTrue(staging(root).isEmpty())
        assertTrue(ready(root).isEmpty())
    }

    @Test fun sourceFailureClosesSourceAndLeavesNoPublishedPartial() {
        val root = temporary.newFolder()
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("synthetic interrupted source")
            override fun close() { closed = true }
        }
        assertEquals(OrionModelFileImport.Rejected(OrionModelFileFailure.STORAGE_UNAVAILABLE),
            store(root).stage(expected(), { input }))
        assertTrue(closed)
        assertTrue(ready(root).isEmpty())
        assertTrue(staging(root).isEmpty())
    }

    @Test fun cancellationDuringCopyPropagatesAndCleansTemporary() {
        val root = temporary.newFolder()
        var checks = 0
        val cancellation = CancellationException("synthetic")
        try {
            store(root).stage(expected(), { ByteArrayInputStream(bytes) }) {
                if (++checks == 3) throw cancellation
            }
            fail("Cancellation swallowed")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertTrue(ready(root).isEmpty())
        assertTrue(staging(root).isEmpty())
    }

    @Test fun interruptedValidatorRestoresThreadFlagAndDoesNotPublish() {
        val root = temporary.newFolder()
        try {
            store(root, validator = OrionModelFileValidator { throw InterruptedException("synthetic") })
                .stage(expected(), { ByteArrayInputStream(bytes) })
            fail("Interruption swallowed")
        } catch (_: InterruptedException) { assertTrue(Thread.currentThread().isInterrupted) }
        finally { Thread.interrupted() }
        assertTrue(ready(root).isEmpty())
    }

    @Test fun orphanCleanupContinuesInBoundedBatchesAndPreservesVerifiedFiles() {
        val root = temporary.newFolder()
        val files = store(root)
        files.stage(expected(), { ByteArrayInputStream(bytes) })
        repeat(3) { File(root, "staging/${UUID.randomUUID()}.part").writeBytes(bytes) }
        assertEquals(OrionModelOrphanCleanup(2, 0, true), files.cleanupInterruptedBatch(2))
        assertEquals(OrionModelOrphanCleanup(1, 0, false), files.cleanupInterruptedBatch(2))
        assertEquals(1, ready(root).size)
    }

    @Test fun symbolicRootAndTargetAreRejectedWithoutFollowingLinks() {
        val outside = temporary.newFolder()
        val root = File(temporary.newFolder(), "linked")
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        assertEquals(OrionModelFileImport.Rejected(OrionModelFileFailure.UNSAFE_PATH),
            store(root).stage(expected(), { error("Source opened") }))
        assertTrue(outside.listFiles().orEmpty().isEmpty())
        val second = temporary.newFolder()
        val files = store(second)
        files.cleanupInterruptedBatch()
        val victim = File(outside, "victim").also { it.writeBytes(bytes) }
        Files.createSymbolicLink(File(second, "verified/${expected().sha256}.gguf").toPath(), victim.toPath())
        assertEquals(OrionModelFileImport.Rejected(OrionModelFileFailure.UNSAFE_PATH),
            files.stage(expected(), { error("Source opened") }))
        assertArrayEquals(bytes, victim.readBytes())
    }

    @Test fun orphanCleanupReportsSymlinkAndUnknownFilesWithoutDeletingTargets() {
        val root = temporary.newFolder()
        val files = store(root)
        files.cleanupInterruptedBatch()
        val victim = temporary.newFile().also { it.writeBytes(bytes) }
        Files.createSymbolicLink(File(root, "staging/${UUID.randomUUID()}.part").toPath(), victim.toPath())
        File(root, "staging/unrecognized").writeBytes(bytes)
        assertEquals(OrionModelOrphanCleanup(0, 2, false), files.cleanupInterruptedBatch())
        assertArrayEquals(bytes, victim.readBytes())
    }

    @Test fun invalidIdentityAndOversizedPlanRejectBeforeInput() {
        for (hash in listOf("0".repeat(64), "a".repeat(63), "../" + "a".repeat(61), "A".repeat(64))) {
            try { OrionExpectedModelFile(hash, 1); fail("Invalid digest accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(OrionModelFileImport.Rejected(OrionModelFileFailure.LIMIT_EXCEEDED),
            store(temporary.newFolder()).stage(OrionExpectedModelFile(expected().sha256, 1024 * 1024 + 1), { error("Source opened") }))
    }
}
