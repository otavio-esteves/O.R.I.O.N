package network.orion.app.timers

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.orion.core.actions.*
import network.orion.core.commands.timers.*
import network.orion.core.orchestrator.timers.*
import network.orion.core.queries.timers.*
import network.orion.core.time.OrionClock

/** Trusted Activity-scoped adapter. Capture the actual gesture before the first IO suspension. */
internal class OrionTimerUiGateway(private val channel: OrionTimerAdmissionOwner.UiChannel,
    private val kernel: () -> OrionTimerKernel?, private val clock: OrionClock, private val onStorageFailure: () -> Unit = {}) : TimerGateway {
    @Volatile private var displayed: Map<UUID, OrionTimerPending> = emptyMap()

    override suspend fun create(command: CreateTimer): CreateTimerResult {
        val captured = channel.captureCreateFromUser(command)
        if (captured is OrionTimerAdmissionResult.Invalid) return CreateTimerResult.REJECTED
        val timer = (captured as? OrionTimerAdmissionResult.Admitted)?.request?.timer ?: return CreateTimerResult.UNAVAILABLE
        val wire = OrionTimerIngressWire("CREATE", timer.timerId, timer.idempotencyKey, timer.correlationId, timer.title, durationMillis = timer.durationMillis)
        return when (send(wire, timer.idempotencyKey)) {
            TimerOperationOutcome.QUEUED, TimerOperationOutcome.APPLIED -> CreateTimerResult.QUEUED
            TimerOperationOutcome.REJECTED -> CreateTimerResult.REJECTED
            TimerOperationOutcome.UNAVAILABLE -> CreateTimerResult.UNAVAILABLE
        }
    }

    override suspend fun change(command: TimerStatusCommand): TimerStatusResult {
        val captured = channel.captureStatusFromUser(command)
        if (captured is OrionTimerStatusAdmissionResult.Invalid) return TimerStatusResult.REJECTED
        val change = (captured as? OrionTimerStatusAdmissionResult.Admitted)?.request?.change ?: return TimerStatusResult.UNAVAILABLE
        return when (send(OrionTimerIngressWire(change.operation.name, change.timerId, change.idempotencyKey,
            change.correlationId, expectedVersion = change.expectedVersion), change.idempotencyKey)) {
            TimerOperationOutcome.QUEUED, TimerOperationOutcome.APPLIED -> TimerStatusResult.QUEUED
            TimerOperationOutcome.REJECTED -> TimerStatusResult.REJECTED
            TimerOperationOutcome.UNAVAILABLE -> TimerStatusResult.UNAVAILABLE
        }
    }

    override suspend fun list(request: GetTimers): TimerPage? = withContext(Dispatchers.IO) { kernel()?.query?.get(request) }
    override suspend fun status(request: GetTimerStatus): TimerSummary? = withContext(Dispatchers.IO) { kernel()?.query?.get(request) }

    override suspend fun pending(): List<TimerConfirmation> {
        val selected = withContext(Dispatchers.IO) { kernel()?.pending() ?: emptyList() }
        displayed = selected.associateBy { it.display.actionId }
        return selected.map { it.display }
    }

    override suspend fun confirm(actionId: UUID, displayedVersion: Long): TimerOperationOutcome {
        val selected = displayed[actionId]?.takeIf { it.display.version == displayedVersion && it.display.title != null }
            ?: return TimerOperationOutcome.REJECTED
        val normalized = selected.create?.let { p ->
            if (channel.captureCreateFromUser(CreateTimer(p.timer.timerId, p.timer.title, p.timer.durationMillis, p.timer.idempotencyKey,
                    p.timer.correlationId)) !is OrionTimerAdmissionResult.Admitted) return TimerOperationOutcome.UNAVAILABLE
            OrionCreateTimerActionStore.normalized(p.actionId, p.timer, p.policyVersion)
        } ?: selected.change?.let { p ->
            val c = p.change
            val command = CancelTimer(c.timerId, c.expectedVersion, c.idempotencyKey, c.correlationId)
            if (channel.captureStatusFromUser(command) !is OrionTimerStatusAdmissionResult.Admitted) return TimerOperationOutcome.UNAVAILABLE
            OrionTimerStatusActionStore.normalized(p.actionId, c, p.policyVersion)
        } ?: return TimerOperationOutcome.REJECTED
        // Recorded now, at the live callback; persisted replay must retain these exact times.
        val receipt = ActionConfirmationBinding(clock).recordConfirmation(normalized, clock.wallNow().plusSeconds(60))
        return send(selected.wire(receipt), UUID.randomUUID())
    }

    override suspend fun dismiss(actionId: UUID, displayedVersion: Long): TimerOperationOutcome {
        val selected = displayed[actionId]?.takeIf { it.display.version == displayedVersion } ?: return TimerOperationOutcome.REJECTED
        if (!channel.captureDismissFromUser()) return TimerOperationOutcome.UNAVAILABLE
        return send(selected.wire(dismiss = true), UUID.randomUUID())
    }
    override suspend fun processPending(): Int = withContext(Dispatchers.IO) {
        guardStorage { kernel()?.ingress?.drain(25) ?: 0 }
    }
    private suspend fun send(wire: OrionTimerIngressWire, key: UUID) = withContext(Dispatchers.IO) {
        guardStorage { kernel()?.submit(wire, key) ?: TimerOperationOutcome.UNAVAILABLE }
    }
    private fun <T> guardStorage(block: () -> T): T = try { block() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
        catch (failure: Exception) { onStorageFailure(); throw failure }

}
