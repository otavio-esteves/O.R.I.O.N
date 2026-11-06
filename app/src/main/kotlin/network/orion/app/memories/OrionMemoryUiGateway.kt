package network.orion.app.memories

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.orion.core.actions.*
import network.orion.core.commands.memories.*
import network.orion.core.orchestrator.memories.*
import network.orion.core.queries.memories.*
import network.orion.core.time.OrionClock

/** Trusted Activity-scoped adapter. Capture the actual gesture before the first IO suspension. */
internal class OrionMemoryUiGateway(private val channel: OrionMemoryAdmissionOwner.UiChannel,
    private val kernel: () -> OrionMemoryKernel?, private val clock: OrionClock, private val onStorageFailure: () -> Unit = {}) : MemoryGateway {
    @Volatile private var displayed: Map<UUID, OrionMemoryPending> = emptyMap()

    override suspend fun create(command: CreateMemory): CreateMemoryResult {
        val captured = channel.captureCreateFromUser(command)
        if (captured is OrionMemoryAdmissionResult.Invalid) return CreateMemoryResult.REJECTED
        val memory = (captured as? OrionMemoryAdmissionResult.Admitted)?.request?.memory ?: return CreateMemoryResult.UNAVAILABLE
        val wire = OrionMemoryIngressWire("CREATE", memory.memoryId, memory.idempotencyKey, memory.correlationId, memory.content)
        return when (send(wire, memory.idempotencyKey)) {
            MemoryOperationOutcome.QUEUED, MemoryOperationOutcome.APPLIED -> CreateMemoryResult.QUEUED
            MemoryOperationOutcome.REJECTED -> CreateMemoryResult.REJECTED
            MemoryOperationOutcome.UNAVAILABLE -> CreateMemoryResult.UNAVAILABLE
            MemoryOperationOutcome.FORGOTTEN -> CreateMemoryResult.FORGOTTEN
        }
    }

    override suspend fun change(command: MemoryStatusCommand): MemoryStatusResult {
        val captured = channel.captureStatusFromUser(command)
        if (captured is OrionMemoryStatusAdmissionResult.Invalid) return MemoryStatusResult.REJECTED
        val change = (captured as? OrionMemoryStatusAdmissionResult.Admitted)?.request?.change ?: return MemoryStatusResult.UNAVAILABLE
        return when (send(OrionMemoryIngressWire(change.operation.name, change.memoryId, change.idempotencyKey,
            change.correlationId, expectedVersion = change.expectedVersion), change.idempotencyKey)) {
            MemoryOperationOutcome.QUEUED, MemoryOperationOutcome.APPLIED -> MemoryStatusResult.QUEUED
            MemoryOperationOutcome.REJECTED -> MemoryStatusResult.REJECTED
            MemoryOperationOutcome.UNAVAILABLE -> MemoryStatusResult.UNAVAILABLE
            MemoryOperationOutcome.FORGOTTEN -> MemoryStatusResult.REJECTED
        }
    }

    override suspend fun list(request: GetMemories): MemoryPage? = withContext(Dispatchers.IO) { kernel()?.query?.get(request) }
    override suspend fun pending(): List<MemoryConfirmation> {
        val selected = withContext(Dispatchers.IO) { kernel()?.pending() ?: emptyList() }
        displayed = selected.associateBy { it.display.actionId }
        return selected.map { it.display }
    }

    override suspend fun confirm(actionId: UUID, displayedVersion: Long): MemoryOperationOutcome {
        val selected = displayed[actionId]?.takeIf { it.display.version == displayedVersion && it.display.content != null }
            ?: return MemoryOperationOutcome.REJECTED
        val normalized = selected.create?.let { p ->
            if (channel.captureCreateFromUser(CreateMemory(p.memory.memoryId, p.memory.content, p.memory.idempotencyKey,
                    p.memory.correlationId)) !is OrionMemoryAdmissionResult.Admitted) return MemoryOperationOutcome.UNAVAILABLE
            OrionCreateMemoryActionStore.normalized(p.actionId, p.memory, p.policyVersion)
        } ?: selected.change?.let { p ->
            val c = p.change
            val command = ForgetMemory(c.memoryId, c.expectedVersion, c.idempotencyKey, c.correlationId)
            if (channel.captureStatusFromUser(command) !is OrionMemoryStatusAdmissionResult.Admitted) return MemoryOperationOutcome.UNAVAILABLE
            OrionMemoryStatusActionStore.normalized(p.actionId, c, p.policyVersion)
        } ?: return MemoryOperationOutcome.REJECTED
        // Recorded now, at the live callback; persisted replay must retain these exact times.
        val receipt = ActionConfirmationBinding(clock).recordConfirmation(normalized, clock.wallNow().plusSeconds(60))
        val outcome = send(selected.wire(receipt), UUID.randomUUID())
        if (outcome == MemoryOperationOutcome.APPLIED && selected.change != null) displayed = emptyMap()
        return outcome
    }

    override suspend fun dismiss(actionId: UUID, displayedVersion: Long): MemoryOperationOutcome {
        val selected = displayed[actionId]?.takeIf { it.display.version == displayedVersion } ?: return MemoryOperationOutcome.REJECTED
        if (!channel.captureDismissFromUser()) return MemoryOperationOutcome.UNAVAILABLE
        return send(selected.wire(dismiss = true), UUID.randomUUID())
    }
    override suspend fun processPending(): Int = withContext(Dispatchers.IO) {
        guardStorage { kernel()?.ingress?.drain(25) ?: 0 }
    }
    private suspend fun send(wire: OrionMemoryIngressWire, key: UUID) = withContext(Dispatchers.IO) {
        guardStorage { kernel()?.submit(wire, key) ?: MemoryOperationOutcome.UNAVAILABLE }
    }
    private fun <T> guardStorage(block: () -> T): T = try { block() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
        catch (failure: Exception) { onStorageFailure(); throw failure }

}
