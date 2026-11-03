package network.orion.core.model

/** Static functional capability ID, not an Android permission or an IPC feature. */
@JvmInline
value class Capability(val id: String) {
    init {
        require(id.isNotBlank() && id == id.trim() && id.none(Char::isISOControl)) {
            "Capability ID must be nonblank, unpadded and contain no control characters."
        }
    }
}

/** Hard policy remains authoritative even when confirmation is context dependent. */
enum class ConfirmationPolicy { ALWAYS, POLICY_DEPENDENT }

/** Revalidate against current permission, role, OS state and app settings before effects. */
fun interface CapabilityRegistry {
    suspend fun isAvailable(capability: Capability): Boolean
}

/** No implemented functional capabilities are advertised by the Foundation. */
class UnavailableCapabilityRegistry : CapabilityRegistry {
    override suspend fun isAvailable(capability: Capability): Boolean = false
}
