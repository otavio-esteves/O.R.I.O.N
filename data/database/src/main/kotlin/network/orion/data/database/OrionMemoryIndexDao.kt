package network.orion.data.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Rebuildable content selected by MAIN's memory sensitivity policy. */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["memoryId"])
@Entity(tableName = "orion_memory_fts")
data class OrionMemoryIndexRow(val memoryId: String, val body: String) {
    override fun toString() = "OrionMemoryIndexRow(<redacted>)"
}

@Entity(tableName = "orion_memory_index_state")
data class OrionMemoryIndexState(@PrimaryKey val id: Int = 1, val cursor: String = "", val ready: Boolean = false)

@Dao
interface OrionMemoryIndexDao {
    @Insert
    fun insert(row: OrionMemoryIndexRow)

    @Query("DELETE FROM orion_memory_fts WHERE memoryId = :memoryId")
    fun remove(memoryId: String)

    @Query("SELECT * FROM orion_memory_index_state WHERE id = 1")
    fun state(): OrionMemoryIndexState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(state: OrionMemoryIndexState)

    @Query("""SELECT * FROM orion_memories WHERE origin = 'USER_DECLARED' AND userConfirmed = 1
        AND sensitivity = 'PERSONAL' AND status = 'ACTIVE'
        AND memoryId > :cursor ORDER BY memoryId LIMIT :limit""")
    fun pageForBackfill(cursor: String, limit: Int): List<MemoryEntity>
}
