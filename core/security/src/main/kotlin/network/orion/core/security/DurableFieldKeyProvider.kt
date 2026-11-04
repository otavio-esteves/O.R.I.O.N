package network.orion.core.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

enum class FieldKeyLifecycleResult { APPLIED, ALREADY_EXISTS, UNAVAILABLE, INVALIDATED, VERSION_LIMIT_REACHED }

/** Only wrapped bytes may cross this storage boundary. Null means absent, never corrupt. */
internal interface WrappedKeyRegistryStorage {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

/** The Android adapter owns the non-exportable wrapping key. Expected failures are typed. */
internal interface FieldKeyWrapping {
    fun exists(): Boolean
    fun create()
    fun wrap(plaintext: ByteArray): ByteArray
    fun unwrap(ciphertext: ByteArray): ByteArray
}

internal class FieldKeyProtectionException(val invalidated: Boolean) : Exception("Field key protection unavailable.")

/**
 * Main-owned registry mechanism. Reads never provision keys. Provisioning is explicit
 * and only legal for an installation whose authoritative encrypted storage is empty.
 * All data-key versions are authenticated inside one wrapped atomic registry.
 */
internal class DurableFieldKeyProvider(
    private val storage: WrappedKeyRegistryStorage,
    private val wrapping: FieldKeyWrapping,
) : FieldKeyProvider {
    override fun currentKey(): FieldKeyResult = lookup(null)

    override fun keyForVersion(version: Int): FieldKeyResult {
        require(version > 0) { "Invalid key version." }
        return lookup(version)
    }

    private fun lookup(version: Int?): FieldKeyResult = synchronized(registryLock) {
        try {
            val registry = load() ?: return@synchronized FieldKeyResult.Unavailable
            try {
                val selected = version ?: registry.current
                val bytes = registry.keys[selected] ?: return@synchronized FieldKeyResult.Unavailable
                FieldKeyResult.Available(selected, SecretKeySpec(bytes, "AES"))
            } finally { registry.clear() }
        } catch (failure: FieldKeyProtectionException) {
            if (failure.invalidated) FieldKeyResult.Invalidated else FieldKeyResult.Unavailable
        } catch (_: IOException) { FieldKeyResult.Unavailable }
    }

    /**
     * Trusted owner must first prove there are no encrypted fields to preserve.
     * An existing wrapping alias with a missing registry is NEVER repaired here.
     * A crash during first provisioning can therefore require explicit manual review.
     */
    fun provisionNewInstallation(): FieldKeyLifecycleResult = synchronized(registryLock) {
        lifecycle {
            if (storage.read() != null || wrapping.exists()) return@lifecycle FieldKeyLifecycleResult.ALREADY_EXISTS
            wrapping.create()
            val registry = Registry(1, linkedMapOf(1 to newKey()))
            try { persist(registry) } finally { registry.clear() }
            FieldKeyLifecycleResult.APPLIED
        }
    }

    /** Commits a new data-key version atomically, retaining all historical keys. */
    fun rotate(): FieldKeyLifecycleResult = synchronized(registryLock) {
        lifecycle {
            val registry = load() ?: return@lifecycle FieldKeyLifecycleResult.UNAVAILABLE
            try {
                if (registry.current >= MAX_VERSIONS) return@lifecycle FieldKeyLifecycleResult.VERSION_LIMIT_REACHED
                val next = registry.current + 1
                registry.keys[next] = newKey()
                persist(Registry(next, registry.keys))
                FieldKeyLifecycleResult.APPLIED
            } finally { registry.clear() }
        }
    }

    private fun lifecycle(operation: () -> FieldKeyLifecycleResult): FieldKeyLifecycleResult = try {
        operation()
    } catch (failure: FieldKeyProtectionException) {
        if (failure.invalidated) FieldKeyLifecycleResult.INVALIDATED else FieldKeyLifecycleResult.UNAVAILABLE
    } catch (_: IOException) { FieldKeyLifecycleResult.UNAVAILABLE }

    private fun load(): Registry? {
        val ciphertext = storage.read() ?: return null
        if (ciphertext.size > MAX_REGISTRY_BYTES) throw FieldKeyProtectionException(true)
        val clear = wrapping.unwrap(ciphertext)
        return try { decode(clear) } finally { clear.fill(0) }
    }

    private fun persist(registry: Registry) {
        val clear = encode(registry)
        val ciphertext = try { wrapping.wrap(clear) } finally { clear.fill(0) }
        if (ciphertext.size > MAX_REGISTRY_BYTES) throw FieldKeyProtectionException(true)
        storage.write(ciphertext)
    }

    private class Registry(val current: Int, val keys: LinkedHashMap<Int, ByteArray>) {
        fun clear() = keys.values.forEach { it.fill(0) }
    }

    private fun encode(registry: Registry): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(REGISTRY_MAGIC)
            out.writeInt(1)
            out.writeInt(registry.current)
            out.writeInt(registry.keys.size)
            registry.keys.forEach { (version, key) -> out.writeInt(version); out.write(key) }
        }
    }.toByteArray()

    private fun decode(bytes: ByteArray): Registry {
        val keys = linkedMapOf<Int, ByteArray>()
        try {
            if (bytes.size !in 52..MAX_REGISTRY_BYTES) throw FieldKeyProtectionException(true)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != REGISTRY_MAGIC || input.readInt() != 1) throw FieldKeyProtectionException(true)
                val current = input.readInt()
                val count = input.readInt()
                if (count !in 1..MAX_VERSIONS || current != count || bytes.size != 16 + count * 36) {
                    throw FieldKeyProtectionException(true)
                }
                repeat(count) { index ->
                    if (input.readInt() != index + 1) throw FieldKeyProtectionException(true)
                    keys[index + 1] = ByteArray(32).also(input::readFully)
                }
                return Registry(current, keys)
            }
        } catch (failure: Exception) {
            keys.values.forEach { it.fill(0) }
            throw failure
        }
    }

    companion object {
        // Serializes all instances in MAIN, including rotation versus read/provision.
        private val registryLock = Any()
        private const val REGISTRY_MAGIC = 0x4f524b52
        private const val MAX_VERSIONS = 1024
        internal const val MAX_REGISTRY_BYTES = 64 * 1024
        private val random = SecureRandom()
        private fun newKey(): ByteArray = ByteArray(32).also(random::nextBytes)
    }
}
