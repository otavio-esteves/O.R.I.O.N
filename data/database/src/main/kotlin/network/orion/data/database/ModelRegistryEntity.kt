package network.orion.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** MAIN-owned technical metadata. READY is import integrity, never inference authorization. */
@Entity(tableName = "orion_model_registry", indices = [Index(value = ["filePath"], unique = true)])
data class ModelRegistryEntity(
    @PrimaryKey val modelId: String,
    val displayName: String,
    val filePath: String,
    val sha256: String,
    val sizeBytes: Long,
    val architecture: String?,
    val quantization: String?,
    val contextLimit: Int?,
    val status: String,
    val createdAt: Long,
    val lastValidatedAt: Long?,
    val version: Long = 0,
) {
    override fun toString() = "ModelRegistryEntity(<private metadata>)"
}
