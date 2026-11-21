package network.orion.core.policy.sentinel

import network.orion.core.skills.metadata.notifications.OrionProactiveNotificationDescriptor

/** The standing opt-in authorizes only Sentinel's generic, locally generated notification. */
object OrionProactiveNotificationPolicy {
    fun allows(optInAndEligible: Boolean, startupOpen: Boolean, source: String): Boolean =
        optInAndEligible && startupOpen && source == OrionProactiveNotificationDescriptor.SOURCE
}
