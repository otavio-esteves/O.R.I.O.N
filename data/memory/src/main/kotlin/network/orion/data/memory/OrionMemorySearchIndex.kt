package network.orion.data.memory

import java.util.UUID
import java.util.concurrent.Callable
import network.orion.core.model.Sensitivity
import network.orion.core.queries.memories.MemoryContentState
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.time.WallClock
import network.orion.data.database.*

enum class OrionMemoryIndexProgress { BUILDING, BLOCKED, READY }

/** Derived, bounded index over the current product's user-declared personal memories. */
class OrionMemorySearchIndex(private val database: OrionDatabase, encryption: OrionFieldEncryption,
    clock: WallClock) {
    private val dao = database.memoryIndex()
    private val query = OrionMemoryQuery(database, encryption, clock)

    /** Persist the cursor with the corresponding postings so restart can resume an incomplete build. */
    fun backfillBatch(limit: Int = 32): OrionMemoryIndexProgress {
        require(limit in 1..100)
        return database.runInTransaction(Callable {
            val state = dao.state() ?: OrionMemoryIndexState()
            if (state.ready) return@Callable OrionMemoryIndexProgress.READY
            val page = dao.pageForBackfill(state.cursor, limit + 1)
            var cursor = state.cursor
            for (row in page.take(limit)) {
                if (eligible(row)) {
                    val (content, availability) = query.readIndexContent(row)
                    if (availability == MemoryContentState.KEY_UNAVAILABLE) {
                        dao.save(state.copy(cursor = cursor))
                        return@Callable OrionMemoryIndexProgress.BLOCKED
                    }
                    write(row.memoryId, content)
                } else {
                    dao.remove(row.memoryId)
                }
                cursor = row.memoryId
            }
            val ready = page.size <= limit
            dao.save(state.copy(cursor = cursor, ready = ready))
            if (ready) OrionMemoryIndexProgress.READY else OrionMemoryIndexProgress.BUILDING
        })
    }

    internal fun indexWrittenMemory(row: MemoryEntity, clearContent: ByteArray) {
        check(database.inTransaction())
        if (!eligible(row)) return
        val id = UUID.fromString(row.memoryId)
        write(row.memoryId, query.decodeContent(id, clearContent))
    }

    private fun write(id: String, content: String?) {
        dao.remove(id)
        if (content != null) dao.insert(OrionMemoryIndexRow(id, content))
    }

    private fun eligible(row: MemoryEntity): Boolean =
        row.origin == MemoryOrigin.USER_DECLARED && row.userConfirmed && row.sensitivity == Sensitivity.PERSONAL &&
            row.status == MemoryStatus.ACTIVE
}
