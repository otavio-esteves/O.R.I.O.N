package network.orion.core.orchestrator.tasks

import java.util.UUID
import network.orion.core.commands.tasks.*
import network.orion.core.queries.tasks.*

enum class TaskOperation { CREATE, COMPLETE, CANCEL }
enum class TaskOperationOutcome { APPLIED, QUEUED, REJECTED, UNAVAILABLE }

/** Selected immutable display data. IDs/version are references, never confirmation authority. */
class TaskConfirmation(val actionId: UUID, val version: Long, val operation: TaskOperation, val title: String?) {
    override fun toString() = "TaskConfirmation(<redacted>)"
}

/** Activity-scoped in-app port. Mutation submissions originate only in explicit UI handlers. */
interface TaskGateway {
    suspend fun create(command: CreateTask): CreateTaskResult
    suspend fun change(command: TaskStatusCommand): TaskStatusResult
    suspend fun list(request: GetTasks): TaskPage?
    suspend fun pending(): List<TaskConfirmation>
    suspend fun confirm(actionId: UUID, displayedVersion: Long): TaskOperationOutcome
    suspend fun dismiss(actionId: UUID, displayedVersion: Long): TaskOperationOutcome
    /** Separate explicit lifecycle/gesture operation; reads never hide a queue drain. */
    suspend fun processPending(): Int
}
