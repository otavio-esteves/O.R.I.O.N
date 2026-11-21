package network.orion.core.policy.sentinel

/** An enabled, persisted consent revision authorizes only the generic Sentinel notification. */
object OrionProactiveNotificationPolicy {
    fun allows(enabledConsentVersion: Long, startupOpen: Boolean): Boolean =
        startupOpen && enabledConsentVersion >= 0
}
