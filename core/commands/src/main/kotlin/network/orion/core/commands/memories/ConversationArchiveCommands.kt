package network.orion.core.commands.memories

import java.util.UUID
import network.orion.core.model.OrionConversationTranscript

data class ArchiveConversation(override val memoryId: UUID,
    override val idempotencyKey: UUID, override val correlationId: UUID,
    val transcript: OrionConversationTranscript) : MemoryStatusCommand {
    override val expectedVersion get() = 0L
    override val operation get() = MemoryStatusOperation.ARCHIVE_CONVERSATION
    override fun toString() = "ArchiveConversation(<redacted>)"
}

data class ForgetConversation(override val memoryId: UUID, override val expectedVersion: Long,
    override val idempotencyKey: UUID, override val correlationId: UUID) : MemoryStatusCommand {
    override val operation get() = MemoryStatusOperation.FORGET_CONVERSATION
    override fun toString() = "ForgetConversation(<redacted>)"
}
