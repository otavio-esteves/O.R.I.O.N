package network.orion.core.model

import java.time.Instant
import java.util.Collections

enum class OrionMemoryType { SEMANTIC, EPISODIC, OPERATIONAL, CONVERSATIONAL, PREFERENCE, STATE }

enum class OrionMemoryStatus { ACTIVE, CANDIDATE, SUPERSEDED, EXPIRED, REJECTED, ARCHIVED, DELETED }

/** Nullable metadata preserves the meaning of records written before the lifecycle contract. */
data class OrionMemoryLifecycle(
    val type: OrionMemoryType? = null,
    val importance: Double? = null,
    val validFrom: Instant? = null,
    val validUntil: Instant? = null,
    val expiresAt: Instant? = null,
) {
    init {
        require(importance == null || importance.isFinite() && importance in 0.0..1.0)
        require(validFrom == null || validUntil == null || validFrom.toEpochMilli() < validUntil.toEpochMilli())
        require(validFrom == null || expiresAt == null || validFrom.toEpochMilli() < expiresAt.toEpochMilli())
    }

    fun isExpiredAt(at: Instant): Boolean =
        validUntil?.let { at.toEpochMilli() >= it.toEpochMilli() } == true ||
            expiresAt?.let { at.toEpochMilli() >= it.toEpochMilli() } == true

    fun isValidAt(at: Instant): Boolean =
        (validFrom == null || at.toEpochMilli() >= validFrom.toEpochMilli()) && !isExpiredAt(at)

    fun stateAt(status: OrionMemoryStatus, at: Instant): OrionMemoryStatus =
        if (status in expirableStates && isExpiredAt(at)) OrionMemoryStatus.EXPIRED else status

    private companion object {
        val expirableStates = setOf(OrionMemoryStatus.ACTIVE, OrionMemoryStatus.CANDIDATE)
    }
}

/** User content kept in a separate, authenticated field envelope. */
class OrionMemoryDetails(val source: String? = null, val summary: String? = null, tags: List<String> = emptyList()) {
    val tags: List<String> = Collections.unmodifiableList(ArrayList(tags.also { require(it.size <= 16) }))

    init {
        require(source != null || summary != null || this.tags.isNotEmpty())
        require(source == null || source.validText(256))
        require(summary == null || summary.validText(2048))
        require(this.tags.all { it.validText(64) })
        require(this.tags.distinct().size == this.tags.size)
    }

    private fun String.validText(maxBytes: Int): Boolean = length <= maxBytes && isNotBlank() && this == trim() &&
        '\u0000' !in this && Charsets.UTF_8.newEncoder().canEncode(this) && toByteArray(Charsets.UTF_8).size <= maxBytes

    override fun toString() = "OrionMemoryDetails(<redacted>)"
}
