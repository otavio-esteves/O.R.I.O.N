package network.orion.core.actions

import java.time.Instant
import java.util.UUID
import network.orion.core.model.AuthorizationLevel
import network.orion.core.model.IdempotencyMode
import network.orion.core.model.RecoveryStrategy
import network.orion.core.model.SideEffectClass
import network.orion.core.time.WallClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ActionConfirmationBindingTest {
    private val confirmedAt = Instant.parse("2026-11-03T12:00:00Z")
    private var now = confirmedAt
    private val binding = ActionConfirmationBinding(WallClock { now })
    private val request = NormalizedActionRequest(UUID(0, 1), "task.create", "{\"title\":\"private title\"}",
        "private target", AuthorizationLevel.SAFE_WRITE, 1, SideEffectClass.LOCAL_MUTATION,
        RecoveryStrategy.RETRY_SAFE, IdempotencyMode.KEYED, "policy-1")

    @Test fun `version one fingerprints retain their independently generated encoding vectors`() {
        // Reference vectors generated with Python hashlib + struct.pack('>i', UTF-8 length).
        val prepared = binding.prepare(request)
        assertEquals("5783af696fcaa21022b3b182844ce6b9542215e621137f41ea0449250e0f90e0",
            prepared.normalizedPayloadHash)
        assertEquals("6f80774ee77334089002f64bf16b0e1d8b6009fc03049035db9710212d561738",
            prepared.confirmationFingerprint)
    }

    @Test fun `confirmation records exact binding and the same lock instant`() {
        val prepared = binding.prepare(request)
        val confirmation = binding.recordConfirmation(request, confirmedAt.plusSeconds(60))
        assertEquals(prepared.normalizedPayloadHash, confirmation.normalizedPayloadHash)
        assertEquals(prepared.normalizedPayloadHash, confirmation.confirmedPayloadHash)
        assertEquals(prepared.confirmationFingerprint, confirmation.confirmationFingerprint)
        assertEquals(confirmedAt, confirmation.confirmedAt)
        assertEquals(confirmedAt, confirmation.payloadLockedAt)
        assertEquals(ConfirmationBindingResult.VALID_BINDING, binding.validate(request, confirmation))
    }

    @Test fun `changing any material field invalidates confirmation without changing the original`() {
        val confirmation = binding.recordConfirmation(request, null)
        val changed = listOf(
            request.copy(skill = "task.delete"),
            request.copy(canonicalNormalizedArguments = "{\"title\":\"different\"}"),
            request.copy(normalizedTarget = "other target"),
            request.copy(authorizationLevel = AuthorizationLevel.DESTRUCTIVE),
            request.copy(argumentSchemaVersion = 2),
            request.copy(sideEffectClass = SideEffectClass.EXTERNAL_IRREVERSIBLE),
            request.copy(recoveryStrategy = RecoveryStrategy.NO_RETRY),
            request.copy(idempotencyMode = IdempotencyMode.NONE),
        )
        changed.forEach { altered ->
            assertEquals(ConfirmationBindingResult.CONFIRMATION_MISMATCH, binding.validate(altered, confirmation))
        }
        assertEquals(ConfirmationBindingResult.VALID_BINDING, binding.validate(request, confirmation))
    }

    @Test fun `action identity binds fingerprint independently of payload hash`() {
        val other = request.copy(actionId = UUID(0, 2))
        assertEquals(binding.prepare(request).normalizedPayloadHash, binding.prepare(other).normalizedPayloadHash)
        assertNotEquals(binding.prepare(request).confirmationFingerprint, binding.prepare(other).confirmationFingerprint)
        assertEquals(ConfirmationBindingResult.CONFIRMATION_MISMATCH,
            binding.validate(other, binding.recordConfirmation(request, null)))
    }

    @Test fun `changed policy requires reconfirmation even when payload is identical`() {
        val newer = request.copy(policyVersion = "policy-2")
        assertEquals(binding.prepare(request).normalizedPayloadHash, binding.prepare(newer).normalizedPayloadHash)
        assertNotEquals(binding.prepare(request).confirmationFingerprint, binding.prepare(newer).confirmationFingerprint)
        assertEquals(ConfirmationBindingResult.POLICY_CHANGED_RECONFIRM_REQUIRED,
            binding.validate(newer, binding.recordConfirmation(request, null)))
    }

    @Test fun `each persisted hash must match the recomputed binding`() {
        val confirmation = binding.recordConfirmation(request, null)
        val bad = "0".repeat(64)
        listOf(confirmation.copy(normalizedPayloadHash = bad), confirmation.copy(confirmedPayloadHash = bad),
            confirmation.copy(confirmationFingerprint = bad)).forEach { altered ->
            assertEquals(ConfirmationBindingResult.CONFIRMATION_MISMATCH, binding.validate(request, altered))
        }
    }

    @Test fun `expiry boundary is inclusive and forward jumps expire confirmation`() {
        val expiresAt = now.plusSeconds(60)
        val confirmation = binding.recordConfirmation(request, expiresAt)
        now = expiresAt.minusNanos(1)
        assertEquals(ConfirmationBindingResult.VALID_BINDING, binding.validate(request, confirmation))
        now = expiresAt
        assertEquals(ConfirmationBindingResult.CONFIRMATION_EXPIRED, binding.validate(request, confirmation))
        now = expiresAt.plusSeconds(3600)
        assertEquals(ConfirmationBindingResult.CONFIRMATION_EXPIRED, binding.validate(request, confirmation))
    }

    @Test fun `rollback before confirmation blocks even without an expiry`() {
        val confirmation = binding.recordConfirmation(request, null)
        now = confirmedAt.minusNanos(1)
        assertEquals(ConfirmationBindingResult.CONFIRMATION_STALE, binding.validate(request, confirmation))
    }

    @Test fun `unlocked or inconsistent persisted metadata is stale`() {
        val confirmation = binding.recordConfirmation(request, null)
        assertEquals(ConfirmationBindingResult.CONFIRMATION_STALE,
            binding.validate(request, confirmation.copy(payloadLockedAt = confirmedAt.minusNanos(1))))
    }

    @Test fun `a confirmation cannot be recorded already expired`() {
        assertThrows(IllegalArgumentException::class.java) { binding.recordConfirmation(request, now) }
        assertThrows(IllegalArgumentException::class.java) { binding.recordConfirmation(request, now.minusNanos(1)) }
    }

    @Test fun `length framing prevents field concatenation and separator ambiguity`() {
        val first = request.copy(skill = "ab", canonicalNormalizedArguments = "c")
        val second = request.copy(skill = "a", canonicalNormalizedArguments = "bc")
        assertNotEquals(binding.prepare(first).normalizedPayloadHash, binding.prepare(second).normalizedPayloadHash)
        val third = request.copy(skill = "a\u0000b", canonicalNormalizedArguments = "c")
        val fourth = request.copy(skill = "a", canonicalNormalizedArguments = "b\u0000c")
        assertNotEquals(binding.prepare(third).normalizedPayloadHash, binding.prepare(fourth).normalizedPayloadHash)
    }

    @Test fun `Unicode input is deterministic and is never normalized silently`() {
        val unicode = request.copy(canonicalNormalizedArguments = "{\"title\":\"ação 🌙\"}")
        val independent = unicode.copy()
        assertEquals(binding.prepare(unicode), binding.prepare(independent))
        assertNotEquals(binding.prepare(request.copy(canonicalNormalizedArguments = "é")),
            binding.prepare(request.copy(canonicalNormalizedArguments = "e\u0301")))
        assertNotEquals(binding.prepare(request.copy(canonicalNormalizedArguments = "{}")),
            binding.prepare(request.copy(canonicalNormalizedArguments = "{ }")))
    }

    @Test fun `malformed Unicode cannot collapse into replacement characters`() {
        for (bad in listOf("\uD800", "\uDC00")) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                request.copy(canonicalNormalizedArguments = bad)
            }
            assertEquals("Action binding requires well-formed Unicode.", failure.message)
        }
    }

    @Test fun `required fields cannot be blank or use invalid schema versions`() {
        assertThrows(IllegalArgumentException::class.java) { request.copy(skill = " ") }
        assertThrows(IllegalArgumentException::class.java) { request.copy(canonicalNormalizedArguments = "") }
        assertThrows(IllegalArgumentException::class.java) { request.copy(policyVersion = "") }
        assertThrows(IllegalArgumentException::class.java) { request.copy(argumentSchemaVersion = 0) }
        binding.prepare(request.copy(canonicalNormalizedArguments = "{}", normalizedTarget = ""))
    }

    @Test fun `default representations disclose no request values or hashes`() {
        val prepared = binding.prepare(request)
        val confirmation = binding.recordConfirmation(request, null)
        val rendered = listOf(request, prepared, confirmation).joinToString()
        for (secret in listOf(request.skill, request.canonicalNormalizedArguments, request.normalizedTarget,
            request.policyVersion, request.actionId.toString(), prepared.normalizedPayloadHash,
            prepared.confirmationFingerprint)) {
            assertFalse(rendered.contains(secret))
        }
    }
}
