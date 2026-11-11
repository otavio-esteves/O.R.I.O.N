package network.orion.data.repository.models

import android.app.Application
import android.content.Context
import android.os.UserManager
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import network.orion.core.common.process.MainProcessStoragePolicy
import network.orion.core.common.process.OrionProcessRole

/** Expected identity comes from trusted configuration, never from a claimed filename. */
data class OrionExpectedModelFile(val sha256: String, val sizeBytes: Long) {
    init {
        require(sha256.matches(Regex("[0-9a-f]{64}")) && sha256.any { it != '0' })
        require(sizeBytes > 0)
    }
}

/** Validation of model structure is mandatory and separate from digest validation. */
fun interface OrionModelFileValidator { fun validate(file: File): Boolean }

class OrionVerifiedModelFile internal constructor(val sha256: String, val sizeBytes: Long) {
    val privateFileName: String get() = "$sha256.gguf"
    override fun toString() = "OrionVerifiedModelFile(<redacted>)"
}

enum class OrionModelFileFailure {
    LIMIT_EXCEEDED, LOW_SPACE, STORAGE_UNAVAILABLE, SIZE_MISMATCH, HASH_MISMATCH, FORMAT_INVALID, UNSAFE_PATH,
}

sealed interface OrionModelFileImport {
    class Verified(val file: OrionVerifiedModelFile, val reused: Boolean) : OrionModelFileImport
    data class Rejected(val reason: OrionModelFileFailure) : OrionModelFileImport
}

data class OrionModelOrphanCleanup(val deleted: Int, val blocked: Int, val needsContinuation: Boolean)

internal class OrionOpenedModel(val descriptor: ParcelFileDescriptor, val metadata: OrionGgufMetadata)

internal sealed interface OrionModelFileRead {
    class Verified(val opened: OrionOpenedModel) : OrionModelFileRead
    data object Invalid : OrionModelFileRead
    data object StorageUnavailable : OrionModelFileRead
}

/**
 * Synchronous MAIN-owned file staging. No database READY transition or loading authority.
 * Process death can leave a .part or a verified unregistered file; the future registry
 * coordinator must reconcile both. A receipt is not durable proof after later tampering.
 */
class OrionPrivateModelFiles internal constructor(
    private val root: File,
    private val maximumModelBytes: Long,
    private val reserveBytes: Long,
    private val validator: OrionModelFileValidator,
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    init { require(maximumModelBytes > 0 && reserveBytes >= 0) }

    fun stage(expected: OrionExpectedModelFile, source: () -> InputStream,
        checkCancellation: () -> Unit = {}): OrionModelFileImport = synchronized(lock) {
        if (expected.sizeBytes > maximumModelBytes) return@synchronized rejected(OrionModelFileFailure.LIMIT_EXCEEDED)
        var temporary: File? = null
        try {
            directories()
            checkCancelled(checkCancellation)
            val target = File(ready, "${expected.sha256}.gguf")
            if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target.toPath()) || !Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS))
                    return@synchronized rejected(OrionModelFileFailure.UNSAFE_PATH)
                val failure = verify(target, expected, checkCancellation)
                return@synchronized if (failure == null) verified(expected, true) else rejected(failure)
            }
            if (count(ready) >= MAX_READY_FILES || count(staging) >= MAX_STAGING_FILES)
                return@synchronized rejected(OrionModelFileFailure.LIMIT_EXCEEDED)
            val free = availableBytes(root)
            if (free < reserveBytes || free - reserveBytes < expected.sizeBytes)
                return@synchronized rejected(OrionModelFileFailure.LOW_SPACE)
            val temp = File(staging, "${UUID.randomUUID()}.part").also { temporary = it }
            var copied = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            var tooLarge = false
            FileChannel.open(temp.toPath(), WRITE, CREATE_NEW, NOFOLLOW_LINKS).use { output ->
                source().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        checkCancelled(checkCancellation)
                        val remaining = expected.sizeBytes - copied
                        // One extra byte detects oversized input without copying arbitrary extra content.
                        val wanted = if (remaining >= buffer.size) buffer.size else remaining.toInt() + 1
                        val n = input.read(buffer, 0, wanted)
                        if (n < 0) break
                        if (n == 0) throw IOException("Model source made no progress.")
                        if (n.toLong() > remaining) { tooLarge = true; break }
                        digest.update(buffer, 0, n)
                        val bytes = ByteBuffer.wrap(buffer, 0, n)
                        while (bytes.hasRemaining()) output.write(bytes)
                        copied += n
                    }
                }
                output.force(true)
            }
            checkCancelled(checkCancellation)
            val failure = when {
                tooLarge || copied != expected.sizeBytes -> OrionModelFileFailure.SIZE_MISMATCH
                hex(digest.digest()) != expected.sha256 -> OrionModelFileFailure.HASH_MISMATCH
                !validator.validate(temp) -> OrionModelFileFailure.FORMAT_INVALID
                else -> null
            }
            if (failure != null) {
                quarantine(temp)
                return@synchronized rejected(failure)
            }
            checkCancelled(checkCancellation)
            // Same filesystem only. Lack of atomic-rename support fails closed, without copy fallback.
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE)
            temporary = null
            verified(expected, false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (interrupted: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: UnsafeModelPath) {
            rejected(OrionModelFileFailure.UNSAFE_PATH)
        } catch (_: IOException) {
            rejected(OrionModelFileFailure.STORAGE_UNAVAILABLE)
        } finally {
            // A failed cleanup leaves a recognizable orphan; never mask cancellation.
            temporary?.let { try { Files.deleteIfExists(it.toPath()) } catch (_: IOException) { } }
        }
    }

    /** Only recognized staging files are removed. Never follows links or removes registered content. */
    fun cleanupInterruptedBatch(limit: Int = 25, checkCancellation: () -> Unit = {}): OrionModelOrphanCleanup = synchronized(lock) {
        checkCancelled(checkCancellation)
        require(limit in 1..MAX_STAGING_FILES)
        try {
            directories()
            var deleted = 0
            var blocked = 0
            var inspected = 0
            val continuation: Boolean
            Files.newDirectoryStream(staging.toPath()).use { entries ->
                val iterator = entries.iterator()
                while (iterator.hasNext() && inspected < limit) {
                    checkCancelled(checkCancellation)
                    val path = iterator.next()
                    inspected++
                    val name = path.fileName.toString()
                    val id = name.removeSuffix(".part")
                    val known = name.endsWith(".part") && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
                    if (!known || !Files.isRegularFile(path, NOFOLLOW_LINKS)) blocked++
                    else try { checkCancelled(checkCancellation); Files.delete(path); deleted++ } catch (_: IOException) { blocked++ }
                }
                continuation = iterator.hasNext() && blocked == 0
            }
            OrionModelOrphanCleanup(deleted, blocked, continuation)
        } catch (_: IOException) { OrionModelOrphanCleanup(0, 1, false) }
    }

    /** Revalidate the exact opened inode, never a caller-supplied path or a prior receipt alone. */
    internal fun openVerified(expected: OrionExpectedModelFile, parser: OrionGgufValidator,
        checkCancellation: () -> Unit = {}): OrionModelFileRead = synchronized(lock) {
        checkCancelled(checkCancellation)
        if (expected.sizeBytes > maximumModelBytes) return@synchronized OrionModelFileRead.Invalid
        var owned: ParcelFileDescriptor? = null
        try {
            directories()
            val target = File(ready, "${expected.sha256}.gguf")
            val raw = Os.open(target.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
            val descriptor = try { ParcelFileDescriptor.dup(raw) } finally { Os.close(raw) }
            owned = descriptor
            val stat = Os.fstat(descriptor.fileDescriptor)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != expected.sizeBytes) return@synchronized OrionModelFileRead.Invalid
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    checkCancelled(checkCancellation)
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) return@synchronized OrionModelFileRead.StorageUnavailable
                    if (n.toLong() > expected.sizeBytes - copied) return@synchronized OrionModelFileRead.Invalid
                    digest.update(buffer, 0, n); copied += n
                }
            }
            checkCancelled(checkCancellation)
            if (copied != expected.sizeBytes || hex(digest.digest()) != expected.sha256) return@synchronized OrionModelFileRead.Invalid
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
            val metadata = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use {
                parser.inspect(object : FilterInputStream(it) {
                    override fun read(): Int { checkCancelled(checkCancellation); return super.read() }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        checkCancelled(checkCancellation)
                        return super.read(bytes, offset, length)
                    }
                }, expected.sizeBytes)
            } ?: return@synchronized OrionModelFileRead.Invalid
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
            checkCancelled(checkCancellation)
            owned = null
            OrionModelFileRead.Verified(OrionOpenedModel(descriptor, metadata))
        } catch (interrupted: InterruptedIOException) {
            Thread.currentThread().interrupt(); throw interrupted
        } catch (_: UnsafeModelPath) { OrionModelFileRead.Invalid }
          catch (_: IOException) { OrionModelFileRead.StorageUnavailable }
          catch (failure: ErrnoException) {
            if (failure.errno in setOf(OsConstants.ENOENT, OsConstants.ENOTDIR, OsConstants.ELOOP)) OrionModelFileRead.Invalid
            else OrionModelFileRead.StorageUnavailable
          }
        finally { try { owned?.close() } catch (_: IOException) { } }
    }

    /** Cursor prevents unknown entries from starving bounded reconciliation. Only recognized files are candidates. */
    internal fun reconcileUnregisteredBatch(registered: Set<String>, after: String?, limit: Int,
        checkCancellation: () -> Unit = {}): Pair<OrionModelOrphanCleanup, String?> = synchronized(lock) {
        checkCancelled(checkCancellation)
        require(limit in 1..100)
        try {
            directories()
            // Normal admission caps this directory at 32; fail closed for unexpected excess.
            val names = mutableListOf<String>()
            Files.newDirectoryStream(ready.toPath()).use { entries ->
                for (entry in entries) {
                    checkCancelled(checkCancellation)
                    if (names.size >= MAX_STAGING_FILES) return@synchronized OrionModelOrphanCleanup(0, 1, false) to null
                    names.add(entry.fileName.toString())
                }
            }
            val page = names.sorted().filter { after == null || it > after }.take(limit)
            var deleted = 0
            var blocked = 0
            for (name in page) {
                checkCancelled(checkCancellation)
                val path = File(ready, name).toPath()
                val hash = name.removeSuffix(".gguf")
                if (!name.matches(Regex("[0-9a-f]{64}\\.gguf")) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) blocked++
                else if (hash !in registered) try { checkCancelled(checkCancellation); Files.delete(path); deleted++ } catch (_: IOException) { blocked++ }
            }
            val next = if (page.size == limit) page.last() else null
            OrionModelOrphanCleanup(deleted, blocked, next != null) to next
        } catch (_: IOException) { OrionModelOrphanCleanup(0, 1, false) to null }
    }

    private val ready get() = File(root, "verified")
    private val staging get() = File(root, "staging")
    private val quarantined get() = File(root, "quarantine")

    private fun directories() {
        for (dir in listOf(root, ready, staging, quarantined)) {
            var ancestor: File? = dir.absoluteFile
            while (ancestor != null) {
                if (Files.isSymbolicLink(ancestor.toPath())) throw UnsafeModelPath()
                ancestor = ancestor.parentFile
            }
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Model storage unavailable.")
        }
    }

    private fun verify(file: File, expected: OrionExpectedModelFile, checkCancellation: () -> Unit): OrionModelFileFailure? {
        if (file.length() != expected.sizeBytes) return OrionModelFileFailure.SIZE_MISMATCH
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            var copied = 0L
            while (true) {
                checkCancelled(checkCancellation)
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) throw IOException("Model source made no progress.")
                if (n.toLong() > expected.sizeBytes - copied) return OrionModelFileFailure.SIZE_MISMATCH
                digest.update(buffer, 0, n)
                copied += n
            }
            if (copied != expected.sizeBytes) return OrionModelFileFailure.SIZE_MISMATCH
        }
        if (hex(digest.digest()) != expected.sha256) return OrionModelFileFailure.HASH_MISMATCH
        checkCancelled(checkCancellation)
        return if (validator.validate(file)) null else OrionModelFileFailure.FORMAT_INVALID
    }

    /** Bound retained invalid bytes and count; excess invalid input is discarded, never published. */
    private fun quarantine(file: File) {
        if (file.length() <= MAX_QUARANTINE_BYTES && count(quarantined) < MAX_QUARANTINE_FILES)
            Files.move(file.toPath(), File(quarantined, "${UUID.randomUUID()}.invalid").toPath(), ATOMIC_MOVE)
    }

    private fun count(directory: File): Int {
        var size = 0
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (ignored in entries) { size++; if (size >= MAX_STAGING_FILES) break }
        }
        return size
    }

    private fun rejected(reason: OrionModelFileFailure) = OrionModelFileImport.Rejected(reason)
    private fun verified(expected: OrionExpectedModelFile, reused: Boolean) =
        OrionModelFileImport.Verified(OrionVerifiedModelFile(expected.sha256, expected.sizeBytes), reused)

    private class UnsafeModelPath : IOException()

    private fun checkCancelled(checkCancellation: () -> Unit) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Model copy interrupted.")
        checkCancellation()
    }

    companion object {
        private val lock = Any()
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_READY_FILES = 32
        private const val MAX_STAGING_FILES = 100
        private const val MAX_QUARANTINE_FILES = 4
        private const val MAX_QUARANTINE_BYTES = 1024 * 1024L
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        fun open(context: Context, maximumModelBytes: Long, reserveBytes: Long,
            validator: OrionModelFileValidator): OrionPrivateModelFiles {
            val app = context.applicationContext
            MainProcessStoragePolicy.requireAccess(
                OrionProcessRole.fromProcessName(app.packageName, Application.getProcessName()),
                app.getSystemService(UserManager::class.java).isUserUnlocked, context.isDeviceProtectedStorage)
            // Framework credential-storage paths can contain platform aliases (for
            // example /data/user/0). Normalize only that trusted base. Resolving the
            // appended store path would conceal an attacker-controlled child link.
            val frameworkBase = app.noBackupFilesDir.canonicalFile
            return OrionPrivateModelFiles(File(frameworkBase, "orion_model_files"), maximumModelBytes, reserveBytes, validator)
        }
    }
}
