package network.orion.app.reminders

import java.util.concurrent.Callable
import network.orion.core.ingress.StartupBarrier
import network.orion.data.database.OrionDatabase

/** Technical failure only; an enclosing ingress owner must observe it after Room rolls back. */
internal sealed class OrionReminderTransactionBlocked : RuntimeException("Reminder transaction blocked.") {
    class Action(val reason: OrionReminderActionFailure) : OrionReminderTransactionBlocked()
    class Execution(val reason: OrionReminderExecutionFailure) : OrionReminderTransactionBlocked()
}

/**
 * Root calls translate a rolled-back failure to their typed result. Nested calls
 * propagate it to the caller-owned transaction: swallowing a failed nested Room
 * transaction could otherwise report success although SQLite rolls the parent back.
 * Never acquire the admission monitor after acquiring a writer without admission.
 */
internal fun <T : Any> orionReminderTransaction(database: OrionDatabase, barrier: StartupBarrier,
    gated: OrionReminderTransactionBlocked, blocked: (OrionReminderTransactionBlocked) -> T, block: () -> T): T {
    if (database.inTransaction()) {
        check(Thread.holdsLock(barrier)) { "Reminder writer requires prior barrier admission." }
        if (!barrier.isOpen()) throw gated
        return block()
    }
    return barrier.whileOpen {
        try { database.runInTransaction(Callable { block() }) }
        catch (failure: OrionReminderTransactionBlocked) { blocked(failure) }
    } ?: blocked(gated)
}
