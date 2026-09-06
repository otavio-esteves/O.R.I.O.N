package network.orion.core.commands

/**
 * Port for dispatching commands (Architecture V3.2 §9; Master Plan §7.4).
 *
 * Implementations must preserve the request's result type and report failures to
 * the caller, including coroutine cancellation. This port supplies no retry,
 * error-to-success fallback, execution authority or bypass of gated ingress.
 */
interface CommandDispatcher {
    suspend fun <R> dispatch(request: Command<R>): R
}
