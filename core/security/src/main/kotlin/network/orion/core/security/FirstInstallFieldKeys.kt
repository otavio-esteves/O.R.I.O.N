package network.orion.core.security

enum class FieldKeyInitialization { READY, EXISTING_DATA, UNAVAILABLE, INVALIDATED }

/**
 * Main startup only. The attempt marker must commit before provisioning. A crash
 * between these steps requires explicit remediation, never automatic recreation.
 * The storage owner must check emptiness and provision under its transaction lock.
 */
class FirstInstallFieldKeys(
    private val keys: FieldKeyProvider,
    private val attempted: suspend () -> Boolean,
    private val markAttempted: suspend () -> Unit,
    private val provisionIfStorageEmpty: () -> FieldKeyLifecycleResult?,
) {
    suspend fun initialize(): FieldKeyInitialization {
        when (keys.currentKey()) {
            is FieldKeyResult.Available -> {
                if (!attempted()) markAttempted()
                return FieldKeyInitialization.READY
            }
            FieldKeyResult.Invalidated -> return FieldKeyInitialization.INVALIDATED
            FieldKeyResult.Unavailable -> Unit
        }
        if (attempted()) return FieldKeyInitialization.UNAVAILABLE
        markAttempted()
        return when (provisionIfStorageEmpty()) {
            null -> FieldKeyInitialization.EXISTING_DATA
            FieldKeyLifecycleResult.INVALIDATED -> FieldKeyInitialization.INVALIDATED
            FieldKeyLifecycleResult.APPLIED, FieldKeyLifecycleResult.ALREADY_EXISTS -> when (keys.currentKey()) {
                is FieldKeyResult.Available -> FieldKeyInitialization.READY
                FieldKeyResult.Invalidated -> FieldKeyInitialization.INVALIDATED
                FieldKeyResult.Unavailable -> FieldKeyInitialization.UNAVAILABLE
            }
            else -> FieldKeyInitialization.UNAVAILABLE
        }
    }
}
