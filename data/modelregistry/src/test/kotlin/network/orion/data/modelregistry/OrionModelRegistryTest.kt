package network.orion.data.modelregistry

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import network.orion.core.time.WallClock
import network.orion.data.database.ModelRegistryEntity
import network.orion.data.database.ModelRegistryStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrionModelRegistryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val clock = WallClock { Instant.ofEpochMilli(1_000) }
    private class ProcessDeath : Error()
    private class Index : OrionModelRegistryIndex {
        val rows = linkedMapOf<String, ModelRegistryEntity>()
        var crashAt: ModelRegistryStatus? = null
        override fun find(modelId: String) = rows[modelId]
        override fun list(limit: Int) = rows.values.sortedBy { it.modelId }.take(limit)
        override fun insertIfCapacity(row: ModelRegistryEntity, maximum: Int): Boolean {
            if (rows.size >= maximum || rows.containsKey(row.modelId)) return false
            rows[row.modelId] = row; return true
        }
        override fun transition(row: ModelRegistryEntity, next: ModelRegistryStatus, at: Long,
            metadata: OrionGgufMetadata?, error: String?): Boolean {
            if (crashAt == next) throw ProcessDeath()
            if (rows[row.modelId] != row) return false
            rows[row.modelId] = row.copy(status = next, updatedAt = at, version = row.version + 1,
                architecture = metadata?.architecture, quantization = metadata?.quantization,
                contextLimit = metadata?.contextLimit, lastValidatedAt = if (next == ModelRegistryStatus.READY) at else null,
                lastErrorCode = error)
            return true
        }
    }
    private data class Fixture(val index: Index, val files: OrionModelArtifactStore, val registry: OrionModelRegistry)
    private fun fixture(space: Long = Long.MAX_VALUE, maximum: Int = 16): Fixture {
        val index = Index()
        val files = OrionModelArtifactStore(File(temporary.root, UUID.randomUUID().toString()),
            OrionModelImportLimits(1_048_576, 64, maximum), { space })
        return Fixture(index, files, OrionModelRegistry(index, files, clock))
    }
    private fun sha(bytes: ByteArray) = OrionModelArtifactStore.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun import(fixture: Fixture, bytes: ByteArray = GgufFixtures.valid()) =
        fixture.registry.importModel(ByteArrayInputStream(bytes), bytes.size.toLong(), sha(bytes))

    @Test fun `only complete hash matched validated atomic artifact becomes ready and deduplicates`() {
        val f = fixture(); val bytes = GgufFixtures.valid(); val digest = sha(bytes)
        val result = import(f) as OrionModelImportResult.Ready
        assertFalse(result.deduplicated); assertEquals("sha256:$digest", result.model.modelId)
        val row = f.index.find(result.model.modelId)!!
        assertEquals(ModelRegistryStatus.READY, row.status); assertEquals(2L, row.version)
        assertEquals(listOf("$digest.gguf"), f.files.entries())
        assertArrayEquals(bytes, f.files.file(digest).readBytes())
        assertTrue((import(f) as OrionModelImportResult.Ready).deduplicated)
        assertEquals(1, f.index.rows.size)
        assertEquals(OrionModelRegistryFailure.CONFLICT, (f.registry.importModel(ByteArrayInputStream(bytes),
            bytes.size + 1L, digest) as OrionModelImportResult.Blocked).reason)
    }
    @Test fun `hash and declared size mismatch never publish ready or leave partial bytes`() {
        for ((size, digest) in listOf(GgufFixtures.valid().size.toLong() to "0".repeat(64),
            GgufFixtures.valid().size + 1L to sha(GgufFixtures.valid()))) {
            val f = fixture(); val bytes = GgufFixtures.valid()
            assertTrue(f.registry.importModel(ByteArrayInputStream(bytes), size, digest) is OrionModelImportResult.Blocked)
            assertEquals(ModelRegistryStatus.INVALID, f.index.rows.values.single().status)
            assertTrue(f.files.entries().isEmpty())
        }
    }
    @Test fun `truncated GGUF with correct hash is invalid rather than ready`() {
        val f = fixture(); val bytes = GgufFixtures.valid().dropLast(1).toByteArray()
        assertEquals(OrionModelRegistryFailure.INVALID_GGUF, (import(f, bytes) as OrionModelImportResult.Blocked).reason)
        assertEquals(ModelRegistryStatus.INVALID, f.index.rows.values.single().status)
        assertTrue(f.files.entries().isEmpty())
    }
    @Test fun `disk and registry capacity reject before copying and close rejected streams`() {
        val f = fixture(space = 65)
        var closed = false
        val source = object : ByteArrayInputStream(GgufFixtures.valid()) { override fun close() { closed = true; super.close() } }
        val result = f.registry.importModel(source, GgufFixtures.valid().size.toLong(), sha(GgufFixtures.valid()))
        assertEquals(OrionModelRegistryFailure.NO_SPACE, (result as OrionModelImportResult.Blocked).reason)
        assertTrue(closed); assertTrue(f.index.rows.isEmpty()); assertTrue(f.files.entries().isEmpty())
        val capped = fixture(maximum = 1); import(capped)
        val other = GgufFixtures.valid(metadata = listOf("general.name" to { u32(8); string("other") }))
        assertEquals(OrionModelRegistryFailure.CAPACITY, (import(capped, other) as OrionModelImportResult.Blocked).reason)
        assertEquals(1, capped.index.rows.size)
    }
    @Test fun `interrupted stream preserves the import journal for later recovery`() {
        val f = fixture()
        val broken = object : InputStream() { override fun read(): Int = throw InterruptedIOException() }
        assertThrows(InterruptedIOException::class.java) { f.registry.importModel(broken, 256, "0".repeat(64)) }
        assertEquals(ModelRegistryStatus.IMPORTED, f.index.rows.values.single().status)
        assertEquals(1, f.files.entries().size)
        assertEquals(1, f.registry.recover().quarantined)
        assertTrue(f.files.entries().isEmpty())
    }
    @Test fun `interrupted channel recovery preserves a valid promoted artifact and interrupt semantics`() {
        val index = Index()
        var interruptRead = false
        val files = OrionModelArtifactStore(File(temporary.root, "interrupted-channel"), OrionModelImportLimits(1_048_576, 64),
            { Long.MAX_VALUE }, {}, { file ->
                val channel = Files.newByteChannel(file.toPath(), java.nio.file.StandardOpenOption.READ)
                object : SeekableByteChannel by channel {
                    override fun read(destination: ByteBuffer): Int {
                        if (interruptRead) { channel.close(); throw ClosedByInterruptException() }
                        return channel.read(destination)
                    }
                }
            })
        val registry = OrionModelRegistry(index, files, clock)
        val bytes = GgufFixtures.valid()
        index.crashAt = ModelRegistryStatus.READY
        assertThrows(ProcessDeath::class.java) { registry.importModel(ByteArrayInputStream(bytes), bytes.size.toLong(), sha(bytes)) }
        index.crashAt = null; interruptRead = true
        try {
            assertThrows(ClosedByInterruptException::class.java) { registry.recover() }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(ModelRegistryStatus.VALIDATING, index.rows.values.single().status)
            assertTrue(files.file(sha(bytes)).exists())
        } finally { Thread.interrupted() }
        interruptRead = false
        assertEquals(1, registry.recover().recoveredReady)
        assertEquals(ModelRegistryStatus.READY, index.rows.values.single().status)
    }
    @Test fun `death before validation leaves no ready and recovery removes temporary import`() {
        val f = fixture(); f.index.crashAt = ModelRegistryStatus.VALIDATING
        assertThrows(ProcessDeath::class.java) { import(f) }
        assertEquals(ModelRegistryStatus.IMPORTED, f.index.rows.values.single().status)
        assertEquals(1, f.files.entries().size)
        f.index.crashAt = null
        val report = f.registry.recover()
        assertEquals(1, report.quarantined); assertEquals(1, report.temporaryFilesRemoved)
        assertEquals(ModelRegistryStatus.QUARANTINED, f.index.rows.values.single().status)
        assertTrue(f.files.entries().isEmpty())
    }
    @Test fun `death after rename before ready is recovered only after full file validation`() {
        val f = fixture(); f.index.crashAt = ModelRegistryStatus.READY
        assertThrows(ProcessDeath::class.java) { import(f) }
        assertEquals(ModelRegistryStatus.VALIDATING, f.index.rows.values.single().status)
        assertTrue(f.files.entries().single().endsWith(".gguf"))
        f.index.crashAt = null
        assertEquals(1, f.registry.recover().recoveredReady)
        assertEquals(ModelRegistryStatus.READY, f.index.rows.values.single().status)
        assertEquals(0, f.registry.recover().recoveredReady)
    }
    @Test fun `corrupted committed file is quarantined when reused`() {
        val f = fixture(); val bytes = GgufFixtures.valid(); import(f)
        val artifact = f.files.file(sha(bytes)); assertTrue(artifact.setWritable(true, true))
        artifact.writeBytes(bytes.copyOf().also { it[it.lastIndex] = 1 })
        assertEquals(OrionModelRegistryFailure.HASH_MISMATCH, (import(f) as OrionModelImportResult.Blocked).reason)
        assertEquals(ModelRegistryStatus.QUARANTINED, f.index.rows.values.single().status)
    }
    @Test fun `missing or wrong size ready artifact is quarantined during cheap recovery`() {
        for (missing in listOf(true, false)) {
            val f = fixture(); val bytes = GgufFixtures.valid(); import(f)
            val artifact = f.files.file(sha(bytes))
            if (missing) assertTrue(artifact.delete())
            else { assertTrue(artifact.setWritable(true, true)); artifact.writeBytes(byteArrayOf(1)) }
            assertEquals(1, f.registry.recover().quarantined)
            assertEquals(ModelRegistryStatus.QUARANTINED, f.index.rows.values.single().status)
        }
    }
    @Test fun `first directory creation promotion and cleanup flush directory metadata`() {
        val root = File(temporary.root, "durable")
        val synced = mutableListOf<File>()
        val files = OrionModelArtifactStore(root, OrionModelImportLimits(1_048_576, 64), { Long.MAX_VALUE }, { synced += it })
        files.ensureDirectory()
        assertEquals(listOf(root, root.parentFile), synced)
        val token = UUID.randomUUID().toString(); val bytes = GgufFixtures.valid()
        files.copy(ByteArrayInputStream(bytes), token, bytes.size.toLong(), sha(bytes))
        files.promote(token, sha(bytes)); assertEquals(root, synced.last())
        val orphan = File(root, "${UUID.randomUUID()}.part"); orphan.writeText("synthetic")
        val before = synced.size
        assertTrue(files.discardOrphanTemporary(orphan.name)); assertEquals(before + 1, synced.size)
        assertEquals(root, synced.last())
    }
    @Test fun `orphan temporary cleanup preserves unrelated files and refuses symlinks`() {
        val f = fixture(); f.files.ensureDirectory()
        val part = File(f.files.directory, "${UUID.randomUUID()}.part"); part.writeBytes(byteArrayOf(1))
        val unknown = File(f.files.directory, "preserved.txt"); unknown.writeText("owned elsewhere")
        val outside = temporary.newFile(); outside.writeText("preserved")
        val link = File(f.files.directory, "${UUID.randomUUID()}.part")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val report = f.registry.recover()
        assertEquals(1, report.temporaryFilesRemoved); assertEquals(2, report.blockedArtifacts)
        assertTrue(unknown.exists()); assertTrue(Files.isSymbolicLink(link.toPath())); assertEquals("preserved", outside.readText())
    }
    @Test fun `FIFO and symlink model artifacts are rejected before any channel opener`() {
        val root = File(temporary.root, "special-artifacts")
        var opened = 0
        val files = OrionModelArtifactStore(root, OrionModelImportLimits(1_048_576, 64), { Long.MAX_VALUE }, {}, {
            opened++; error("Special artifact reached channel opener.")
        })
        files.ensureDirectory()
        val fifo = files.file("a".repeat(64))
        val command = ProcessBuilder("mkfifo", fifo.path).start()
        try {
            assertTrue("Fixture FIFO creation timed out.", command.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, command.exitValue())
            assertEquals(ModelArtifactFailure.UNSAFE_PATH, assertThrows(ModelArtifactException::class.java) {
                files.validateFinal("a".repeat(64), 256)
            }.reason)
        } finally { command.destroyForcibly() }
        val target = temporary.newFile().also { it.writeBytes(GgufFixtures.valid()) }
        val link = File(root, "${"b".repeat(64)}.gguf")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        assertEquals(ModelArtifactFailure.UNSAFE_PATH, assertThrows(ModelArtifactException::class.java) {
            files.validateFinal("b".repeat(64), target.length())
        }.reason)
        assertEquals(0, opened)
    }
    @Test fun `invalid identities never become file paths and storage access is revalidated`() {
        val f = fixture()
        assertNull(f.registry.find("../weights"))
        assertEquals(OrionModelRegistryFailure.INVALID_REQUEST, (f.registry.importModel(ByteArrayInputStream(byteArrayOf()),
            256, "../../weights") as OrionModelImportResult.Blocked).reason)
        var access = true
        val guarded = OrionModelRegistry(f.index, f.files, clock) { check(access) }
        guarded.records(); access = false
        assertThrows(IllegalStateException::class.java) { guarded.recover() }
    }
}
