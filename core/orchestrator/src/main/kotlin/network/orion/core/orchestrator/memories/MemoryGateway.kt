package network.orion.core.orchestrator.memories

import java.util.UUID
import network.orion.core.commands.memories.*
import network.orion.core.model.OrionConversationTranscript
import network.orion.core.queries.memories.*

enum class MemoryOperation { CREATE, DELETE, CORRECT, CONFIRM_CLAIM, REJECT_CLAIM, ARCHIVE_CONVERSATION, FORGET_CONVERSATION;

    fun isConversationOperation() = this == ARCHIVE_CONVERSATION || this == FORGET_CONVERSATION
}
enum class MemoryOperationOutcome { APPLIED, QUEUED, REJECTED, UNAVAILABLE, FORGOTTEN }

/** Selected immutable display data. IDs/version are references, never confirmation authority. */
class MemoryConfirmation(val actionId: UUID, val version: Long, val operation: MemoryOperation, val content: String?) {
    override fun toString() = "MemoryConfirmation(<redacted>)"
}

/** Activity-scoped in-app port. Mutation submissions originate only in explicit UI handlers. */
interface MemoryGateway {
    suspend fun create(command: CreateMemory): CreateMemoryResult
    suspend fun change(command: MemoryStatusCommand): MemoryStatusResult
    suspend fun list(request: GetMemories): MemoryPage?
    suspend fun search(request: SearchMemories): MemorySearchPage?
    suspend fun claims(request: GetMemoryClaims): List<MemoryClaimSummary>?
    suspend fun archives(): List<ConversationArchive>?
    suspend fun archived(request: GetArchivedConversation): OrionConversationTranscript?
    suspend fun pending(): List<MemoryConfirmation>
    suspend fun confirm(actionId: UUID, displayedVersion: Long): MemoryOperationOutcome
    suspend fun dismiss(actionId: UUID, displayedVersion: Long): MemoryOperationOutcome
    /** Separate explicit lifecycle/gesture operation; reads never hide a queue drain. */
    suspend fun processPending(): Int
}
