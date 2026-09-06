package network.orion.core.commands

/**
 * A request to change state with result type [R] (Architecture V3.2 §9).
 *
 * Concrete requests must be immutable and supply the identity/correlation metadata
 * required by their operation. A command can fail. It is not an authorization or
 * an ActionRequest: mutable ingress still passes through IngressCoordinator,
 * Startup Barrier and the applicable policy/action boundaries.
 */
interface Command<out R>
