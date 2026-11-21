package network.orion.core.orchestrator.chat

import java.util.Collections
import network.orion.core.model.OrionConversationEntry
import network.orion.core.model.OrionMemoryOrigin

/** An extract computed from the current earlier turns. Each excerpt has its original source ID. */
internal class OrionSessionSummary private constructor(entries: List<OrionConversationEntry>) {
    val entries: List<OrionConversationEntry> = Collections.unmodifiableList(ArrayList(entries))
    val origin get() = OrionMemoryOrigin.SYSTEM_DERIVED

    override fun toString() = "OrionSessionSummary(<redacted>)"

    companion object {
        fun from(history: List<OrionChatTurn>, recentTurns: Int): OrionSessionSummary? {
            val sources = history.dropLast(recentTurns).filter {
                it.user && it.origin == OrionMemoryOrigin.USER_DECLARED && it.archiveId == null &&
                    it.proposal == null && it.text.isNotBlank() && it.text.toByteArray(Charsets.UTF_8).size <= 256
            }.takeLast(3)
            if (sources.isEmpty()) return null
            return OrionSessionSummary(sources.map { OrionConversationEntry(it.id, it.origin, it.text) })
        }
    }
}
