package network.orion.core.actions

import java.time.Duration

/** Declaration only; the future transactional store enforces retention and uniqueness. */
data class IdempotencyKeyPolicy(
    val issuer: String,
    val scope: String,
    val retention: Duration,
    val canonicalPayloadSchemaVersion: Int,
    val uniqueConstraint: String,
) {
    init {
        require(issuer.isNotBlank() && scope.isNotBlank() && uniqueConstraint.isNotBlank()) {
            "Idempotency policy requires issuer, scope and uniqueness constraint."
        }
        require(!retention.isNegative && !retention.isZero) { "Retention must be positive." }
        require(canonicalPayloadSchemaVersion > 0) { "Canonical payload schema version must be positive." }
    }

    /** Compare already-normalized, same-version bindings; never replace an existing operation. */
    fun compare(original: IdempotencyBinding, incoming: IdempotencyBinding): IdempotencyReplay {
        require(original.scope == scope && incoming.scope == scope) { "Idempotency scope mismatch." }
        require(original.canonicalPayloadSchemaVersion == canonicalPayloadSchemaVersion &&
            incoming.canonicalPayloadSchemaVersion == canonicalPayloadSchemaVersion) {
            "Canonical payload schema mismatch."
        }
        return when {
            original.key != incoming.key -> IdempotencyReplay.NEW_KEY
            original.canonicalPayloadHash == incoming.canonicalPayloadHash -> IdempotencyReplay.DEDUP
            else -> IdempotencyReplay.IDEMPOTENCY_CONFLICT
        }
    }
}

class IdempotencyBinding(
    val scope: String,
    val key: String,
    val canonicalPayloadHash: String,
    val canonicalPayloadSchemaVersion: Int,
) {
    init {
        require(canonicalPayloadSchemaVersion > 0) { "Canonical payload schema version must be positive." }
        require(scope.isNotBlank() && key.isNotBlank()) { "Idempotency binding requires scope and key." }
        require(canonicalPayloadHash.matches(Regex("[0-9a-f]{64}"))) { "Expected canonical SHA-256 fingerprint." }
    }
    override fun toString(): String = "IdempotencyBinding(<redacted>)"
}

enum class IdempotencyReplay { NEW_KEY, DEDUP, IDEMPOTENCY_CONFLICT }
