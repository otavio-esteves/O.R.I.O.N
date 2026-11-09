package network.orion.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Called only by the MAIN import coordinator after private-file checks, never by :ai. */
@Dao
abstract class OrionModelRegistryDao {
    @Insert protected abstract fun insert(row: ModelRegistryEntity)

    @Query("SELECT count(*) FROM orion_model_registry")
    abstract fun countAll(): Int

    @Query("SELECT * FROM orion_model_registry WHERE modelId = :id")
    abstract fun find(id: String): ModelRegistryEntity?

    @Query("""SELECT * FROM orion_model_registry WHERE (:after IS NULL OR modelId > :after)
        ORDER BY modelId LIMIT :limit""")
    protected abstract fun page(after: String?, limit: Int): List<ModelRegistryEntity>

    fun listPage(after: String? = null, limit: Int = 25): List<ModelRegistryEntity> {
        require(limit in 1..100)
        return page(after, limit)
    }

    /** Identity is the complete content hash; callers must have copied and hashed the private file. */
    fun registerImported(row: ModelRegistryEntity) {
        require(row.modelId == row.sha256 && HASH.matches(row.sha256) && row.sha256.any { it != '0' })
        require(row.status == "IMPORTED" && row.version == 0L && row.lastValidatedAt == null)
        require(row.architecture == null && row.quantization == null && row.contextLimit == null)
        require(row.sizeBytes > 0 && row.filePath.isNotBlank() && row.displayName.length in 1..128)
        insert(row)
    }

    @Query("""UPDATE orion_model_registry SET status='VALIDATING', version=version+1
        WHERE modelId=:id AND version=:version AND version < 9223372036854775807 AND status='IMPORTED'""")
    abstract fun beginValidation(id: String, version: Long): Int

    @Query("""UPDATE orion_model_registry SET status='READY', architecture=:architecture,
        quantization=:quantization, contextLimit=:contextLimit, lastValidatedAt=:at, version=version+1
        WHERE modelId=:id AND version=:version AND version < 9223372036854775807 AND status='VALIDATING'
        AND sha256=:verifiedHash AND sizeBytes=:verifiedSize""")
    protected abstract fun ready(id: String, version: Long, verifiedHash: String, verifiedSize: Long,
        architecture: String, quantization: String, contextLimit: Int, at: Long): Int

    /** The coordinator supplies results of full file/format validation, never untrusted GGUF claims alone. */
    fun completeValidation(id: String, version: Long, verifiedHash: String, verifiedSize: Long,
        architecture: String, quantization: String, contextLimit: Int, at: Long): Int {
        require(HASH.matches(verifiedHash) && id == verifiedHash && verifiedSize > 0)
        require(architecture.length in 1..128 && quantization.length in 1..128 && contextLimit > 0)
        return ready(id, version, verifiedHash, verifiedSize, architecture, quantization, contextLimit, at)
    }

    @Query("""UPDATE orion_model_registry SET status=:status, version=version+1
        WHERE modelId=:id AND version=:version AND version < 9223372036854775807
        AND status IN ('IMPORTED','VALIDATING','READY')""")
    protected abstract fun fail(id: String, version: Long, status: String): Int

    fun invalidate(id: String, version: Long, quarantine: Boolean): Int =
        fail(id, version, if (quarantine) "QUARANTINED" else "INVALID")

    companion object { private val HASH = Regex("[0-9a-f]{64}") }
}
