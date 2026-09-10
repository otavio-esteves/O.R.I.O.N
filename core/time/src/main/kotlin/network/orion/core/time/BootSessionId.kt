package network.orion.core.time

/**
 * Android boot count, stable across app processes/restarts in the same system boot.
 *
 * Valid only on the same device with uninterrupted system-settings continuity.
 * Not globally unique: never restore monotonic references across devices, factory
 * resets or restored system settings. Not a security credential. A missing/untrusted
 * source is represented by null on [MonotonicInstant], never by a fabricated ID.
 */
data class BootSessionId(val bootCount: Int) {
    init {
        require(bootCount >= 0) { "Boot count must be nonnegative." }
    }
}
