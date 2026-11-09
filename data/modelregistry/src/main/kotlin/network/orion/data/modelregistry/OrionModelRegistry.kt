package network.orion.data.modelregistry

import android.app.Application
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.UserManager
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import network.orion.core.common.process.MainProcessStoragePolicy
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.time.WallClock
import network.orion.data.database.ModelRegistryEntity
import network.orion.data.database.ModelRegistryStatus
import network.orion.data.database.OrionDatabase

enum class OrionModelRegistryFailure {
    INVALID_REQUEST, CONFLICT, CAPACITY, NO_SPACE, SIZE_MISMATCH, HASH_MISMATCH, INVALID_GGUF,
    STORAGE_UNAVAILABLE, NOT_READY, NOT_FOUND, UNSAFE_PATH,
}

data class OrionRegisteredModel(val modelId: String, val sha256: String, val sizeBytes: Long,
    val status: ModelRegistryStatus, val architecture: String?, val quantization: String?, val contextLimit: Int?) {
    override fun toString(): String = "OrionRegisteredModel(<redacted>)"
}

sealed interface OrionModelImportResult {
    data class Ready(val model: OrionRegisteredModel, val deduplicated: Boolean) : OrionModelImportResult
    data class Blocked(val reason: OrionModelRegistryFailure) : OrionModelImportResult
}

sealed interface OrionModelOpenResult {
    /** Caller owns this read-only descriptor. A handle grants no inference/Skill authority. */
    class Ready(val model: OrionRegisteredModel, val descriptor: ParcelFileDescriptor) : OrionModelOpenResult {
        override fun toString(): String = "OrionModelOpenResult.Ready(<redacted>)"
    }
    data class Blocked(val reason: OrionModelRegistryFailure) : OrionModelOpenResult
}

data class OrionModelRecoveryReport(val recoveredReady: Int, val quarantined: Int,
    val temporaryFilesRemoved: Int, val blockedArtifacts: Int)

internal interface OrionModelRegistryIndex {
    fun find(modelId: String): ModelRegistryEntity?
    fun list(limit: Int): List<ModelRegistryEntity>
    fun insertIfCapacity(row: ModelRegistryEntity, maximum: Int): Boolean
    fun transition(row: ModelRegistryEntity, next: ModelRegistryStatus, at: Long,
        metadata: OrionGgufMetadata? = null, error: String? = null): Boolean
}

/**
 * MAIN-owned technical storage port, invoked off the UI thread through trusted Core flows.
 * Import does not execute a model, assign a qualified resource profile or grant consent.
 * Hash identity is immutable; duplicate identity plus a different declared size is a conflict.
 * Failure records have bounded capacity and require explicit operator review/retry policy.
 */
class OrionModelRegistry internal constructor(private val index: OrionModelRegistryIndex,
    private val files: OrionModelArtifactStore, private val clock: WallClock,
    private val requireStorageAccess: () -> Unit = {},
) {
    /** This method closes the supplied stream, including rejected imports. */
    fun importModel(input: InputStream, expectedBytes: Long, expectedSha256: String): OrionModelImportResult = input.use { source -> synchronized(lock) {
        requireStorageAccess()
        if (!OrionModelArtifactStore.SHA.matches(expectedSha256) || expectedBytes !in 32..files.limits.maximumModelBytes)
            return@synchronized OrionModelImportResult.Blocked(OrionModelRegistryFailure.INVALID_REQUEST)
        val id = "sha256:$expectedSha256"
        val existing = index.find(id)
        if (existing != null) {
            if (existing.sha256 != expectedSha256 || existing.sizeBytes != expectedBytes)
                return@synchronized OrionModelImportResult.Blocked(OrionModelRegistryFailure.CONFLICT)
            if (existing.status != ModelRegistryStatus.READY)
                return@synchronized OrionModelImportResult.Blocked(OrionModelRegistryFailure.NOT_READY)
            return@synchronized try {
                validateRecord(existing)
                files.validateFinal(existing.sha256, existing.sizeBytes)
                OrionModelImportResult.Ready(existing.selected(), true)
            } catch (failure: Exception) {
                rethrowCancellation(failure)
                quarantine(existing, failureCode(failure).name)
                OrionModelImportResult.Blocked(failureCode(failure))
            }
        }
        var row: ModelRegistryEntity? = null
        try {
            files.preflight(expectedBytes)
            val at = clock.wallNow().toEpochMilli()
            val proposed = ModelRegistryEntity(id, "GGUF", "$expectedSha256.gguf", expectedSha256, expectedBytes,
                null, null, null, ModelRegistryStatus.IMPORTED, UUID.randomUUID().toString(), at, at, null, 0, null)
            if (!index.insertIfCapacity(proposed, files.limits.maximumRegistryEntries))
                return@synchronized OrionModelImportResult.Blocked(OrionModelRegistryFailure.CAPACITY)
            row = proposed
            files.copy(source, proposed.importToken, expectedBytes, expectedSha256)
            if (!index.transition(proposed, ModelRegistryStatus.VALIDATING, clock.wallNow().toEpochMilli()))
                return@synchronized OrionModelImportResult.Blocked(OrionModelRegistryFailure.CONFLICT)
            row = checkNotNull(index.find(id))
            val metadata = files.validateTemporary(row.importToken)
            files.promote(row.importToken, expectedSha256)
            if (!index.transition(row, ModelRegistryStatus.READY, clock.wallNow().toEpochMilli(), metadata))
                return@synchronized OrionModelImportResult.Blocked(OrionModelRegistryFailure.CONFLICT)
            OrionModelImportResult.Ready(checkNotNull(index.find(id)).selected(), false)
        } catch (failure: Exception) {
            // Cooperative cancellation is not evidence of corruption. Preserve the import
            // journal/state so normal recovery can inspect the owned bytes later.
            rethrowCancellation(failure)
            val current = row?.let { index.find(it.modelId) }
            if (current != null && current.status in setOf(ModelRegistryStatus.IMPORTED, ModelRegistryStatus.VALIDATING)) {
                val terminal = if (failure is OrionGgufValidationException || failure is ModelArtifactException &&
                    failure.reason in setOf(ModelArtifactFailure.HASH_MISMATCH, ModelArtifactFailure.SIZE_MISMATCH))
                    ModelRegistryStatus.INVALID else ModelRegistryStatus.QUARANTINED
                index.transition(current, terminal, clock.wallNow().toEpochMilli(), error = failureCode(failure).name)
            }
            row?.let { try { files.discardTemporary(it.importToken) } catch (_: IOException) { /* Recovery reports remaining artifacts. */ } }
            OrionModelImportResult.Blocked(failureCode(failure))
        }
    } }

    fun find(modelId: String): OrionRegisteredModel? = synchronized(lock) {
        requireStorageAccess(); if (!MODEL_ID.matches(modelId)) return@synchronized null
        index.find(modelId)?.also(::validateRecord)?.selected()
    }

    fun records(): List<OrionRegisteredModel> = synchronized(lock) {
        requireStorageAccess()
        val rows = index.list(files.limits.maximumRegistryEntries + 1)
        check(rows.size <= files.limits.maximumRegistryEntries) { "Model registry capacity exceeded." }
        rows.map { validateRecord(it); it.selected() }
    }

    /** Rehashes and structurally validates the SAME read-only inode sent to :ai. Never passes arbitrary paths. */
    fun openReady(modelId: String): OrionModelOpenResult = synchronized(lock) {
        requireStorageAccess()
        if (!MODEL_ID.matches(modelId)) return@synchronized OrionModelOpenResult.Blocked(OrionModelRegistryFailure.INVALID_REQUEST)
        val row = index.find(modelId) ?: return@synchronized OrionModelOpenResult.Blocked(OrionModelRegistryFailure.NOT_FOUND)
        if (row.status != ModelRegistryStatus.READY) return@synchronized OrionModelOpenResult.Blocked(OrionModelRegistryFailure.NOT_READY)
        try {
            validateRecord(row)
            val path = files.file(row.sha256)
            files.verifyFinalPresence(row.sha256, row.sizeBytes)
            val descriptor = Os.open(path.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
                OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
            FileInputStream(descriptor).use { stream ->
                val stat = Os.fstat(descriptor)
                require(OsConstants.S_ISREG(stat.st_mode) && stat.st_size == row.sizeBytes) { "Regular model artifact required." }
                val metadata = files.validateOpen(stream.channel, row.sha256, row.sizeBytes)
                if (metadata.architecture != row.architecture || metadata.quantization != row.quantization || metadata.contextLimit != row.contextLimit)
                    throw IOException("Model metadata changed.")
                OrionModelOpenResult.Ready(row.selected(), ParcelFileDescriptor.dup(descriptor))
            }
        } catch (failure: Exception) {
            rethrowCancellation(failure)
            quarantine(row, failureCode(failure).name)
            OrionModelOpenResult.Blocked(failureCode(failure))
        }
    }

    /**
     * A final file renamed before a process death is revalidated before READY.
     * Partial imports become QUARANTINED; UUID-named abandoned temp files are removed.
     * Unknown files and failures are preserved explicitly, not silently adopted or erased.
     */
    fun recover(): OrionModelRecoveryReport = synchronized(lock) {
        requireStorageAccess()
        var ready = 0; var quarantined = 0; var removed = 0; var blocked = 0
        val rows = index.list(files.limits.maximumRegistryEntries + 1)
        if (rows.size > files.limits.maximumRegistryEntries) return@synchronized OrionModelRecoveryReport(0, 0, 0, 1)
        for (row in rows) {
            try {
                validateRecord(row)
                if (row.status == ModelRegistryStatus.READY) {
                    try { files.verifyFinalPresence(row.sha256, row.sizeBytes) }
                    catch (failure: Exception) {
                        rethrowCancellation(failure)
                        if (quarantine(row, failureCode(failure).name)) quarantined++ else blocked++
                    }
                }
                if (row.status in setOf(ModelRegistryStatus.IMPORTED, ModelRegistryStatus.VALIDATING)) {
                    try {
                        val metadata = files.validateFinal(row.sha256, row.sizeBytes)
                        // IMPORTED cannot have a promoted final artifact in the normal protocol.
                        // Still enter VALIDATING explicitly when recovering such an owned artifact.
                        var current = row
                        if (current.status == ModelRegistryStatus.IMPORTED) {
                            check(index.transition(current, ModelRegistryStatus.VALIDATING, clock.wallNow().toEpochMilli()))
                            current = checkNotNull(index.find(current.modelId))
                        }
                        if (index.transition(current, ModelRegistryStatus.READY, clock.wallNow().toEpochMilli(), metadata)) ready++ else blocked++
                    } catch (failure: Exception) {
                        rethrowCancellation(failure)
                        val current = index.find(row.modelId) ?: row
                        if (quarantine(current, failureCode(failure).name)) quarantined++ else blocked++
                    }
                }
                if (files.discardTemporary(row.importToken)) removed++
            } catch (failure: Exception) { rethrowCancellation(failure); blocked++ }
        }
        try {
            val names = files.entries()
            val known = rows.map { it.filePath }.toSet()
            for (name in names) {
                if (OrionModelArtifactStore.PART.matches(name)) {
                    try { if (files.discardOrphanTemporary(name)) removed++ } catch (_: IOException) { blocked++ }
                } else if (name !in known) blocked++
            }
        } catch (failure: Exception) { rethrowCancellation(failure); blocked++ }
        OrionModelRecoveryReport(ready, quarantined, removed, blocked)
    }

    private fun quarantine(row: ModelRegistryEntity, code: String): Boolean =
        if (row.status in setOf(ModelRegistryStatus.IMPORTED, ModelRegistryStatus.VALIDATING, ModelRegistryStatus.READY))
            index.transition(row, ModelRegistryStatus.QUARANTINED, clock.wallNow().toEpochMilli(), error = code)
        else false

    private fun validateRecord(row: ModelRegistryEntity) {
        require(row.modelId == "sha256:${row.sha256}" && OrionModelArtifactStore.SHA.matches(row.sha256))
        require(row.filePath == "${row.sha256}.gguf" && row.displayName == "GGUF")
        require(row.sizeBytes in 32..files.limits.maximumModelBytes && row.version >= 0 && row.version < Long.MAX_VALUE)
        require(UUID.fromString(row.importToken).toString() == row.importToken)
    }

    private fun ModelRegistryEntity.selected() = OrionRegisteredModel(modelId, sha256, sizeBytes, status, architecture, quantization, contextLimit)

    companion object {
        private val lock = Any()
        private val MODEL_ID = Regex("sha256:[0-9a-f]{64}")
        /** Construction/each operation checks MAIN, unlocked credential storage. No auto initializer. */
        fun open(context: Context, database: OrionDatabase, clock: WallClock,
            limits: OrionModelImportLimits = OrionModelImportLimits()): OrionModelRegistry {
            val app = context.applicationContext
            val access = {
                MainProcessStoragePolicy.requireAccess(OrionProcessRole.fromProcessName(app.packageName, Application.getProcessName()),
                    app.getSystemService(UserManager::class.java)?.isUserUnlocked == true, context.isDeviceProtectedStorage)
            }
            access()
            // Resolve only Android's trusted base directory (which may contain platform symlinks).
            // The store still refuses a symlink at the owned orion_models directory itself.
            val artifacts = OrionModelArtifactStore(File(app.noBackupFilesDir.canonicalFile, "orion_models"), limits,
                syncDirectory = { directory ->
                    val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
                        OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
                    try {
                        require(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) { "Model directory required." }
                        Os.fsync(fd)
                    } finally { Os.close(fd) }
                }, openReadChannel = { file ->
                    // NONBLOCK closes the check/open replacement race for FIFOs.
                    val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
                        OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
                    var handedOff = false
                    try {
                        require(OsConstants.S_ISREG(Os.fstat(fd).st_mode)) { "Regular model artifact required." }
                        FileInputStream(fd).channel.also { handedOff = true }
                    } finally { if (!handedOff) Os.close(fd) }
                })
            return OrionModelRegistry(RoomModelRegistryIndex(database), artifacts, clock, access)
        }

        private fun rethrowCancellation(failure: Exception) {
            if (failure is CancellationException) throw failure
            if (failure is InterruptedException || failure is ClosedByInterruptException) {
                Thread.currentThread().interrupt(); throw failure
            }
            if (failure is InterruptedIOException) throw failure
        }
        private fun failureCode(failure: Exception): OrionModelRegistryFailure = when (failure) {
            is OrionGgufValidationException -> OrionModelRegistryFailure.INVALID_GGUF
            is ModelArtifactException -> when (failure.reason) {
                ModelArtifactFailure.SIZE_MISMATCH -> OrionModelRegistryFailure.SIZE_MISMATCH
                ModelArtifactFailure.HASH_MISMATCH -> OrionModelRegistryFailure.HASH_MISMATCH
                ModelArtifactFailure.NO_SPACE -> OrionModelRegistryFailure.NO_SPACE
                ModelArtifactFailure.UNSAFE_PATH -> OrionModelRegistryFailure.UNSAFE_PATH
                ModelArtifactFailure.CAPACITY -> OrionModelRegistryFailure.CAPACITY
            }
            is IllegalArgumentException -> OrionModelRegistryFailure.INVALID_REQUEST
            else -> OrionModelRegistryFailure.STORAGE_UNAVAILABLE
        }
    }
}

private class RoomModelRegistryIndex(private val database: OrionDatabase) : OrionModelRegistryIndex {
    override fun find(modelId: String) = database.modelRegistry().find(modelId)
    override fun list(limit: Int) = database.modelRegistry().list(limit)
    override fun insertIfCapacity(row: ModelRegistryEntity, maximum: Int): Boolean = database.runInTransaction(Callable {
        if (database.modelRegistry().count() >= maximum || database.modelRegistry().find(row.modelId) != null) false
        else { database.modelRegistry().insert(row); true }
    })
    override fun transition(row: ModelRegistryEntity, next: ModelRegistryStatus, at: Long,
        metadata: OrionGgufMetadata?, error: String?): Boolean {
        val allowed = when (row.status) {
            ModelRegistryStatus.IMPORTED -> next in setOf(ModelRegistryStatus.VALIDATING, ModelRegistryStatus.INVALID, ModelRegistryStatus.QUARANTINED)
            ModelRegistryStatus.VALIDATING -> next in setOf(ModelRegistryStatus.READY, ModelRegistryStatus.INVALID, ModelRegistryStatus.QUARANTINED)
            ModelRegistryStatus.READY -> next == ModelRegistryStatus.QUARANTINED
            ModelRegistryStatus.INVALID, ModelRegistryStatus.QUARANTINED -> false
        }
        require(allowed && row.version < Long.MAX_VALUE && (next != ModelRegistryStatus.READY || metadata != null))
        return database.modelRegistry().transition(row.modelId, row.version, row.status, next, at,
            metadata?.architecture, metadata?.quantization, metadata?.contextLimit,
            if (next == ModelRegistryStatus.READY) at else null, error) == 1
    }
}
