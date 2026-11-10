package network.orion.data.repository.models

import android.app.Application
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.UserManager
import java.io.InputStream
import network.orion.core.common.process.MainProcessStoragePolicy
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.time.WallClock
import network.orion.data.database.ModelRegistryEntity
import network.orion.data.database.OrionDatabase

enum class OrionModelImportBlock { FILE_REJECTED, REGISTRY_CAPACITY, REGISTRY_CONFLICT, MODEL_UNAVAILABLE }

sealed interface OrionModelImportResult {
    class Ready(val modelId: String, val reused: Boolean) : OrionModelImportResult
    data class Rejected(val reason: OrionModelImportBlock, val fileReason: OrionModelFileFailure? = null) : OrionModelImportResult
}

data class OrionModelRecoveryPage(val inspected: Int, val invalidated: Int, val ready: Int,
    val conflicts: Int, val nextAfterModelId: String?)

data class OrionUnregisteredModelCleanup(val deleted: Int, val blocked: Int, val nextAfterFileName: String?)

/**
 * Trusted technical MAIN port; no UI/Skill ingress and no inference authority.
 * Synchronous IO: call off the UI thread. The file commit and SQLite commit cannot
 * be atomic together, so publication happens first and recovery reconciles each gap.
 * READY means structural/integrity validation only, never resource qualification.
 */
class OrionModelImportCoordinator private constructor(
    private val database: OrionDatabase,
    private val files: OrionPrivateModelFiles,
    private val parser: OrionGgufValidator,
    private val clock: WallClock,
) {
    fun importModel(expected: OrionExpectedModelFile, source: () -> InputStream,
        checkCancellation: () -> Unit = {}): OrionModelImportResult = synchronized(lock) {
        val existing = database.models().find(expected.sha256)
        if (existing != null) {
            if (!matches(existing, expected)) return@synchronized rejected(OrionModelImportBlock.REGISTRY_CONFLICT)
            if (existing.status == "READY") {
                val opened = openReadOnly(existing.modelId)
                return@synchronized if (opened != null) {
                    opened.close(); OrionModelImportResult.Ready(existing.modelId, true)
                } else rejected(OrionModelImportBlock.MODEL_UNAVAILABLE)
            }
            // Recovery, not a new copy or a silent replacement, owns interrupted rows.
            return@synchronized rejected(OrionModelImportBlock.MODEL_UNAVAILABLE)
        }
        if (database.models().countAll() >= MAX_REGISTRY_ROWS) return@synchronized rejected(OrionModelImportBlock.REGISTRY_CAPACITY)
        val staged = files.stage(expected, source, checkCancellation)
        if (staged is OrionModelFileImport.Rejected)
            return@synchronized OrionModelImportResult.Rejected(OrionModelImportBlock.FILE_REJECTED, staged.reason)
        checkCancellation()
        // A process death here leaves only an unregistered private file, never READY.
        val imported = ModelRegistryEntity(expected.sha256, "GGUF ${expected.sha256.take(12)}",
            fileName(expected.sha256), expected.sha256, expected.sizeBytes, null, null, null,
            "IMPORTED", clock.wallNow().toEpochMilli(), null)
        var accepted = false
        database.runInTransaction {
            if (database.models().find(expected.sha256) == null && database.models().countAll() < MAX_REGISTRY_ROWS) {
                database.models().registerImported(imported)
                accepted = true
            }
        }
        if (!accepted) return@synchronized rejected(OrionModelImportBlock.REGISTRY_CONFLICT)
        checkCancellation()
        if (finishPending(imported)) OrionModelImportResult.Ready(expected.sha256, (staged as OrionModelFileImport.Verified).reused)
        else rejected(OrionModelImportBlock.MODEL_UNAVAILABLE)
    }

    /** Every open checks current READY, exact identity and full hash/format on the opened inode. */
    fun openReadOnly(modelId: String): ParcelFileDescriptor? = synchronized(lock) {
        val row = database.models().find(modelId) ?: return@synchronized null
        if (row.status != "READY") return@synchronized null
        val expected = expected(row)
        val read = expected?.let { files.openVerified(it, parser) } ?: OrionModelFileRead.Invalid
        if (read == OrionModelFileRead.StorageUnavailable) return@synchronized null
        if (read !is OrionModelFileRead.Verified) {
            database.models().invalidate(row.modelId, row.version, true)
            return@synchronized null
        }
        val opened = read.opened
        var transferred = false
        try {
            val metadata = opened.metadata
            val same = metadata.architecture == row.architecture && metadata.contextLimit == row.contextLimit &&
                quantization(metadata) == row.quantization && database.models().find(modelId) == row
            if (!same) {
                database.models().invalidate(row.modelId, row.version, true)
                null
            } else {
                transferred = true
                opened.descriptor
            }
        } finally { if (!transferred) opened.descriptor.close() }
    }

    /** A caller resumes with the cursor; no READY entry is assumed valid merely because SQLite says so. */
    fun recoverPage(afterModelId: String? = null, limit: Int = 25): OrionModelRecoveryPage = synchronized(lock) {
        val page = database.models().listPage(afterModelId, limit)
        var invalidated = 0
        var restored = 0
        var conflicts = 0
        for (row in page) {
            when (row.status) {
                "READY" -> {
                    val opened = openReadOnly(row.modelId)
                    if (opened != null) opened.close()
                    else if (database.models().find(row.modelId)?.status in setOf("INVALID", "QUARANTINED")) invalidated++
                    else conflicts++
                }
                "IMPORTED", "VALIDATING" -> if (finishPending(row)) restored++ else {
                    if (database.models().find(row.modelId)?.status in setOf("INVALID", "QUARANTINED")) invalidated++ else conflicts++
                }
            }
        }
        OrionModelRecoveryPage(page.size, invalidated, restored, conflicts,
            if (page.size == limit) page.last().modelId else null)
    }

    /**
     * A completed startup scan cannot cover a later interrupted import. Revalidate
     * exactly the selected identity without rescanning other models or opening a
     * source stream. This grants no load/inference authority; callers still require
     * a fresh request and resource decision after recovery.
     */
    fun recoverSelected(modelId: String): Boolean = synchronized(lock) {
        val row = database.models().find(modelId) ?: return@synchronized false
        when (row.status) {
            "IMPORTED", "VALIDATING" -> finishPending(row)
            "READY" -> openReadOnly(modelId)?.use { true } ?: false
            else -> false
        }
    }

    fun cleanupInterruptedCopies(limit: Int = 25): OrionModelOrphanCleanup = files.cleanupInterruptedBatch(limit)

    /** Preserve files referenced even by quarantined/manual-review rows; never manufacture READY from filenames. */
    fun cleanupUnregisteredFiles(afterFileName: String? = null, limit: Int = 25): OrionUnregisteredModelCleanup = synchronized(lock) {
        val known = mutableSetOf<String>()
        var after: String? = null
        do {
            val rows = database.models().listPage(after, 100)
            rows.forEach { known.add(it.modelId) }
            // Admission caps rows at128. Excess from another writer is preserved and reported.
            if (known.size > MAX_REGISTRY_ROWS) return@synchronized OrionUnregisteredModelCleanup(0, 1, null)
            after = if (rows.size == 100) rows.last().modelId else null
        } while (after != null)
        val (result, next) = files.reconcileUnregisteredBatch(known, afterFileName, limit)
        OrionUnregisteredModelCleanup(result.deleted, result.blocked, next)
    }

    private fun finishPending(original: ModelRegistryEntity): Boolean {
        var row = original
        if (row.status == "IMPORTED") {
            if (database.models().beginValidation(row.modelId, row.version) != 1) return false
            row = database.models().find(row.modelId) ?: return false
        }
        if (row.status != "VALIDATING") return false
        val expected = expected(row)
        val read = expected?.let { files.openVerified(it, parser) } ?: OrionModelFileRead.Invalid
        if (read == OrionModelFileRead.StorageUnavailable) return false
        if (read !is OrionModelFileRead.Verified) {
            database.models().invalidate(row.modelId, row.version, true)
            return false
        }
        val opened = read.opened
        val metadata = opened.metadata
        opened.descriptor.close()
        return database.models().completeValidation(row.modelId, row.version, row.sha256, row.sizeBytes,
            metadata.architecture, quantization(metadata), metadata.contextLimit, clock.wallNow().toEpochMilli()) == 1
    }

    private fun expected(row: ModelRegistryEntity): OrionExpectedModelFile? {
        if (row.modelId != row.sha256 || row.filePath != fileName(row.sha256)) return null
        return try { OrionExpectedModelFile(row.sha256, row.sizeBytes) } catch (_: IllegalArgumentException) { null }
    }
    private fun matches(row: ModelRegistryEntity, expected: OrionExpectedModelFile) =
        row.modelId == expected.sha256 && row.sha256 == expected.sha256 && row.sizeBytes == expected.sizeBytes &&
            row.filePath == fileName(expected.sha256)
    private fun rejected(reason: OrionModelImportBlock) = OrionModelImportResult.Rejected(reason)
    private fun fileName(hash: String) = "verified/$hash.gguf"
    private fun quantization(metadata: OrionGgufMetadata) = metadata.tensorTypes.sortedBy { it.code }.joinToString("+") { it.name }

    companion object {
        private const val MAX_REGISTRY_ROWS = 128
        private val lock = Any()
        fun open(context: Context, database: OrionDatabase, clock: WallClock,
            maximumModelBytes: Long, reserveBytes: Long): OrionModelImportCoordinator {
            val app = context.applicationContext
            MainProcessStoragePolicy.requireAccess(
                OrionProcessRole.fromProcessName(app.packageName, Application.getProcessName()),
                app.getSystemService(UserManager::class.java).isUserUnlocked, context.isDeviceProtectedStorage)
            val parser = OrionGgufValidator()
            return OrionModelImportCoordinator(database,
                OrionPrivateModelFiles.open(context, maximumModelBytes, reserveBytes, parser), parser, clock)
        }
    }
}
