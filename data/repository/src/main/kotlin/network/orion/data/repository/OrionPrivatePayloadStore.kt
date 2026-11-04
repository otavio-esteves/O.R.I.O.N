package network.orion.data.repository

import android.app.Application
import android.content.Context
import android.os.UserManager
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import network.orion.core.common.process.MainProcessStoragePolicy
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.ingress.DurableIngressRequest
import network.orion.core.model.Sensitivity
import network.orion.core.security.*
import network.orion.core.time.WallClock

enum class PrivatePayloadFailure {
    PAYLOAD_TOO_LARGE, CAPACITY_EXCEEDED, STORAGE_UNAVAILABLE, UNREADABLE,
    KEY_UNAVAILABLE, KEY_INVALIDATED, EXPIRED, CLOCK_ROLLBACK, NOT_FOUND,
}

class PrivatePayloadReference internal constructor(val id: UUID, val sensitivity: Sensitivity,
    val createdAt: Long, val expiresAt: Long) {
    /** A local trusted producer supplies this request; a reference conveys no execution authority. */
    fun ingressRequest(ingressId: UUID, scope: String, key: UUID, source: String, commandType: String,
        schemaVersion: Int, lifetimeMillis: Long, retentionMillis: Long, correlationId: UUID,
        priority: Int = 0): DurableIngressRequest {
        // The store revalidates this receipt and the current remaining lifetime at enqueue.
        require(lifetimeMillis > 0 && lifetimeMillis <= expiresAt - createdAt)
        return DurableIngressRequest(ingressId, scope, key, source, commandType, schemaVersion, sensitivity,
            true, id, null, lifetimeMillis, retentionMillis, priority, correlationId)
    }
    override fun toString(): String = "PrivatePayloadReference(<redacted>)"
}

sealed interface PrivatePayloadWrite {
    data class Stored(val reference: PrivatePayloadReference) : PrivatePayloadWrite
    data class Blocked(val reason: PrivatePayloadFailure) : PrivatePayloadWrite
}

sealed interface PrivatePayloadRead {
    class Ready(bytes: ByteArray, val reference: PrivatePayloadReference) : PrivatePayloadRead {
        private val content = bytes.copyOf()
        fun copyContent(): ByteArray = content.copyOf()
        override fun toString(): String = "PrivatePayloadRead.Ready(<redacted>)"
    }
    data class Blocked(val reason: PrivatePayloadFailure) : PrivatePayloadRead
}

data class PrivatePayloadCleanup(val deleted: Int, val blocked: Int, val nextAfterId: UUID?)

/** Main-owned, synchronous IO. Content is always encrypted, including abandoned atomic writes. */
class OrionPrivatePayloadStore private constructor(private val directory: File,
    private val encryption: OrionFieldEncryption, private val clock: WallClock) {
    fun write(content: ByteArray, sensitivity: Sensitivity, lifetimeMillis: Long): PrivatePayloadWrite = synchronized(lock) {
        require(lifetimeMillis in 1..MAX_LIFETIME_MILLIS)
        if (content.size > FieldEnvelope.MAX_FIELD_BYTES) return@synchronized PrivatePayloadWrite.Blocked(PrivatePayloadFailure.PAYLOAD_TOO_LARGE)
        try {
            ensureDirectory()
            recoverUncommittedWrites()
            if (files().size >= MAX_FILES) return@synchronized PrivatePayloadWrite.Blocked(PrivatePayloadFailure.CAPACITY_EXCEEDED)
            val at = clock.wallNow().toEpochMilli()
            val ref = PrivatePayloadReference(UUID.randomUUID(), sensitivity, at, Math.addExact(at, lifetimeMillis))
            val encrypted = encryption.encrypt(content, context(ref))
            if (encrypted is FieldEncryptionResult.Failure) return@synchronized PrivatePayloadWrite.Blocked(failure(encrypted.reason))
            val bytes = encode(ref, FieldEnvelopeCodec.encode((encrypted as FieldEncryptionResult.Encrypted).envelope))
            val atomic = AtomicFile(file(ref.id))
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                stream.fd.sync()
                atomic.finishWrite(stream)
            } catch (exception: IOException) { atomic.failWrite(stream); throw exception }
            if (!atomic.readFully().contentEquals(bytes)) throw IOException("Temporary payload commit unavailable.")
            PrivatePayloadWrite.Stored(ref)
        } catch (_: IOException) { PrivatePayloadWrite.Blocked(PrivatePayloadFailure.STORAGE_UNAVAILABLE) }
    }

    fun read(id: UUID): PrivatePayloadRead = synchronized(lock) { readLocked(id, allowExpired = false) }

    /** Only a receipt whose content is still available may enter durable ingress. */
    fun enqueue(reference: PrivatePayloadReference, request: DurableIngressRequest,
        queue: OrionDurableIngressStore): network.orion.core.ingress.DurableIngressOutcome {
        require(request.referenceId == reference.id && request.sensitivity == reference.sensitivity && request.referenceSufficient)
        val current = read(reference.id)
        if (current !is PrivatePayloadRead.Ready) return network.orion.core.ingress.DurableIngressOutcome.STORAGE_UNAVAILABLE
        val now = clock.wallNow().toEpochMilli()
        require(reference.createdAt == current.reference.createdAt && reference.expiresAt == current.reference.expiresAt)
        require(request.lifetimeMillis <= reference.expiresAt - now) { "Ingress must expire before its temporary content." }
        // Never acquire SQLite while holding the file lock: local consumers read files inside their DB transaction.
        return queue.enqueueExpiringReference(request, reference.createdAt, reference.expiresAt)
    }

    /** Unreadable/clock-invalid files are preserved and reported. No retry or silent eviction. */
    fun cleanupExpiredBatch(limit: Int = 10, afterId: UUID? = null): PrivatePayloadCleanup = synchronized(lock) {
        require(limit in 1..MAX_FILES)
        try {
            ensureDirectory()
            recoverUncommittedWrites()
            val candidates = files().mapNotNull { parseId(it.name) }.distinct().sortedBy(UUID::toString)
                .filter { afterId == null || it.toString() > afterId.toString() }.take(limit)
            var deleted = 0
            var blocked = 0
            for (id in candidates) {
                val read = readLocked(id, allowExpired = true)
                if (read is PrivatePayloadRead.Ready) {
                    if (clock.wallNow().toEpochMilli() >= read.reference.expiresAt) {
                        AtomicFile(file(id)).delete()
                        if (file(id).exists()) blocked++ else deleted++
                    }
                } else blocked++
            }
            PrivatePayloadCleanup(deleted, blocked, if (candidates.size == limit) candidates.last() else null)
        } catch (_: IOException) { PrivatePayloadCleanup(0, 1, afterId) }
    }

    private fun readLocked(id: UUID, allowExpired: Boolean): PrivatePayloadRead {
        return try {
        ensureDirectory()
        val target = file(id)
        val backup = File(target.path + ".bak")
        if (!target.exists() && !backup.exists()) PrivatePayloadRead.Blocked(PrivatePayloadFailure.NOT_FOUND)
        else if (Files.isSymbolicLink(target.toPath()) || Files.isSymbolicLink(backup.toPath())) PrivatePayloadRead.Blocked(PrivatePayloadFailure.UNREADABLE)
        else {
            val bytes = AtomicFile(target).openRead().use { input ->
                if (input.channel.size() > MAX_FILE_BYTES) return PrivatePayloadRead.Blocked(PrivatePayloadFailure.UNREADABLE)
                val buffer = ByteArray(MAX_FILE_BYTES + 1)
                var used = 0
                while (used < buffer.size) {
                    val count = input.read(buffer, used, buffer.size - used)
                    if (count < 0) break
                    used += count
                }
                if (used > MAX_FILE_BYTES) return PrivatePayloadRead.Blocked(PrivatePayloadFailure.UNREADABLE)
                buffer.copyOf(used)
            }
            decode(id, bytes, allowExpired)
        }
    } catch (_: IOException) { PrivatePayloadRead.Blocked(PrivatePayloadFailure.STORAGE_UNAVAILABLE) }
      catch (_: IllegalArgumentException) { PrivatePayloadRead.Blocked(PrivatePayloadFailure.UNREADABLE) }
      catch (_: ArithmeticException) { PrivatePayloadRead.Blocked(PrivatePayloadFailure.UNREADABLE) }
    }

    private fun decode(id: UUID, bytes: ByteArray, allowExpired: Boolean): PrivatePayloadRead {
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == 1)
            val created = input.readLong()
            val expires = input.readLong()
            require(expires > created && Math.subtractExact(expires, created) <= MAX_LIFETIME_MILLIS)
            val sensitivity = Sensitivity.valueOf(input.readUTF())
            val size = input.readInt()
            require(size > 0 && size == input.available())
            val envelope = FieldEnvelopeCodec.decode(ByteArray(size).also(input::readFully))
            if (envelope !is FieldEnvelopeDecodingResult.Decoded) return PrivatePayloadRead.Blocked(PrivatePayloadFailure.UNREADABLE)
            val ref = PrivatePayloadReference(id, sensitivity, created, expires)
            when (val result = encryption.decrypt(envelope.envelope, context(ref))) {
                is FieldDecryptionResult.Failure -> PrivatePayloadRead.Blocked(failure(result.reason))
                is FieldDecryptionResult.Plaintext -> {
                    val now = clock.wallNow().toEpochMilli()
                    when {
                        now < created -> PrivatePayloadRead.Blocked(PrivatePayloadFailure.CLOCK_ROLLBACK)
                        !allowExpired && now >= expires -> PrivatePayloadRead.Blocked(PrivatePayloadFailure.EXPIRED)
                        else -> {
                            val clear = result.copyBytes()
                            try { PrivatePayloadRead.Ready(clear, ref) } finally { clear.fill(0) }
                        }
                    }
                }
            }
        }
    }

    private fun encode(ref: PrivatePayloadReference, envelope: ByteArray): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use { out ->
            out.writeInt(MAGIC); out.writeInt(1); out.writeLong(ref.createdAt); out.writeLong(ref.expiresAt)
            out.writeUTF(ref.sensitivity.name); out.writeInt(envelope.size); out.write(envelope)
        }
    }.toByteArray()
    private fun context(ref: PrivatePayloadReference) = FieldEncryptionContext("temporary/${ref.id}",
        "payload/${ref.createdAt}/${ref.expiresAt}/${ref.sensitivity.name}", 1)
    private fun file(id: UUID) = File(directory, "$id.v1")
    private fun files(): List<File> = directory.listFiles()?.toList() ?: throw IOException("Temporary storage unavailable.")
    private fun ensureDirectory() {
        if (Files.isSymbolicLink(directory.toPath())) throw IOException("Temporary storage unavailable.")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Temporary storage unavailable.")
    }
    private fun parseId(name: String): UUID? = runCatching {
        val stem = name.removeSuffix(".bak").removeSuffix(".new").removeSuffix(".v1")
        UUID.fromString(stem).takeIf { it.toString() == stem && name in setOf("$stem.v1", "$stem.v1.new", "$stem.v1.bak") }
    }.getOrNull()
    private fun recoverUncommittedWrites() {
        files().filter { it.name.endsWith(".v1.new") }.forEach { temp ->
            val base = File(temp.path.removeSuffix(".new"))
            if (!base.exists() && !File(base.path + ".bak").exists() && !temp.delete()) throw IOException("Temporary cleanup unavailable.")
        }
    }
    private fun failure(reason: FieldCryptoFailure) = when (reason) {
        FieldCryptoFailure.KEY_UNAVAILABLE -> PrivatePayloadFailure.KEY_UNAVAILABLE
        FieldCryptoFailure.KEY_INVALIDATED -> PrivatePayloadFailure.KEY_INVALIDATED
        FieldCryptoFailure.FIELD_TOO_LARGE -> PrivatePayloadFailure.PAYLOAD_TOO_LARGE
        else -> PrivatePayloadFailure.UNREADABLE
    }

    companion object {
        const val MAX_FILES = 16
        const val MAX_LIFETIME_MILLIS = 24 * 60 * 60 * 1000L
        private const val MAGIC = 0x4f545031
        private const val MAX_FILE_BYTES = FieldEnvelope.MAX_FIELD_BYTES + 4096
        private val lock = Any()
        private const val DIRECTORY = "orion_temporary_payloads"
        fun open(context: Context, encryption: OrionFieldEncryption, clock: WallClock): OrionPrivatePayloadStore {
            requireStorageAccess(context)
            val app = context.applicationContext
            return OrionPrivatePayloadStore(File(app.noBackupFilesDir, DIRECTORY), encryption, clock)
        }
        /** Do not create replacement keys over committed, pending or unknown private artifacts. */
        fun hasArtifacts(context: Context): Boolean {
            requireStorageAccess(context)
            val dir = File(context.applicationContext.noBackupFilesDir, DIRECTORY)
            return Files.isSymbolicLink(dir.toPath()) || (dir.exists() && (dir.listFiles()?.isNotEmpty() ?: true))
        }
        private fun requireStorageAccess(context: Context) {
            val app = context.applicationContext
            MainProcessStoragePolicy.requireAccess(OrionProcessRole.fromProcessName(app.packageName, Application.getProcessName()),
                app.getSystemService(UserManager::class.java).isUserUnlocked, context.isDeviceProtectedStorage)
        }
    }
}
