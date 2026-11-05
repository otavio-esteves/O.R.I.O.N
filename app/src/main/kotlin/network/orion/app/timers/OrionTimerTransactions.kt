package network.orion.app.timers

import java.util.concurrent.Callable
import network.orion.core.ingress.StartupBarrier
import network.orion.data.database.OrionDatabase

/** Technical failure only; an enclosing ingress owner must observe it after Room rolls back. */
internal sealed class OrionTimerTransactionBlocked : RuntimeException("Timer transaction blocked.") {
    class Action(val reason: OrionTimerActionFailure) : OrionTimerTransactionBlocked()
    class Execution(val reason: OrionTimerExecutionFailure) : OrionTimerTransactionBlocked()
}

/**
 * Root calls translate a rolled-back failure to their typed result. Nested calls
 * propagate it to the caller-owned transaction: swallowing a failed nested Room
 * transaction could otherwise report success although SQLite rolls the parent back.
 * Never acquire the admission monitor after acquiring a writer without admission.
 */
internal fun <T : Any> orionTimerTransaction(database: OrionDatabase, barrier: StartupBarrier,
    gated: OrionTimerTransactionBlocked, blocked: (OrionTimerTransactionBlocked) -> T, block: () -> T): T {
    if (database.inTransaction()) {
        check(Thread.holdsLock(barrier)) { "Timer writer requires prior barrier admission." }
        if (!barrier.isOpen()) throw gated
        return block()
    }
    return barrier.whileOpen {
        try { database.runInTransaction(Callable { block() }) }
        catch (failure: OrionTimerTransactionBlocked) { blocked(failure) }
    } ?: blocked(gated)
}
