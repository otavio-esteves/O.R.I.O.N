package network.orion.core.orchestrator.reminders

import java.util.UUID
import network.orion.core.commands.reminders.*
import network.orion.core.queries.reminders.*

enum class ReminderOperation { CREATE, SNOOZE, CANCEL }
enum class ReminderOperationOutcome { APPLIED, QUEUED, REJECTED, UNAVAILABLE }

/** Selected immutable display data. IDs/version are references, never confirmation authority. */
class ReminderConfirmation(val actionId: UUID, val version: Long, val operation: ReminderOperation, val title: String?) {
    override fun toString() = "ReminderConfirmation(<redacted>)"
}

/** Activity-scoped in-app port. Mutation submissions originate only in explicit UI handlers. */
interface ReminderGateway {
    /** Samples MAIN's injected wall clock once; returned request is retained across retries. No authority. */
    fun prepareCreate(id: UUID, title: String, afterMinutes: Long, key: UUID, trace: UUID): CreateReminder?
    fun prepareSnooze(id: UUID, version: Long, currentScheduledAt: java.time.Instant, afterMinutes: Long, key: UUID, trace: UUID): SnoozeReminder?

    suspend fun create(command: CreateReminder): CreateReminderResult
    suspend fun change(command: ReminderStatusCommand): ReminderStatusResult
    suspend fun list(request: GetReminders): ReminderPage?
    suspend fun pending(): List<ReminderConfirmation>
    suspend fun confirm(actionId: UUID, displayedVersion: Long): ReminderOperationOutcome
    suspend fun dismiss(actionId: UUID, displayedVersion: Long): ReminderOperationOutcome
    /** Separate explicit lifecycle/gesture operation; reads never hide a queue drain. */
    suspend fun processPending(): Int
}
