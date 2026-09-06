package network.orion.core.queries

/**
 * Port for read-only queries (Architecture V3.2 §9; Master Plan §7.4).
 *
 * Implementations must preserve result types and propagate failures/cancellation.
 * Reads during startup require valid minimum state under the existing readiness
 * contract. This port supplies no mutation path, retry or fallback policy.
 */
interface QueryGateway {
    suspend fun <R> execute(request: Query<R>): R
}
