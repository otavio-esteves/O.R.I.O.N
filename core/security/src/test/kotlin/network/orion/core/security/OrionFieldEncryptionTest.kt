package network.orion.core.security

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class OrionFieldEncryptionTest {
    private val key1 = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 7 }, "AES"))
    private val key2 = FieldKeyResult.Available(2, SecretKeySpec(ByteArray(32) { 11 }, "AES"))
    private val context = FieldEncryptionContext("task/42", "description", 1)
    private val secret = "private field content".toByteArray()

    private class Keys(var current: FieldKeyResult, val versions: MutableMap<Int, FieldKeyResult>) : FieldKeyProvider {
        override fun currentKey(): FieldKeyResult = current
        override fun keyForVersion(version: Int): FieldKeyResult = versions[version] ?: FieldKeyResult.Unavailable
    }

    private fun keys() = Keys(key1, mutableMapOf(1 to key1, 2 to key2))
    private fun encrypt(engine: OrionFieldEncryption, value: ByteArray = secret,
                        binding: FieldEncryptionContext = context): FieldEnvelope =
        (engine.encrypt(value, binding) as FieldEncryptionResult.Encrypted).envelope

    private fun plaintext(result: FieldDecryptionResult): ByteArray =
        (result as FieldDecryptionResult.Plaintext).copyBytes()

    @Test fun `v1 aad authenticates independent openssl generated fixture`() {
        // Node 22.23.3 / OpenSSL 3.5.8, generated outside the JVM; fixture-only key.
        val key = FieldKeyResult.Available(7, SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
        val provider = Keys(key, mutableMapOf(7 to key))
        val ciphertext = "216bae6fb097a736e22ffbf2918d1919e28738d5484c2dd8483ce0cef1de5f5e74"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val envelope = FieldEnvelope(1, 7, "AES_GCM", 1, ByteArray(12) { it.toByte() }, ciphertext)
        val binding = FieldEncryptionContext("action/fixture", "arguments", 3)
        assertArrayEquals("fixture-only data".toByteArray(),
            plaintext(OrionFieldEncryption(provider).decrypt(envelope, binding)))
    }

    @Test fun `round trip uses explicit versioned envelope and permits empty fields`() {
        val engine = OrionFieldEncryption(keys())
        val envelope = encrypt(engine)
        assertEquals(1, envelope.ciphertextVersion)
        assertEquals(1, envelope.keyVersion)
        assertEquals("AES_GCM", envelope.algorithm)
        assertEquals(1, envelope.aadSchemaVersion)
        assertEquals(12, envelope.copyNonce().size)
        assertEquals(secret.size + 16, envelope.copyCiphertextAndTag().size)
        assertFalse(envelope.copyCiphertextAndTag().contentEquals(secret))
        assertArrayEquals(secret, plaintext(engine.decrypt(envelope, context)))
        assertArrayEquals(byteArrayOf(), plaintext(engine.decrypt(encrypt(engine, byteArrayOf()), context)))
    }

    @Test fun `entity field and schema changes fail authentication`() {
        val engine = OrionFieldEncryption(keys())
        val envelope = encrypt(engine)
        for (changed in listOf(FieldEncryptionContext("task/43", "description", 1),
            FieldEncryptionContext("task/42", "title", 1), FieldEncryptionContext("task/42", "description", 2))) {
            assertEquals(FieldCryptoFailure.AUTHENTICATION_FAILED,
                (engine.decrypt(envelope, changed) as FieldDecryptionResult.Failure).reason)
        }
    }

    @Test fun `aad encoding distinguishes concatenation collisions and unicode`() {
        val engine = OrionFieldEncryption(keys())
        val left = FieldEncryptionContext("ab", "c", 1)
        val right = FieldEncryptionContext("a", "bc", 1)
        assertTrue(engine.decrypt(encrypt(engine, binding = left), right) is FieldDecryptionResult.Failure)
        val unicode = FieldEncryptionContext("memória/Ω", "descrição", 2)
        assertArrayEquals(secret, plaintext(engine.decrypt(encrypt(engine, binding = unicode), unicode)))
        assertThrows(IllegalArgumentException::class.java) { FieldEncryptionContext("\uD800", "field", 1) }
    }

    @Test fun `modified nonce ciphertext tag and key metadata never produce plaintext`() {
        val provider = keys()
        provider.versions[2] = FieldKeyResult.Available(2, key1.key)
        val engine = OrionFieldEncryption(provider)
        val envelope = encrypt(engine)
        fun changed(nonce: ByteArray = envelope.copyNonce(), ciphertext: ByteArray = envelope.copyCiphertextAndTag(),
                    version: Int = envelope.keyVersion) = FieldEnvelope(1, version, "AES_GCM", 1, nonce, ciphertext)
        val variants = listOf(
            changed(nonce = envelope.copyNonce().also { it[0] = (it[0].toInt() xor 1).toByte() }),
            changed(ciphertext = envelope.copyCiphertextAndTag().also { it[0] = (it[0].toInt() xor 1).toByte() }),
            changed(ciphertext = envelope.copyCiphertextAndTag().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }),
            changed(version = 2),
        )
        for (modified in variants) {
            assertEquals(FieldCryptoFailure.AUTHENTICATION_FAILED,
                (engine.decrypt(modified, context) as FieldDecryptionResult.Failure).reason)
        }
    }

    @Test fun `rotation writes new version and reads retained previous versions`() {
        val provider = keys()
        val engine = OrionFieldEncryption(provider)
        val old = encrypt(engine)
        provider.current = key2
        val newer = encrypt(engine)
        assertEquals(2, newer.keyVersion)
        assertArrayEquals(secret, plaintext(engine.decrypt(old, context)))
        assertArrayEquals(secret, plaintext(engine.decrypt(newer, context)))
        provider.versions.remove(1)
        assertEquals(FieldCryptoFailure.KEY_UNAVAILABLE,
            (engine.decrypt(old, context) as FieldDecryptionResult.Failure).reason)
    }

    @Test fun `unavailable and invalidated keys are explicit and never regenerated`() {
        val provider = keys()
        val engine = OrionFieldEncryption(provider)
        val envelope = encrypt(engine)
        for ((key, failure) in listOf(FieldKeyResult.Unavailable to FieldCryptoFailure.KEY_UNAVAILABLE,
            FieldKeyResult.Invalidated to FieldCryptoFailure.KEY_INVALIDATED)) {
            provider.current = key
            provider.versions[1] = key
            assertEquals(failure, (engine.encrypt(secret, context) as FieldEncryptionResult.Failure).reason)
            assertEquals(failure, (engine.decrypt(envelope, context) as FieldDecryptionResult.Failure).reason)
        }
    }

    @Test fun `wrong provider version and wrong key fail closed`() {
        val provider = keys()
        val engine = OrionFieldEncryption(provider)
        val envelope = encrypt(engine)
        provider.versions[1] = key2
        assertEquals(FieldCryptoFailure.KEY_UNAVAILABLE,
            (engine.decrypt(envelope, context) as FieldDecryptionResult.Failure).reason)
        provider.versions[1] = FieldKeyResult.Available(1, key2.key)
        assertEquals(FieldCryptoFailure.AUTHENTICATION_FAILED,
            (engine.decrypt(envelope, context) as FieldDecryptionResult.Failure).reason)
    }

    @Test fun `unsupported versions and algorithm fail before key lookup`() {
        val engine = OrionFieldEncryption(keys())
        val original = encrypt(engine)
        val provider = object : FieldKeyProvider {
            override fun currentKey(): FieldKeyResult = error("Unexpected lookup")
            override fun keyForVersion(version: Int): FieldKeyResult = error("Unexpected lookup")
        }
        val checking = OrionFieldEncryption(provider)
        for (envelope in listOf(
            FieldEnvelope(2, 1, "AES_GCM", 1, original.copyNonce(), original.copyCiphertextAndTag()),
            FieldEnvelope(1, 1, "OTHER", 1, original.copyNonce(), original.copyCiphertextAndTag()),
            FieldEnvelope(1, 1, "AES_GCM", 2, original.copyNonce(), original.copyCiphertextAndTag()),
        )) {
            assertEquals(FieldCryptoFailure.UNSUPPORTED_ENVELOPE,
                (checking.decrypt(envelope, context) as FieldDecryptionResult.Failure).reason)
        }
    }

    @Test fun `envelope and plaintext use defensive copies and redacted strings`() {
        val engine = OrionFieldEncryption(keys())
        val original = encrypt(engine)
        val nonce = original.copyNonce()
        val cipher = original.copyCiphertextAndTag()
        val restored = FieldEnvelope(1, 1, "AES_GCM", 1, nonce, cipher)
        nonce.fill(0)
        cipher.fill(0)
        restored.copyNonce().fill(0)
        restored.copyCiphertextAndTag().fill(0)
        val result = engine.decrypt(restored, context) as FieldDecryptionResult.Plaintext
        result.copyBytes().fill(0)
        assertArrayEquals(secret, result.copyBytes())
        assertEquals("FieldEnvelope(<redacted>)", restored.toString())
        assertEquals("FieldPlaintext(<redacted>)", result.toString())
        assertEquals("FieldEncryptionContext(<redacted>)", context.toString())
        assertEquals("FieldKey(<redacted>)", key1.toString())
    }

    @Test fun `secure random nonces differ across engine instances sharing a key`() {
        val seen = mutableSetOf<List<Byte>>()
        repeat(1000) {
            assertTrue(seen.add(encrypt(OrionFieldEncryption(keys())).copyNonce().toList()))
        }
    }

    @Test fun `malformed envelopes contexts and oversized fields are rejected`() {
        val engine = OrionFieldEncryption(keys())
        assertEquals(FieldCryptoFailure.FIELD_TOO_LARGE,
            (engine.encrypt(ByteArray(1_048_577), context) as FieldEncryptionResult.Failure).reason)
        assertThrows(IllegalArgumentException::class.java) { FieldEncryptionContext("", "field", 1) }
        assertThrows(IllegalArgumentException::class.java) { FieldEncryptionContext("id", "field", 0) }
        assertThrows(IllegalArgumentException::class.java) { FieldEnvelope(1, 0, "AES_GCM", 1, ByteArray(12), ByteArray(16)) }
        assertThrows(IllegalArgumentException::class.java) { FieldEnvelope(1, 1, "AES_GCM", 1, ByteArray(11), ByteArray(16)) }
        assertThrows(IllegalArgumentException::class.java) { FieldEnvelope(1, 1, "AES_GCM", 1, ByteArray(12), ByteArray(15)) }
    }
}
