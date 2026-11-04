package network.orion.core.security

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Immutable AES-GCM envelope. The final 16 bytes of ciphertextAndTag are the tag. */
class FieldEnvelope(
    val ciphertextVersion: Int,
    val keyVersion: Int,
    val algorithm: String,
    val aadSchemaVersion: Int,
    nonce: ByteArray,
    ciphertextAndTag: ByteArray,
) {
    private val nonceBytes: ByteArray
    private val encryptedBytes: ByteArray

    init {
        require(ciphertextVersion > 0 && keyVersion > 0 && aadSchemaVersion > 0) { "Invalid envelope version." }
        require(algorithm.isNotBlank() && algorithm.length <= 32) { "Invalid envelope algorithm." }
        require(nonce.size == 12) { "Invalid envelope nonce size." }
        require(ciphertextAndTag.size in 16..(MAX_FIELD_BYTES + 16)) { "Invalid encrypted field size." }
        nonceBytes = nonce.copyOf()
        encryptedBytes = ciphertextAndTag.copyOf()
    }

    fun copyNonce(): ByteArray = nonceBytes.copyOf()
    fun copyCiphertextAndTag(): ByteArray = encryptedBytes.copyOf()
    override fun toString(): String = "FieldEnvelope(<redacted>)"

    companion object {
        const val MAX_FIELD_BYTES: Int = 1_048_576
    }
}

/** Scope entityId with the entity type/domain as well as its stable identifier. */
class FieldEncryptionContext(val entityId: String, val fieldName: String, val schemaVersion: Int) {
    private val entityBytes: ByteArray
    private val fieldBytes: ByteArray

    init {
        require(entityId.isNotBlank() && fieldName.isNotBlank() && schemaVersion > 0) { "Invalid field context." }
        require(entityId.length <= 4096 && fieldName.length <= 4096) { "Field context exceeds size limit." }
        entityBytes = strictUtf8(entityId)
        fieldBytes = strictUtf8(fieldName)
        require(entityBytes.size <= 4096 && fieldBytes.size <= 4096) { "Field context exceeds size limit." }
    }

    internal fun copyEntityBytes(): ByteArray = entityBytes.copyOf()
    internal fun copyFieldBytes(): ByteArray = fieldBytes.copyOf()
    override fun toString(): String = "FieldEncryptionContext(<redacted>)"
}

internal fun strictUtf8(value: String): ByteArray = try {
    val buffer = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(value))
    ByteArray(buffer.remaining()).also { buffer.get(it) }
} catch (_: CharacterCodingException) {
    throw IllegalArgumentException("Invalid UTF-8 field context.")
}
