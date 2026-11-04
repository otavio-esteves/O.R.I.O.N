package network.orion.core.security

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

enum class FieldCryptoFailure {
    KEY_UNAVAILABLE, KEY_INVALIDATED, INVALID_KEY, UNSUPPORTED_ENVELOPE,
    AUTHENTICATION_FAILED, FIELD_TOO_LARGE, CRYPTO_UNAVAILABLE,
}

sealed interface FieldEncryptionResult {
    class Encrypted(val envelope: FieldEnvelope) : FieldEncryptionResult {
        override fun toString(): String = "FieldEncrypted(<redacted>)"
    }
    data class Failure(val reason: FieldCryptoFailure) : FieldEncryptionResult
}

sealed interface FieldDecryptionResult {
    class Plaintext internal constructor(bytes: ByteArray) : FieldDecryptionResult {
        private val value = bytes.copyOf()
        fun copyBytes(): ByteArray = value.copyOf()
        override fun toString(): String = "FieldPlaintext(<redacted>)"
    }
    data class Failure(val reason: FieldCryptoFailure) : FieldDecryptionResult
}

/**
 * Pure field mechanism: no persistence, key generation, policy decisions or logs.
 * Each encryption uses a fresh SecureRandom 96-bit nonce and a 128-bit tag.
 * Rotation/volume policy belongs to the versioned production key provider.
 */
class OrionFieldEncryption(private val keys: FieldKeyProvider) {
    private val random = SecureRandom()

    fun encrypt(plaintext: ByteArray, context: FieldEncryptionContext): FieldEncryptionResult {
        if (plaintext.size > FieldEnvelope.MAX_FIELD_BYTES) return FieldEncryptionResult.Failure(FieldCryptoFailure.FIELD_TOO_LARGE)
        val key = when (val result = keys.currentKey()) {
            is FieldKeyResult.Available -> result
            FieldKeyResult.Unavailable -> return FieldEncryptionResult.Failure(FieldCryptoFailure.KEY_UNAVAILABLE)
            FieldKeyResult.Invalidated -> return FieldEncryptionResult.Failure(FieldCryptoFailure.KEY_INVALIDATED)
        }
        val nonce = ByteArray(12).also(random::nextBytes)
        val input = plaintext.copyOf()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key.key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(key.version, context))
            FieldEncryptionResult.Encrypted(FieldEnvelope(1, key.version, ALGORITHM, 1, nonce, cipher.doFinal(input)))
        } catch (_: InvalidKeyException) {
            FieldEncryptionResult.Failure(FieldCryptoFailure.INVALID_KEY)
        } catch (_: GeneralSecurityException) {
            FieldEncryptionResult.Failure(FieldCryptoFailure.CRYPTO_UNAVAILABLE)
        } finally {
            input.fill(0)
        }
    }

    fun decrypt(envelope: FieldEnvelope, context: FieldEncryptionContext): FieldDecryptionResult {
        if (!isSupported(envelope)) return FieldDecryptionResult.Failure(FieldCryptoFailure.UNSUPPORTED_ENVELOPE)
        val key = when (val result = keys.keyForVersion(envelope.keyVersion)) {
            is FieldKeyResult.Available -> if (result.version == envelope.keyVersion) result else
                return FieldDecryptionResult.Failure(FieldCryptoFailure.KEY_UNAVAILABLE)
            FieldKeyResult.Unavailable -> return FieldDecryptionResult.Failure(FieldCryptoFailure.KEY_UNAVAILABLE)
            FieldKeyResult.Invalidated -> return FieldDecryptionResult.Failure(FieldCryptoFailure.KEY_INVALIDATED)
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key.key, GCMParameterSpec(128, envelope.copyNonce()))
            cipher.updateAAD(aad(envelope.keyVersion, context))
            val plaintext = cipher.doFinal(envelope.copyCiphertextAndTag())
            try { FieldDecryptionResult.Plaintext(plaintext) } finally { plaintext.fill(0) }
        } catch (_: AEADBadTagException) {
            FieldDecryptionResult.Failure(FieldCryptoFailure.AUTHENTICATION_FAILED)
        } catch (_: BadPaddingException) {
            // Providers may report a GCM authentication failure using the superclass.
            FieldDecryptionResult.Failure(FieldCryptoFailure.AUTHENTICATION_FAILED)
        } catch (_: InvalidKeyException) {
            FieldDecryptionResult.Failure(FieldCryptoFailure.INVALID_KEY)
        } catch (_: GeneralSecurityException) {
            FieldDecryptionResult.Failure(FieldCryptoFailure.CRYPTO_UNAVAILABLE)
        }
    }

    private fun aad(keyVersion: Int, context: FieldEncryptionContext): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                fun field(value: ByteArray) { output.writeInt(value.size); output.write(value) }
                field("orion_field_encryption".toByteArray(Charsets.UTF_8))
                output.writeInt(1) // ciphertextVersion
                output.writeInt(keyVersion)
                field(ALGORITHM.toByteArray(Charsets.UTF_8))
                output.writeInt(1) // aadSchemaVersion
                field(context.copyEntityBytes())
                field(context.copyFieldBytes())
                output.writeInt(context.schemaVersion)
            }
        }.toByteArray()

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        internal const val ALGORITHM = "AES_GCM"
        internal fun isSupported(envelope: FieldEnvelope): Boolean =
            envelope.ciphertextVersion == 1 && envelope.aadSchemaVersion == 1 && envelope.algorithm == ALGORITHM
    }
}
