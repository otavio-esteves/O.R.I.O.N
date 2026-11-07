package network.orion.app.scheduling

import java.util.UUID
import network.orion.app.reminderdelivery.OrionReminderDeliveryAdmissions
import network.orion.app.reminderdelivery.OrionReminderDeliveryPipeline
import network.orion.core.orchestrator.reminders.*

/** Activity-owned lifecycle adapter. A stale graph/channel cannot manufacture a new confirmation. */
internal class OrionReminderDeliveryUiGateway(
    private val pipeline: () -> OrionReminderDeliveryPipeline?,
    private val onEnabled: suspend (UUID) -> Unit,
    private val onProcess: suspend () -> Unit,
) : ReminderDeliveryGateway {
    private var resumed = false
    private var focused = false
    private var closed = false
    private var bound: OrionReminderDeliveryPipeline? = null
    private var channel: OrionReminderDeliveryAdmissions.UiChannel? = null
    private var delegate: ReminderDeliveryGateway? = null
    fun onResumed(value: Boolean) { resumed = value; channel?.onResumed(value); if (value) current() }
    fun onWindowFocus(value: Boolean) { focused = value; channel?.onWindowFocus(value); if (value) current() }
    fun onCoreRecovered() { current() }
    override suspend fun processPending() { if (current() != null) onProcess() }
    fun close() { closed = true; channel?.close(); delegate = null }
    private fun current(): ReminderDeliveryGateway? {
        if (closed || !resumed || !focused) return null
        val graph = pipeline() ?: return null
        if (bound !== graph) {
            channel?.close()
            val next = graph.openUiChannel().also { it.onResumed(resumed); it.onWindowFocus(focused) }
            channel = next; bound = graph; delegate = graph.gateway(next)
        }
        return delegate
    }
    override suspend fun prepare(reminderId: UUID, expectedVersion: Long): ReminderDeliveryPreparation =
        current()?.prepare(reminderId, expectedVersion) ?: ReminderDeliveryPreparation.Blocked(ReminderDeliveryOutcome.UNAVAILABLE)
    override suspend fun prepareSeries(reminderId: UUID, expectedVersion: Long,
        rule: network.orion.core.model.OrionReminderRecurrenceRule): ReminderSeriesPreparation =
        current()?.prepareSeries(reminderId, expectedVersion, rule)
            ?: ReminderSeriesPreparation.Blocked(ReminderDeliveryOutcome.UNAVAILABLE)
    override suspend fun confirmSeries(preview: ReminderSeriesPreview): ReminderDeliveryOutcome {
        if (pipeline() !== bound) return ReminderDeliveryOutcome.REJECTED
        val result = current()?.confirmSeries(preview) ?: ReminderDeliveryOutcome.UNAVAILABLE
        if (result == ReminderDeliveryOutcome.ENABLED) {
            try { onEnabled(preview.occurrences.first().delivery.reminderId) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* The committed authorization remains valid; Core-owned reconciliation also observes it. */ }
        }
        return result
    }
    override suspend fun confirm(preview: ReminderDeliveryPreview): ReminderDeliveryOutcome {
        // Confirm only on the same graph that produced the displayed preview.
        if (pipeline() !== bound) return ReminderDeliveryOutcome.REJECTED
        val result = current()?.confirm(preview) ?: ReminderDeliveryOutcome.UNAVAILABLE
        if (result == ReminderDeliveryOutcome.ENABLED) {
            try { onEnabled(preview.reminderId) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Do not misreport already committed human consent as rejected. */ }
        }
        return result
    }
}
