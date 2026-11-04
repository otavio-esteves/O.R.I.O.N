package network.orion.core.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

enum class FieldEnvelopeDecodeFailure { MALFORMED, UNSUPPORTED_VERSION }

sealed interface FieldEnvelopeDecodingResult {
    class Decoded(val envelope: FieldEnvelope) : FieldEnvelopeDecodingResult {
        override fun toString(): String = "FieldEnvelopeDecoded(<redacted>)"
    }
    data class Failure(val reason: FieldEnvelopeDecodeFailure) : FieldEnvelopeDecodingResult
}

/** Strict bounded binary storage format; successful parsing does not prove authenticity. */
object FieldEnvelopeCodec {
    private const val MAGIC = 0x4f524631 // ORF1
    private const val CODEC_VERSION = 1
    private const val MAX_ENCODED_BYTES = FieldEnvelope.MAX_FIELD_BYTES + 128

    fun encode(envelope: FieldEnvelope): ByteArray {
        require(OrionFieldEncryption.isSupported(envelope)) { "Unsupported field envelope." }
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                fun field(value: ByteArray) { output.writeInt(value.size); output.write(value) }
                output.writeInt(MAGIC)
                output.writeInt(CODEC_VERSION)
                output.writeInt(envelope.ciphertextVersion)
                output.writeInt(envelope.keyVersion)
                output.writeInt(envelope.aadSchemaVersion)
                field(envelope.algorithm.toByteArray(Charsets.UTF_8))
                field(envelope.copyNonce())
                field(envelope.copyCiphertextAndTag())
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): FieldEnvelopeDecodingResult {
        if (bytes.size > MAX_ENCODED_BYTES) return malformed()
        return try {
            DataInputStream(ByteArrayInputStream(bytes.copyOf())).use { input ->
                if (input.readInt() != MAGIC) return malformed()
                if (input.readInt() != CODEC_VERSION) return unsupported()
                val ciphertextVersion = input.readInt()
                val keyVersion = input.readInt()
                val aadVersion = input.readInt()
                if (ciphertextVersion <= 0 || keyVersion <= 0 || aadVersion <= 0) return malformed()
                if (ciphertextVersion != 1 || aadVersion != 1) return unsupported()
                val algorithmSize = input.readInt()
                val algorithm = OrionFieldEncryption.ALGORITHM.toByteArray(Charsets.UTF_8)
                if (algorithmSize != algorithm.size || input.available() < algorithmSize) return malformed()
                val storedAlgorithm = ByteArray(algorithmSize).also(input::readFully)
                if (!storedAlgorithm.contentEquals(algorithm)) return unsupported()
                if (input.readInt() != 12) return malformed()
                val nonce = ByteArray(12).also(input::readFully)
                val encryptedSize = input.readInt()
                if (encryptedSize !in 16..(FieldEnvelope.MAX_FIELD_BYTES + 16) || input.available() != encryptedSize) return malformed()
                val encrypted = ByteArray(encryptedSize).also(input::readFully)
                FieldEnvelopeDecodingResult.Decoded(FieldEnvelope(ciphertextVersion, keyVersion,
                    OrionFieldEncryption.ALGORITHM, aadVersion, nonce, encrypted))
            }
        } catch (_: IOException) {
            malformed()
        }
    }

    private fun malformed() = FieldEnvelopeDecodingResult.Failure(FieldEnvelopeDecodeFailure.MALFORMED)
    private fun unsupported() = FieldEnvelopeDecodingResult.Failure(FieldEnvelopeDecodeFailure.UNSUPPORTED_VERSION)
}
