package network.orion.core.queries.memories

import java.time.Instant
import java.util.UUID
import network.orion.core.model.OrionMemoryProvenance
import network.orion.core.queries.Query

data class GetMemoryClaims(val memoryId: UUID, val expectedVersion: Long) : Query<List<MemoryClaimSummary>> {
    init { require(expectedVersion >= 0) }
}

class MemoryClaimSummary(val claimId: UUID, val version: Long, val statement: String?, val state: MemoryState,
    val contentState: MemoryContentState, val provenance: OrionMemoryProvenance, val validFrom: Instant,
    val validUntil: Instant?, val canReview: Boolean) {
    init {
        require(version >= 0)
        require((statement != null) == (contentState == MemoryContentState.READABLE))
    }

    override fun toString() = "MemoryClaimSummary(<redacted>)"
}
