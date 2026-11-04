package network.orion.core.ingress

import network.orion.core.health.CoreReadiness

enum class StartupRecoveryCompletion { RELEASED, BLOCKED, SUPERSEDED }

/** Operational gate, independent of the health snapshot. Main Core owns this instance. */
class StartupBarrier {
    class RecoveryTicket internal constructor(internal val owner: StartupBarrier, internal val epoch: Long)
    private var epoch = 0L
    private var open = false

    @Synchronized fun beginRecovery(): RecoveryTicket {
        open = false
        epoch = Math.addExact(epoch, 1)
        return RecoveryTicket(this, epoch)
    }

    /** Only the recovery coordinator may attest completion; a stale recovery cannot reopen the gate. */
    @Synchronized fun releaseAfterRecovery(ticket: RecoveryTicket, readiness: CoreReadiness): Boolean {
        if (!owns(ticket) || !readiness.isStartupBarrierOpen) return false
        open = true
        return true
    }

    /**
     * Publication, release and bounded local drain share the admission lock, so a
     * newer recovery cannot supersede this ticket between validation and publication.
     * Callbacks must never execute external effects or unbounded work.
     */
    @Synchronized fun completeRecovery(
        ticket: RecoveryTicket,
        calculateAndPublishHealth: () -> CoreReadiness,
        openIngressAndDrain: () -> Unit,
    ): StartupRecoveryCompletion {
        if (!owns(ticket)) return StartupRecoveryCompletion.SUPERSEDED
        val readiness = calculateAndPublishHealth()
        // Also defend against a callback re-entering the barrier on the same thread.
        if (!owns(ticket)) return StartupRecoveryCompletion.SUPERSEDED
        if (!readiness.isStartupBarrierOpen) {
            open = false
            return StartupRecoveryCompletion.BLOCKED
        }
        open = true
        openIngressAndDrain()
        return if (owns(ticket) && open) StartupRecoveryCompletion.RELEASED else StartupRecoveryCompletion.SUPERSEDED
    }

    /** A stale failure/cancellation must not close a newer recovery's gate. */
    @Synchronized fun abortRecovery(ticket: RecoveryTicket): Boolean {
        if (!owns(ticket)) return false
        open = false
        epoch = Math.addExact(epoch, 1)
        return true
    }

    private fun owns(ticket: RecoveryTicket): Boolean = ticket.owner === this && ticket.epoch == epoch

    @Synchronized fun isOpen(): Boolean = open

    /** Gate closure waits for an admitted bounded local transaction to finish. Never run external effects here. */
    @Synchronized fun <T> whileOpen(block: () -> T): T? = if (open) block() else null
}
