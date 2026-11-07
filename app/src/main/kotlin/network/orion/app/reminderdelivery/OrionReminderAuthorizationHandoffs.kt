package network.orion.app.reminderdelivery

import java.util.UUID
import network.orion.core.actions.ActionStatus
import network.orion.core.ingress.StartupBarrier
import network.orion.data.database.DurableIngressStatus
import network.orion.data.database.OrionDatabase

/** In-memory post-commit wakeups only; Room and startup reconciliation remain authoritative. */
internal class OrionReminderAuthorizationHandoffs(private val db: OrionDatabase,
    private val barrier: StartupBarrier, private val onAuthorized: (UUID) -> Unit) {
    private data class Target(val reminderId: UUID, val actionId: UUID)
    private val pending = LinkedHashMap<UUID, Target>()

    /** Registration can roll back. A flusher must verify the durable CONSUMED receipt before signalling. */
    fun remember(ingressId: UUID, reminderId: UUID, actionId: UUID): Boolean = synchronized(barrier) {
        check(db.inTransaction())
        if (pending.size >= MAX_PENDING && ingressId !in pending) return@synchronized false
        pending[ingressId] = Target(reminderId, actionId)
        true
    }

    fun flush() {
        // Recovery may drain under completeRecovery's outer monitor; retain wakeups for its first outside drain.
        if (db.inTransaction() || Thread.holdsLock(barrier)) return
        val signals = synchronized(barrier) {
            val remove = ArrayList<UUID>()
            val ready = ArrayList<UUID>()
            for ((ingressId, target) in pending) {
                when (db.ingress().find(ingressId.toString())?.status) {
                    DurableIngressStatus.CONSUMED -> {
                        remove += ingressId
                        val row = db.reminders().find(target.reminderId.toString())
                        if (row?.deliveryActionId == target.actionId.toString() &&
                            db.actions().find(target.actionId.toString())?.status == ActionStatus.CONFIRMED)
                            ready += target.reminderId
                    }
                    null, DurableIngressStatus.REJECTED, DurableIngressStatus.EXPIRED, DurableIngressStatus.FAILED -> remove += ingressId
                    else -> Unit // A rolled-back queued confirmation cannot produce a wakeup.
                }
            }
            remove.forEach { pending.remove(it) }
            ready.distinct()
        }
        check(!db.inTransaction() && !Thread.holdsLock(barrier))
        signals.forEach(onAuthorized) // Core-owned scope launch only; no inline external delivery.
    }

    companion object { const val MAX_PENDING = 1000 }
}
