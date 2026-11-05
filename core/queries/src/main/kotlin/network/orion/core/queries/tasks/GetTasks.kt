package network.orion.core.queries.tasks

import java.util.Collections
import java.util.UUID
import network.orion.core.queries.Query

/** Stable UUID cursor. A query never drains ingress or changes task state. */
data class GetTasks(val after: UUID? = null, val limit: Int = 50) : Query<TaskPage> {
    init { require(limit in 1..100) }
}

enum class TaskState { OPEN, COMPLETED, CANCELLED }
enum class TaskContentState { READABLE, UNREADABLE, KEY_UNAVAILABLE }

class TaskSummary(val taskId: UUID, val title: String?, val state: TaskState, val version: Long,
    val contentState: TaskContentState) {
    init { require(version >= 0); require((title != null) == (contentState == TaskContentState.READABLE)) }
    override fun toString() = "TaskSummary(<redacted>)"
}

class TaskPage(items: List<TaskSummary>, val next: UUID?) {
    val items: List<TaskSummary> = Collections.unmodifiableList(ArrayList(items))
    init { require(items.size <= 100); require(next == null || items.lastOrNull()?.taskId == next) }
    override fun toString() = "TaskPage(<redacted>)"
}

fun interface TaskQuery { fun get(request: GetTasks): TaskPage }
