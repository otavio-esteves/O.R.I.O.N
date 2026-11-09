package network.orion.data.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

enum class ModelRegistryStatus { IMPORTED, VALIDATING, READY, INVALID, QUARANTINED }

/** MAIN-owned technical artifact identity. No source URI, user filename or model metadata text is retained. */
@Entity(tableName = "orion_model_registry", indices = [Index(value = ["status", "updatedAt"])])
data class ModelRegistryEntity(
    @PrimaryKey val modelId: String,
    val displayName: String,
    val filePath: String,
    val sha256: String,
    val sizeBytes: Long,
    val architecture: String?,
    val quantization: String?,
    val contextLimit: Int?,
    val status: ModelRegistryStatus,
    val importToken: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastValidatedAt: Long?,
    val version: Long,
    val lastErrorCode: String?,
) {
    override fun toString(): String = "ModelRegistryEntity(<redacted>)"
}

@Dao
interface OrionModelRegistryDao {
    @Query("SELECT * FROM orion_model_registry WHERE modelId = :modelId")
    fun find(modelId: String): ModelRegistryEntity?

    @Query("SELECT * FROM orion_model_registry ORDER BY modelId LIMIT :limit")
    fun list(limit: Int): List<ModelRegistryEntity>

    @Query("SELECT count(*) FROM orion_model_registry")
    fun count(): Int

    @Insert
    fun insert(model: ModelRegistryEntity)

    /** Explicit optimistic ownership: no new caller may overwrite an existing artifact or validation result. */
    @Query("""UPDATE orion_model_registry SET status = :next, updatedAt = :at, version = version + 1,
        architecture = :architecture, quantization = :quantization, contextLimit = :contextLimit,
        lastValidatedAt = :validatedAt, lastErrorCode = :errorCode
        WHERE modelId = :modelId AND version = :expectedVersion AND status = :expectedStatus""")
    fun transition(modelId: String, expectedVersion: Long, expectedStatus: ModelRegistryStatus,
        next: ModelRegistryStatus, at: Long, architecture: String?, quantization: String?, contextLimit: Int?,
        validatedAt: Long?, errorCode: String?): Int
}
