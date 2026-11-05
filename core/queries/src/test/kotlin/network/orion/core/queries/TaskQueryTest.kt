package network.orion.core.queries

import java.util.UUID
import network.orion.core.queries.tasks.*
import org.junit.Assert.*
import org.junit.Test

class TaskQueryTest {
    @Test fun pageLimitsAreBounded() {
        assertThrows(IllegalArgumentException::class.java) { GetTasks(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { GetTasks(limit = 101) }
        assertEquals(100, GetTasks(limit = 100).limit)
    }
    @Test fun selectedResultsDefensivelyCopyAndRedact() {
        val row = TaskSummary(UUID.randomUUID(), "sensitive title", TaskState.OPEN, 0, TaskContentState.READABLE)
        val input = mutableListOf(row)
        val page = TaskPage(input, row.taskId)
        input.clear()
        assertEquals(1, page.items.size)
        assertThrows(UnsupportedOperationException::class.java) { (page.items as MutableList).clear() }
        assertFalse(row.toString().contains("sensitive")); assertFalse(page.toString().contains("sensitive"))
    }
    @Test fun unreadableRowsCannotClaimReadableContentOrInventCursor() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskSummary(UUID.randomUUID(), "x", TaskState.OPEN, 0, TaskContentState.UNREADABLE)
        }
        assertThrows(IllegalArgumentException::class.java) { TaskPage(emptyList(), UUID.randomUUID()) }
    }
}
