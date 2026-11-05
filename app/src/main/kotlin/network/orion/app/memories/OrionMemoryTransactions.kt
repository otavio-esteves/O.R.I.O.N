package network.orion.app.memories

import java.util.concurrent.Callable
import network.orion.core.ingress.StartupBarrier
import network.orion.data.database.OrionDatabase

/** Technical failure only; an enclosing ingress owner must observe it after Room rolls back. */
internal sealed class OrionMemoryTransactionBlocked : RuntimeException("Memory transaction blocked.") {
    class Action(val reason: OrionMemoryActionFailure) : OrionMemoryTransactionBlocked()
    class Execution(val reason: OrionMemoryExecutionFailure) : OrionMemoryTransactionBlocked()
}

/**
 * Root calls translate a rolled-back failure to their typed result. Nested calls
 * propagate it to the caller-owned transaction: swallowing a failed nested Room
 * transaction could otherwise report success although SQLite rolls the parent back.
 * Never acquire the admission monitor after acquiring a writer without admission.
 */
internal fun <T : Any> orionMemoryTransaction(database: OrionDatabase, barrier: StartupBarrier,
    gated: OrionMemoryTransactionBlocked, blocked: (OrionMemoryTransactionBlocked) -> T, block: () -> T): T {
    if (database.inTransaction()) {
        check(Thread.holdsLock(barrier)) { "Memory writer requires prior barrier admission." }
        if (!barrier.isOpen()) throw gated
        return block()
    }
    return barrier.whileOpen {
        try { database.runInTransaction(Callable { block() }) }
        catch (failure: OrionMemoryTransactionBlocked) { blocked(failure) }
    } ?: blocked(gated)
}
