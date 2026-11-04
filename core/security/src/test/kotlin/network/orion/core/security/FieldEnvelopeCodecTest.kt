package network.orion.core.security

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class FieldEnvelopeCodecTest {
    private val key = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 3 }, "AES"))
    private val engine = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey(): FieldKeyResult = key
        override fun keyForVersion(version: Int): FieldKeyResult = key
    })
    private val context = FieldEncryptionContext("action/17", "arguments", 1)

    private fun envelope() =
        (engine.encrypt("private payload".toByteArray(), context) as FieldEncryptionResult.Encrypted).envelope

    @Test fun `v1 wire bytes remain compatible with independently encoded fixture`() {
        // Explicit big-endian framing; generated using Node Buffer, not this codec.
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val ciphertext = hex("216bae6fb097a736e22ffbf2918d1919e28738d5484c2dd8483ce0cef1de5f5e74")
        val fixture = hex("4f52463100000001000000010000000700000001000000074145535f47434d" +
            "0000000c000102030405060708090a0b00000021" +
            "216bae6fb097a736e22ffbf2918d1919e28738d5484c2dd8483ce0cef1de5f5e74")
        val envelope = FieldEnvelope(1, 7, "AES_GCM", 1, ByteArray(12) { it.toByte() }, ciphertext)
        assertArrayEquals(fixture, FieldEnvelopeCodec.encode(envelope))
        val decoded = (FieldEnvelopeCodec.decode(fixture) as FieldEnvelopeDecodingResult.Decoded).envelope
        assertArrayEquals(ciphertext, decoded.copyCiphertextAndTag())
        assertEquals(7, decoded.keyVersion)
    }

    @Test fun `binary envelope round trip retains authenticated content`() {
        val original = envelope()
        val bytes = FieldEnvelopeCodec.encode(original)
        val decoded = (FieldEnvelopeCodec.decode(bytes) as FieldEnvelopeDecodingResult.Decoded).envelope
        assertEquals(original.keyVersion, decoded.keyVersion)
        assertArrayEquals(original.copyNonce(), decoded.copyNonce())
        assertArrayEquals(original.copyCiphertextAndTag(), decoded.copyCiphertextAndTag())
        assertArrayEquals("private payload".toByteArray(),
            (engine.decrypt(decoded, context) as FieldDecryptionResult.Plaintext).copyBytes())
    }

    @Test fun `truncation at every byte boundary and trailing bytes are rejected`() {
        val bytes = FieldEnvelopeCodec.encode(envelope())
        for (size in bytes.indices) {
            assertTrue("truncated at $size", FieldEnvelopeCodec.decode(bytes.copyOf(size)) is FieldEnvelopeDecodingResult.Failure)
        }
        assertTrue(FieldEnvelopeCodec.decode(bytes + byteArrayOf(0)) is FieldEnvelopeDecodingResult.Failure)
    }

    @Test fun `oversized input corrupted headers and lengths fail boundedly`() {
        val bytes = FieldEnvelopeCodec.encode(envelope())
        assertTrue(FieldEnvelopeCodec.decode(ByteArray(1_048_705)) is FieldEnvelopeDecodingResult.Failure)
        for (offset in listOf(0, 4, 8, 12, 16, 20, 31, 47)) {
            val corrupted = bytes.copyOf()
            repeat(4) { corrupted[offset + it] = 0xff.toByte() }
            assertTrue("corrupt offset $offset", FieldEnvelopeCodec.decode(corrupted) is FieldEnvelopeDecodingResult.Failure)
        }
    }

    @Test fun `wire ciphertext tamper parses but authentication fails`() {
        val bytes = FieldEnvelopeCodec.encode(envelope())
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val decoded = (FieldEnvelopeCodec.decode(bytes) as FieldEnvelopeDecodingResult.Decoded).envelope
        assertEquals(FieldCryptoFailure.AUTHENTICATION_FAILED,
            (engine.decrypt(decoded, context) as FieldDecryptionResult.Failure).reason)
    }
}
