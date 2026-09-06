package network.orion.core.queries

/**
 * A read-only request with result type [R] (Architecture V3.2 §9).
 *
 * Concrete requests must be immutable. Query handlers must not mutate business
 * state or execute side effects. This contract is separate from Command and from
 * event publication; a failed read is not an implicit request for a mutation.
 */
interface Query<out R>
