package network.orion.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import network.orion.ai.api.OrionInferenceFailure
import network.orion.ai.api.OrionInferenceStage
import network.orion.ai.api.OrionInferenceStatus
import network.orion.ai.api.OrionInferenceTerminal
import network.orion.ai.api.OrionIssuedRequestHistory

/** Bounded ephemeral identity ownership shared by active, queued and terminal requests. */
internal class OrionInferenceRequestLedger {
    private class Entry(val fingerprint: String) { val result = CompletableDeferred<OrionInferenceStatus>() }
    private val monitor = Any()
    private val entries = OrionIssuedRequestHistory<Entry> { it.result.isCompleted }

    fun newRequestId(): String = entries.issue()

    suspend fun runOnce(id: String, fingerprint: String,
        cancelledStatus: () -> OrionInferenceStatus = {
            OrionInferenceStatus(OrionInferenceStage.TERMINAL, OrionInferenceTerminal.CANCELLED, null)
        }, operation: suspend () -> OrionInferenceStatus): OrionInferenceStatus {
        val (entry, owns) = synchronized(monitor) {
            val existing = entries.find(id)
            if (existing != null) {
                check(existing.fingerprint == fingerprint) { "Inference request identity conflict." }
                existing to false
            } else {
                Entry(fingerprint).also { entries.put(id, it) } to true
            }
        }
        if (!owns) return entry.result.await()
        try {
            val status = operation()
            check(status.terminal != null) { "Inference owner must publish a terminal result." }
            entry.result.complete(status)
            return status
        } catch (cancelled: CancellationException) {
            val status = try { cancelledStatus() } catch (_: Exception) {
                OrionInferenceStatus(OrionInferenceStage.TERMINAL, OrionInferenceTerminal.CANCELLED, null)
            }
            entry.result.complete(status)
            throw cancelled
        } catch (failure: Throwable) {
            entry.result.complete(OrionInferenceStatus(OrionInferenceStage.TERMINAL, OrionInferenceTerminal.FAILED,
                OrionInferenceFailure.TRANSPORT_FAILURE))
            throw failure
        }
    }
}
