package network.orion.core.common.process

enum class StorageAccessDeniedReason { NON_MAIN_PROCESS, CREDENTIAL_STORAGE_UNAVAILABLE }

/** Typed denial distinguishes process ownership from unrelated Room/provider failures. */
class OrionStorageAccessDeniedException internal constructor(val reason: StorageAccessDeniedReason) :
    IllegalStateException("Main-process credential storage access denied: $reason")

/** Check before creating any Room/DataStore instance or file (Architecture §§6, 46). */
object MainProcessStoragePolicy {
    fun requireAccess(role: OrionProcessRole, userUnlocked: Boolean, deviceProtectedStorage: Boolean) {
        if (role != OrionProcessRole.MAIN) {
            throw OrionStorageAccessDeniedException(StorageAccessDeniedReason.NON_MAIN_PROCESS)
        }
        if (!userUnlocked || deviceProtectedStorage) {
            throw OrionStorageAccessDeniedException(StorageAccessDeniedReason.CREDENTIAL_STORAGE_UNAVAILABLE)
        }
    }
}
