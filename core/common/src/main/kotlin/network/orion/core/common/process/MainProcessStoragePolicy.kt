package network.orion.core.common.process

/** Check before creating any Room/DataStore instance or file (Architecture §§6, 46). */
object MainProcessStoragePolicy {
    fun requireAccess(role: OrionProcessRole, userUnlocked: Boolean, deviceProtectedStorage: Boolean) {
        check(role == OrionProcessRole.MAIN) { "Only MAIN owns application storage." }
        check(userUnlocked && !deviceProtectedStorage) { "Credential-protected storage requires user unlock." }
    }
}
