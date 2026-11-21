package network.orion.core.queries.memories

import java.util.Collections
import java.util.UUID
import network.orion.core.model.OrionMemoryDetails
import network.orion.core.model.OrionMemoryLifecycle
import network.orion.core.model.OrionMemoryProvenance
import network.orion.core.model.OrionMemoryStatus
import network.orion.core.queries.Query

/** Stable UUID cursor. A query never drains ingress or changes memory state. */
data class GetMemories(val after: UUID? = null, val limit: Int = 50) : Query<MemoryPage> {
    init { require(limit in 1..100) }
}

typealias MemoryState = OrionMemoryStatus
enum class MemoryContentState { READABLE, UNREADABLE, KEY_UNAVAILABLE }

class MemorySummary(val memoryId: UUID, val content: String?, val state: MemoryState, val version: Long,
    val contentState: MemoryContentState, val provenance: OrionMemoryProvenance,
    val lifecycle: OrionMemoryLifecycle = OrionMemoryLifecycle(),
    val details: OrionMemoryDetails? = null,
    val detailsState: MemoryContentState? = null) {
    init {
        require(version >= 0)
        require((content != null) == (contentState == MemoryContentState.READABLE))
        require((details != null) == (detailsState == MemoryContentState.READABLE))
    }
    override fun toString() = "MemorySummary(<redacted>)"
}

class MemoryPage(items: List<MemorySummary>, val next: UUID?) {
    val items: List<MemorySummary> = Collections.unmodifiableList(ArrayList(items))
    init { require(items.size <= 100); require(next == null || items.lastOrNull()?.memoryId == next) }
    override fun toString() = "MemoryPage(<redacted>)"
}

fun interface MemoryQuery { fun get(request: GetMemories): MemoryPage }
