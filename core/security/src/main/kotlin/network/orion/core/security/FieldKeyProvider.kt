package network.orion.core.security

import javax.crypto.SecretKey

/**
 * Data-key access only. Production provisioning/persistence must protect or wrap
 * these keys with Android Keystore. Implementations must not silently recreate
 * missing keys or reuse a version for different key material.
 *
 * Map expected provider/Keystore failures to Unavailable or Invalidated. Lookups
 * happen outside the cipher exception boundary: unexpected programming/fatal
 * errors propagate and are never converted into successful encryption/decryption.
 */
interface FieldKeyProvider {
    fun currentKey(): FieldKeyResult
    fun keyForVersion(version: Int): FieldKeyResult
}

sealed interface FieldKeyResult {
    class Available(val version: Int, val key: SecretKey) : FieldKeyResult {
        init { require(version > 0) { "Invalid key version." } }
        override fun toString(): String = "FieldKey(<redacted>)"
    }
    data object Unavailable : FieldKeyResult
    data object Invalidated : FieldKeyResult
}
