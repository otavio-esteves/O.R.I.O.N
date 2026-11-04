package network.orion.core.security

import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class DurableFieldKeyProviderTest {
    private class Storage : WrappedKeyRegistryStorage {
        var bytes: ByteArray? = null
        var failRead = false
        var failWrite = false
        var writes = 0
        override fun read(): ByteArray? {
            if (failRead) throw IOException("fixture")
            return bytes?.copyOf()
        }
        override fun write(bytes: ByteArray) {
            if (failWrite) throw IOException("fixture")
            this.bytes = bytes.copyOf()
            writes++
        }
    }

    /** Real JCA authentication; the fixture KEK exists only in this JVM suite. */
    private class Wrapping : FieldKeyWrapping {
        private val key = SecretKeySpec(ByteArray(32) { 19 }, "AES")
        var present = false
        var failure: FieldKeyProtectionException? = null
        var creates = 0
        var lastPlaintext: ByteArray? = null
        override fun exists() = present
        override fun create() { present = true; creates++ }
        override fun wrap(plaintext: ByteArray): ByteArray {
            failure?.let { throw it }
            lastPlaintext = plaintext // verifies the provider zeros its serialization buffer
            val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            return nonce + cipher.doFinal(plaintext)
        }
        override fun unwrap(ciphertext: ByteArray): ByteArray {
            failure?.let { throw it }
            if (!present) throw FieldKeyProtectionException(false)
            if (ciphertext.size < 28) throw FieldKeyProtectionException(true)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
            return try { cipher.doFinal(ciphertext, 12, ciphertext.size - 12) }
                catch (_: AEADBadTagException) { throw FieldKeyProtectionException(true) }
        }
    }

    private class Fixture {
        val storage = Storage()
        val wrapping = Wrapping()
        fun provider() = DurableFieldKeyProvider(storage, wrapping)
        fun provision(): DurableFieldKeyProvider = provider().also {
            assertEquals(FieldKeyLifecycleResult.APPLIED, it.provisionNewInstallation())
        }
    }

    private fun key(provider: FieldKeyProvider, version: Int? = null): FieldKeyResult.Available =
        (if (version == null) provider.currentKey() else provider.keyForVersion(version)) as FieldKeyResult.Available

    @Test fun `read and rotate of absent registry never provision or write`() {
        val f = Fixture()
        assertEquals(FieldKeyResult.Unavailable, f.provider().currentKey())
        assertEquals(FieldKeyResult.Unavailable, f.provider().keyForVersion(1))
        assertEquals(FieldKeyLifecycleResult.UNAVAILABLE, f.provider().rotate())
        assertEquals(0, f.wrapping.creates)
        assertEquals(0, f.storage.writes)
    }

    @Test fun `explicit provisioning persists wrapped material and survives provider recreation`() {
        val f = Fixture()
        val first = key(f.provision())
        assertEquals(1, first.version)
        assertEquals(32, first.key.encoded.size)
        assertArrayEquals(first.key.encoded, key(f.provider()).key.encoded)
        assertFalse(contains(f.storage.bytes!!, first.key.encoded))
        assertTrue(f.wrapping.lastPlaintext!!.all { it == 0.toByte() })
        assertEquals(FieldKeyLifecycleResult.ALREADY_EXISTS, f.provider().provisionNewInstallation())
        assertEquals(1, f.wrapping.creates)
    }

    @Test fun `rotation retains historical keys and real field ciphertext across reopen`() {
        val f = Fixture()
        val provider = f.provision()
        val old = key(provider)
        val binding = FieldEncryptionContext("task/one", "description", 1)
        val content = "fixture secret".toByteArray()
        val encrypted = (OrionFieldEncryption(provider).encrypt(content, binding) as FieldEncryptionResult.Encrypted).envelope
        assertEquals(FieldKeyLifecycleResult.APPLIED, provider.rotate())
        val fresh = f.provider()
        assertEquals(2, key(fresh).version)
        assertFalse(old.key.encoded.contentEquals(key(fresh).key.encoded))
        assertArrayEquals(old.key.encoded, key(fresh, 1).key.encoded)
        assertEquals(FieldKeyResult.Unavailable, fresh.keyForVersion(3))
        val decrypted = OrionFieldEncryption(fresh).decrypt(encrypted, binding) as FieldDecryptionResult.Plaintext
        assertArrayEquals(content, decrypted.copyBytes())
    }

    @Test fun `lost registry with surviving wrapping key is never reprovisioned`() {
        val f = Fixture()
        f.provision()
        f.storage.bytes = null
        assertEquals(FieldKeyResult.Unavailable, f.provider().currentKey())
        assertEquals(FieldKeyLifecycleResult.ALREADY_EXISTS, f.provider().provisionNewInstallation())
        assertEquals(1, f.wrapping.creates)
        assertEquals(1, f.storage.writes)
    }

    @Test fun `lost wrapping key leaves persisted registry untouched`() {
        val f = Fixture()
        f.provision()
        val original = f.storage.bytes!!.copyOf()
        f.wrapping.present = false
        assertEquals(FieldKeyResult.Unavailable, f.provider().currentKey())
        assertEquals(FieldKeyLifecycleResult.ALREADY_EXISTS, f.provider().provisionNewInstallation())
        assertArrayEquals(original, f.storage.bytes)
        assertEquals(1, f.wrapping.creates)
    }

    @Test fun `invalidated and unavailable wrapping keys block read and rotation without writes`() {
        val f = Fixture()
        f.provision()
        val original = f.storage.bytes!!.copyOf()
        for (invalidated in listOf(false, true)) {
            f.wrapping.failure = FieldKeyProtectionException(invalidated)
            assertEquals(if (invalidated) FieldKeyResult.Invalidated else FieldKeyResult.Unavailable, f.provider().currentKey())
            assertEquals(if (invalidated) FieldKeyLifecycleResult.INVALIDATED else FieldKeyLifecycleResult.UNAVAILABLE,
                f.provider().rotate())
            assertArrayEquals(original, f.storage.bytes)
        }
        assertEquals(1, f.storage.writes)
    }

    @Test fun `tampered registry is not replaced or interpreted as empty`() {
        val f = Fixture()
        f.provision()
        f.storage.bytes!![20] = (f.storage.bytes!![20].toInt() xor 1).toByte()
        val corrupt = f.storage.bytes!!.copyOf()
        assertEquals(FieldKeyResult.Invalidated, f.provider().currentKey())
        assertEquals(FieldKeyLifecycleResult.INVALIDATED, f.provider().rotate())
        assertEquals(FieldKeyLifecycleResult.ALREADY_EXISTS, f.provider().provisionNewInstallation())
        assertArrayEquals(corrupt, f.storage.bytes)
        assertEquals(1, f.storage.writes)
    }

    @Test fun `authenticated malformed and oversized registries fail closed`() {
        val f = Fixture()
        f.provision()
        val badBodies = listOf(ByteArray(0), ByteArray(52),
            byteArrayOf(0x4f, 0x52, 0x4b, 0x52) + ByteArray(48))
        for (body in badBodies) {
            f.storage.bytes = f.wrapping.wrap(body)
            assertEquals(FieldKeyResult.Invalidated, f.provider().currentKey())
        }
        f.storage.bytes = ByteArray(DurableFieldKeyProvider.MAX_REGISTRY_BYTES + 1)
        assertEquals(FieldKeyResult.Invalidated, f.provider().currentKey())
        assertEquals(1, f.storage.writes)
    }

    @Test fun `failed rotation commit preserves previous version and allows safe later rotation`() {
        val f = Fixture()
        val provider = f.provision()
        val original = key(provider)
        f.storage.failWrite = true
        assertEquals(FieldKeyLifecycleResult.UNAVAILABLE, provider.rotate())
        assertArrayEquals(original.key.encoded, key(f.provider()).key.encoded)
        assertEquals(1, key(f.provider()).version)
        f.storage.failWrite = false
        assertEquals(FieldKeyLifecycleResult.APPLIED, f.provider().rotate())
        assertEquals(2, key(f.provider()).version)
    }

    @Test fun `authenticated registry metadata is strict and version cap never evicts keys`() {
        val f = Fixture()
        f.provision()
        fun body(count: Int): ByteArray = ByteBuffer.allocate(16 + count * 36).also { buffer ->
            buffer.putInt(0x4f524b52).putInt(1).putInt(count).putInt(count)
            repeat(count) { index -> buffer.putInt(index + 1).put(ByteArray(32) { 7 }) }
        }.array()
        val currentMismatch = body(1).also { ByteBuffer.wrap(it).putInt(8, 2) }
        val nonSequentialVersion = body(1).also { ByteBuffer.wrap(it).putInt(16, 2) }
        for (invalid in listOf(currentMismatch, nonSequentialVersion, body(1) + byteArrayOf(0))) {
            f.storage.bytes = f.wrapping.wrap(invalid)
            assertEquals(FieldKeyResult.Invalidated, f.provider().currentKey())
        }
        f.storage.bytes = f.wrapping.wrap(body(1024))
        val original = f.storage.bytes!!.copyOf()
        assertEquals(1024, key(f.provider()).version)
        assertEquals(FieldKeyLifecycleResult.VERSION_LIMIT_REACHED, f.provider().rotate())
        assertArrayEquals(original, f.storage.bytes)
        assertEquals(1, key(f.provider(), 1).version)
    }

    @Test fun `failed first commit never reuses or replaces orphan wrapping key`() {
        val f = Fixture()
        f.storage.failWrite = true
        assertEquals(FieldKeyLifecycleResult.UNAVAILABLE, f.provider().provisionNewInstallation())
        f.storage.failWrite = false
        assertEquals(FieldKeyLifecycleResult.ALREADY_EXISTS, f.provider().provisionNewInstallation())
        assertEquals(FieldKeyResult.Unavailable, f.provider().currentKey())
        assertEquals(1, f.wrapping.creates)
    }

    @Test fun `storage read failure cannot trigger key generation`() {
        val f = Fixture()
        f.storage.failRead = true
        assertEquals(FieldKeyResult.Unavailable, f.provider().currentKey())
        assertEquals(FieldKeyLifecycleResult.UNAVAILABLE, f.provider().provisionNewInstallation())
        assertEquals(0, f.wrapping.creates)
    }

    @Test fun `concurrent provider instances serialize rotation and preserve every version`() {
        val f = Fixture()
        val original = key(f.provision())
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll((1..8).map { Callable { f.provider().rotate() } })
            results.forEach { assertEquals(FieldKeyLifecycleResult.APPLIED, it.get()) }
        } finally { executor.shutdownNow() }
        assertEquals(9, key(f.provider()).version)
        assertArrayEquals(original.key.encoded, key(f.provider(), 1).key.encoded)
        (1..9).forEach { assertEquals(it, key(f.provider(), it).version) }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { start -> needle.indices.all { haystack[start + it] == needle[it] } }
}
