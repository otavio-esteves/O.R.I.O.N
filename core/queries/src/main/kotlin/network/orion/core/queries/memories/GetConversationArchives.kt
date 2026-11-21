package network.orion.core.queries.memories

import java.time.Instant
import java.util.UUID
import network.orion.core.model.OrionConversationTranscript
import network.orion.core.queries.Query

data object GetConversationArchives : Query<List<ConversationArchive>>

data class GetArchivedConversation(val id: UUID, val expectedVersion: Long) : Query<OrionConversationTranscript?> {
    init { require(expectedVersion >= 0) }
}

class ConversationArchive(val id: UUID, val version: Long, val createdAt: Instant) {
    override fun toString() = "ConversationArchive(<redacted>)"
}
