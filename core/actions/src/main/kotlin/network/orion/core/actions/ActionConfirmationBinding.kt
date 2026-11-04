package network.orion.core.actions

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import network.orion.core.model.AuthorizationLevel
import network.orion.core.model.IdempotencyMode
import network.orion.core.model.RecoveryStrategy
import network.orion.core.model.SideEffectClass
import network.orion.core.time.WallClock

/** Already normalized by trusted schema/policy code; construction grants no authority. */
data class NormalizedActionRequest(
    val actionId: UUID,
    val skill: String,
    val canonicalNormalizedArguments: String,
    val normalizedTarget: String,
    val authorizationLevel: AuthorizationLevel,
    val argumentSchemaVersion: Int,
    val sideEffectClass: SideEffectClass,
    val recoveryStrategy: RecoveryStrategy,
    val idempotencyMode: IdempotencyMode,
    val policyVersion: String,
) {
    init {
        require(skill.isNotBlank() && policyVersion.isNotBlank()) { "Action binding requires skill and policy version." }
        require(canonicalNormalizedArguments.isNotBlank()) { "Expected canonical normalized arguments." }
        require(argumentSchemaVersion > 0) { "Argument schema version must be positive." }
        listOf(skill, canonicalNormalizedArguments, normalizedTarget, policyVersion).forEach(::strictUtf8)
    }

    override fun toString(): String = "NormalizedActionRequest(<redacted>)"
}

data class ActionConfirmationFingerprint(
    val normalizedPayloadHash: String,
    val confirmationFingerprint: String,
) {
    override fun toString(): String = "ActionConfirmationFingerprint(<redacted>)"
}

/** Persist alongside the exact request. Not a signed credential or proof of a user gesture. */
data class ActionConfirmationSnapshot(
    val actionId: UUID,
    val normalizedPayloadHash: String,
    val confirmedPayloadHash: String,
    val confirmationFingerprint: String,
    val policyVersionAtConfirmation: String,
    val confirmedAt: Instant,
    val confirmationExpiresAt: Instant?,
    val payloadLockedAt: Instant,
) {
    override fun toString(): String = "ActionConfirmationSnapshot(<redacted>)"
}

enum class ConfirmationBindingResult {
    VALID_BINDING,
    CONFIRMATION_STALE,
    CONFIRMATION_MISMATCH,
    CONFIRMATION_EXPIRED,
    POLICY_CHANGED_RECONFIRM_REQUIRED,
}

/** Pure binding checks; policy, capability, permission and execution ownership are separate gates. */
class ActionConfirmationBinding(private val clock: WallClock) {
    fun prepare(request: NormalizedActionRequest): ActionConfirmationFingerprint {
        val payloadHash = digest("orion_action_payload_v1", request.skill,
            request.canonicalNormalizedArguments, request.normalizedTarget,
            request.authorizationLevel.name, request.argumentSchemaVersion.toString(),
            request.sideEffectClass.name, request.recoveryStrategy.name, request.idempotencyMode.name)
        return ActionConfirmationFingerprint(payloadHash,
            digest("orion_action_confirmation_v1", payloadHash, request.actionId.toString(), request.policyVersion))
    }

    /** Caller must establish the user confirmation/policy decision for this exact immutable request. */
    fun recordConfirmation(request: NormalizedActionRequest, expiresAt: Instant?): ActionConfirmationSnapshot {
        val now = clock.wallNow()
        require(expiresAt == null || expiresAt.isAfter(now)) { "Confirmation must expire after confirmation time." }
        val binding = prepare(request)
        return ActionConfirmationSnapshot(request.actionId, binding.normalizedPayloadHash,
            binding.normalizedPayloadHash, binding.confirmationFingerprint, request.policyVersion,
            now, expiresAt, now)
    }

    fun validate(request: NormalizedActionRequest, snapshot: ActionConfirmationSnapshot): ConfirmationBindingResult {
        if (request.actionId != snapshot.actionId) return ConfirmationBindingResult.CONFIRMATION_MISMATCH
        if (request.policyVersion != snapshot.policyVersionAtConfirmation) {
            return ConfirmationBindingResult.POLICY_CHANGED_RECONFIRM_REQUIRED
        }
        val expected = prepare(request)
        if (expected.normalizedPayloadHash != snapshot.normalizedPayloadHash ||
            expected.normalizedPayloadHash != snapshot.confirmedPayloadHash ||
            expected.confirmationFingerprint != snapshot.confirmationFingerprint) {
            return ConfirmationBindingResult.CONFIRMATION_MISMATCH
        }
        val now = clock.wallNow()
        if (snapshot.confirmedAt.isAfter(now) || snapshot.payloadLockedAt != snapshot.confirmedAt) {
            return ConfirmationBindingResult.CONFIRMATION_STALE
        }
        if (snapshot.confirmationExpiresAt?.let { !now.isBefore(it) } == true) {
            return ConfirmationBindingResult.CONFIRMATION_EXPIRED
        }
        return ConfirmationBindingResult.VALID_BINDING
    }
}

private fun digest(domain: String, vararg fields: String): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        for (field in listOf(domain) + fields) {
            val utf8 = strictUtf8(field)
            output.writeInt(utf8.size)
            output.write(utf8)
        }
    }
    val hex = "0123456789abcdef"
    return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { byte ->
        val value = byte.toInt() and 255
        "${hex[value ushr 4]}${hex[value and 15]}"
    }
}

private fun strictUtf8(value: String): ByteArray = try {
    val buffer = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(value))
    ByteArray(buffer.remaining()).also { buffer.get(it) }
} catch (_: CharacterCodingException) {
    throw IllegalArgumentException("Action binding requires well-formed Unicode.")
}
