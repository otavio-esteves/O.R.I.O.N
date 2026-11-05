package network.orion.core.orchestrator.timers

import java.util.UUID
import network.orion.core.commands.timers.*
import network.orion.core.queries.timers.*

enum class TimerOperation { CREATE, CANCEL }
enum class TimerOperationOutcome { APPLIED, QUEUED, REJECTED, UNAVAILABLE }

/** Selected immutable display data. IDs/version are references, never confirmation authority. */
class TimerConfirmation(val actionId: UUID, val version: Long, val operation: TimerOperation, val title: String?, val durationMillis: Long? = null) {
    override fun toString() = "TimerConfirmation(<redacted>)"
}

/** Activity-scoped in-app port. Mutation submissions originate only in explicit UI handlers. */
interface TimerGateway {
    suspend fun create(command: CreateTimer): CreateTimerResult
    suspend fun change(command: TimerStatusCommand): TimerStatusResult
    suspend fun list(request: GetTimers): TimerPage?
    suspend fun status(request: GetTimerStatus): TimerSummary?
    suspend fun pending(): List<TimerConfirmation>
    suspend fun confirm(actionId: UUID, displayedVersion: Long): TimerOperationOutcome
    suspend fun dismiss(actionId: UUID, displayedVersion: Long): TimerOperationOutcome
    /** Separate explicit lifecycle/gesture operation; reads never hide a queue drain. */
    suspend fun processPending(): Int
}
