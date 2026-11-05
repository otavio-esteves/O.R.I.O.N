package network.orion.app.tasks

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.orion.core.actions.*
import network.orion.core.commands.tasks.*
import network.orion.core.orchestrator.tasks.*
import network.orion.core.queries.tasks.*
import network.orion.core.time.OrionClock

/** Trusted Activity-scoped adapter. Capture the actual gesture before the first IO suspension. */
internal class OrionTaskUiGateway(private val channel: OrionTaskAdmissionOwner.UiChannel,
    private val kernel: () -> OrionTaskKernel?, private val clock: OrionClock, private val onStorageFailure: () -> Unit = {}) : TaskGateway {
    @Volatile private var displayed: Map<UUID, OrionTaskPending> = emptyMap()

    override suspend fun create(command: CreateTask): CreateTaskResult {
        val captured = channel.captureCreateFromUser(command)
        if (captured is OrionTaskAdmissionResult.Invalid) return CreateTaskResult.REJECTED
        val task = (captured as? OrionTaskAdmissionResult.Admitted)?.request?.task ?: return CreateTaskResult.UNAVAILABLE
        val wire = OrionTaskIngressWire("CREATE", task.taskId, task.idempotencyKey, task.correlationId, task.title)
        return when (send(wire, task.idempotencyKey)) {
            TaskOperationOutcome.QUEUED, TaskOperationOutcome.APPLIED -> CreateTaskResult.QUEUED
            TaskOperationOutcome.REJECTED -> CreateTaskResult.REJECTED
            TaskOperationOutcome.UNAVAILABLE -> CreateTaskResult.UNAVAILABLE
        }
    }

    override suspend fun change(command: TaskStatusCommand): TaskStatusResult {
        val captured = channel.captureStatusFromUser(command)
        if (captured is OrionTaskStatusAdmissionResult.Invalid) return TaskStatusResult.REJECTED
        val change = (captured as? OrionTaskStatusAdmissionResult.Admitted)?.request?.change ?: return TaskStatusResult.UNAVAILABLE
        return when (send(OrionTaskIngressWire(change.operation.name, change.taskId, change.idempotencyKey,
            change.correlationId, expectedVersion = change.expectedVersion), change.idempotencyKey)) {
            TaskOperationOutcome.QUEUED, TaskOperationOutcome.APPLIED -> TaskStatusResult.QUEUED
            TaskOperationOutcome.REJECTED -> TaskStatusResult.REJECTED
            TaskOperationOutcome.UNAVAILABLE -> TaskStatusResult.UNAVAILABLE
        }
    }

    override suspend fun list(request: GetTasks): TaskPage? = withContext(Dispatchers.IO) { kernel()?.query?.get(request) }
    override suspend fun pending(): List<TaskConfirmation> {
        val selected = withContext(Dispatchers.IO) { kernel()?.pending() ?: emptyList() }
        displayed = selected.associateBy { it.display.actionId }
        return selected.map { it.display }
    }

    override suspend fun confirm(actionId: UUID, displayedVersion: Long): TaskOperationOutcome {
        val selected = displayed[actionId]?.takeIf { it.display.version == displayedVersion && it.display.title != null }
            ?: return TaskOperationOutcome.REJECTED
        val normalized = selected.create?.let { p ->
            if (channel.captureCreateFromUser(CreateTask(p.task.taskId, p.task.title, p.task.idempotencyKey,
                    p.task.correlationId)) !is OrionTaskAdmissionResult.Admitted) return TaskOperationOutcome.UNAVAILABLE
            OrionCreateTaskActionStore.normalized(p.actionId, p.task, p.policyVersion)
        } ?: selected.change?.let { p ->
            val c = p.change
            val command = if (c.operation == TaskStatusOperation.COMPLETE) CompleteTask(c.taskId, c.expectedVersion, c.idempotencyKey, c.correlationId)
                else CancelTask(c.taskId, c.expectedVersion, c.idempotencyKey, c.correlationId)
            if (channel.captureStatusFromUser(command) !is OrionTaskStatusAdmissionResult.Admitted) return TaskOperationOutcome.UNAVAILABLE
            OrionTaskStatusActionStore.normalized(p.actionId, c, p.policyVersion)
        } ?: return TaskOperationOutcome.REJECTED
        // Recorded now, at the live callback; persisted replay must retain these exact times.
        val receipt = ActionConfirmationBinding(clock).recordConfirmation(normalized, clock.wallNow().plusSeconds(60))
        return send(selected.wire(receipt), UUID.randomUUID())
    }

    override suspend fun dismiss(actionId: UUID, displayedVersion: Long): TaskOperationOutcome {
        val selected = displayed[actionId]?.takeIf { it.display.version == displayedVersion } ?: return TaskOperationOutcome.REJECTED
        if (!channel.captureDismissFromUser()) return TaskOperationOutcome.UNAVAILABLE
        return send(selected.wire(dismiss = true), UUID.randomUUID())
    }
    override suspend fun processPending(): Int = withContext(Dispatchers.IO) {
        guardStorage { kernel()?.ingress?.drain(25) ?: 0 }
    }
    private suspend fun send(wire: OrionTaskIngressWire, key: UUID) = withContext(Dispatchers.IO) {
        guardStorage { kernel()?.submit(wire, key) ?: TaskOperationOutcome.UNAVAILABLE }
    }
    private fun <T> guardStorage(block: () -> T): T = try { block() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
        catch (failure: Exception) { onStorageFailure(); throw failure }

}
