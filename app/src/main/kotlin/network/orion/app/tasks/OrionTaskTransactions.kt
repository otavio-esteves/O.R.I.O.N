package network.orion.app.tasks

import java.util.concurrent.Callable
import network.orion.core.ingress.StartupBarrier
import network.orion.data.database.OrionDatabase

/** Technical failure only; an enclosing ingress owner must observe it after Room rolls back. */
internal sealed class OrionTaskTransactionBlocked : RuntimeException("Task transaction blocked.") {
    class Action(val reason: OrionTaskActionFailure) : OrionTaskTransactionBlocked()
    class Execution(val reason: OrionTaskExecutionFailure) : OrionTaskTransactionBlocked()
}

/**
 * Root calls translate a rolled-back failure to their typed result. Nested calls
 * propagate it to the caller-owned transaction: swallowing a failed nested Room
 * transaction could otherwise report success although SQLite rolls the parent back.
 * Never acquire the admission monitor after acquiring a writer without admission.
 */
internal fun <T : Any> orionTaskTransaction(database: OrionDatabase, barrier: StartupBarrier,
    gated: OrionTaskTransactionBlocked, blocked: (OrionTaskTransactionBlocked) -> T, block: () -> T): T {
    if (database.inTransaction()) {
        check(Thread.holdsLock(barrier)) { "Task writer requires prior barrier admission." }
        if (!barrier.isOpen()) throw gated
        return block()
    }
    return barrier.whileOpen {
        try { database.runInTransaction(Callable { block() }) }
        catch (failure: OrionTaskTransactionBlocked) { blocked(failure) }
    } ?: blocked(gated)
}
