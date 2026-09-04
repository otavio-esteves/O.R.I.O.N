package network.orion.core.common.process

fun interface OrionProcessInitializer {
    fun initialize()
}

class OrionProcessBootstrapper(
    private val initializers: Map<OrionProcessRole, OrionProcessInitializer>,
) {
    init {
        require(initializers.keys == OrionProcessRole.entries.toSet()) {
            "Every process role must have exactly one initializer"
        }
    }

    fun bootstrap(role: OrionProcessRole) {
        checkNotNull(initializers[role]) { "No initializer registered for $role" }.initialize()
    }
}
