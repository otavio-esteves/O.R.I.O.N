package network.orion.data.modelregistry

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/** Storage safety bounds, not a qualified model tier or a hardware memory budget. */
data class OrionModelImportLimits(val maximumModelBytes: Long = 8L * 1024 * 1024 * 1024,
    val diskReserveBytes: Long = 256L * 1024 * 1024, val maximumRegistryEntries: Int = 16) {
    init { require(maximumModelBytes >= 32 && diskReserveBytes > 0 && maximumRegistryEntries in 1..64) }
}

internal enum class ModelArtifactFailure { SIZE_MISMATCH, HASH_MISMATCH, NO_SPACE, UNSAFE_PATH, CAPACITY }
internal class ModelArtifactException(val reason: ModelArtifactFailure) : IOException(reason.name)

/** Private immutable content-addressed files. This class never opens Room or loads native code. */
internal class OrionModelArtifactStore(val directory: File, val limits: OrionModelImportLimits,
    private val availableBytes: () -> Long = { directory.usableSpace },
    private val syncDirectory: (File) -> Unit = { dir -> FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } },
    private val openReadChannel: (File) -> SeekableByteChannel = { file ->
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    },
) {
    fun ensureDirectory() {
        if (directory.canonicalFile != directory.absoluteFile || Files.isSymbolicLink(directory.toPath())) unsafe()
        val existed = directory.exists()
        Files.createDirectories(directory.toPath())
        if (!directory.isDirectory || directory.canonicalFile != directory.absoluteFile) unsafe()
        if (!existed) {
            syncDirectory(directory)
            syncDirectory(requireNotNull(directory.parentFile) { "Model parent directory required." })
        }
    }

    fun file(sha256: String): File {
        require(SHA.matches(sha256))
        return checkedFile("$sha256.gguf")
    }

    fun temporary(token: String): File {
        require(UUID.fromString(token).toString() == token)
        return checkedFile("$token.part")
    }

    fun preflight(expectedBytes: Long) {
        ensureDirectory()
        if (expectedBytes !in 32..limits.maximumModelBytes) throw ModelArtifactException(ModelArtifactFailure.SIZE_MISMATCH)
        val space = availableBytes()
        if (space < limits.diskReserveBytes || expectedBytes > space - limits.diskReserveBytes)
            throw ModelArtifactException(ModelArtifactFailure.NO_SPACE)
        if (entries().size >= limits.maximumRegistryEntries * 2 + 1) throw ModelArtifactException(ModelArtifactFailure.CAPACITY)
    }

    fun copy(input: InputStream, token: String, expectedBytes: Long, expectedSha256: String) {
        val target = temporary(token)
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                val maximumRead = minOf(buffer.size.toLong(), expectedBytes - copied + 1).toInt()
                var count = input.read(buffer, 0, maximumRead)
                if (count < 0) break
                if (count == 0) {
                    val one = input.read()
                    if (one < 0) break
                    buffer[0] = one.toByte(); count = 1
                }
                if (count.toLong() > expectedBytes - copied) throw ModelArtifactException(ModelArtifactFailure.SIZE_MISMATCH)
                copied += count
                digest.update(buffer, 0, count)
                val bytes = ByteBuffer.wrap(buffer, 0, count)
                while (bytes.hasRemaining()) output.write(bytes)
            }
            if (copied != expectedBytes) throw ModelArtifactException(ModelArtifactFailure.SIZE_MISMATCH)
            if (hex(digest.digest()) != expectedSha256) throw ModelArtifactException(ModelArtifactFailure.HASH_MISMATCH)
            output.force(true)
        }
    }

    fun validateTemporary(token: String): OrionGgufMetadata = readChannel(temporary(token)).use(OrionGgufValidator::validate)

    fun promote(token: String, sha256: String) {
        val temporary = temporary(token)
        val target = file(sha256)
        if (target.exists()) throw ModelArtifactException(ModelArtifactFailure.UNSAFE_PATH)
        // READY files have no ordinary writer. The same UID is still a shared security boundary.
        if (!temporary.setWritable(false, false)) throw IOException("Model permissions unavailable.")
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(directory)
    }

    fun validateFinal(sha256: String, expectedBytes: Long): OrionGgufMetadata =
        readChannel(file(sha256)).use { validateOpen(it, sha256, expectedBytes) }

    /** Cheap recovery observation only; actual handoff always repeats full validation on its open descriptor. */
    fun verifyFinalPresence(sha256: String, expectedBytes: Long) {
        val path = file(sha256).toPath()
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile) throw ModelArtifactException(ModelArtifactFailure.UNSAFE_PATH)
        if (attributes.size() != expectedBytes) throw ModelArtifactException(ModelArtifactFailure.SIZE_MISMATCH)
    }

    /** Validate exactly the already-open inode subsequently duplicated into Binder, never a reopened path. */
    fun validateOpen(channel: SeekableByteChannel, sha256: String, expectedBytes: Long): OrionGgufMetadata {
        if (channel.size() != expectedBytes) throw ModelArtifactException(ModelArtifactFailure.SIZE_MISMATCH)
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteBuffer.allocate(64 * 1024)
        channel.position(0)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            bytes.clear()
            val count = channel.read(bytes)
            if (count < 0) break
            if (count == 0) throw IOException("Model read unavailable.")
            digest.update(bytes.array(), 0, count)
        }
        if (hex(digest.digest()) != sha256) throw ModelArtifactException(ModelArtifactFailure.HASH_MISMATCH)
        val metadata = OrionGgufValidator.validate(channel)
        syncDirectory(directory)
        return metadata
    }

    fun discardTemporary(token: String): Boolean {
        val deleted = Files.deleteIfExists(temporary(token).toPath())
        if (deleted) syncDirectory(directory)
        return deleted
    }

    /** Names and traversal are bounded before constructing any recovery path. Unknown artifacts are preserved. */
    fun entries(): List<String> {
        ensureDirectory()
        val maximum = limits.maximumRegistryEntries * 2 + 1
        return Files.newDirectoryStream(directory.toPath()).use { stream ->
            val result = ArrayList<String>()
            val iterator = stream.iterator()
            while (iterator.hasNext() && result.size <= maximum) result += iterator.next().fileName.toString()
            if (result.size > maximum || iterator.hasNext()) throw ModelArtifactException(ModelArtifactFailure.CAPACITY)
            result
        }
    }

    fun discardOrphanTemporary(name: String): Boolean {
        if (!PART.matches(name)) return false
        val deleted = Files.deleteIfExists(checkedFile(name).toPath())
        if (deleted) syncDirectory(directory)
        return deleted
    }

    private fun readChannel(file: File): SeekableByteChannel {
        // Inspect without following links before opening: a FIFO/device must not
        // block the MAIN registry lock while waiting for an external writer.
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile) unsafe()
        return openReadChannel(file)
    }
    private fun checkedFile(name: String): File {
        ensureDirectory()
        val file = File(directory, name)
        if (file.canonicalFile.parentFile != directory.canonicalFile || Files.isSymbolicLink(file.toPath())) unsafe()
        return file
    }
    private fun unsafe(): Nothing = throw ModelArtifactException(ModelArtifactFailure.UNSAFE_PATH)

    companion object {
        val SHA = Regex("[0-9a-f]{64}")
        val PART = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.part")
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
