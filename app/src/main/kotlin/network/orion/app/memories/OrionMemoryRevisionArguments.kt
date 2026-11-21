package network.orion.app.memories

import java.util.UUID
import network.orion.core.commands.memories.*
import network.orion.core.model.OrionConversationTranscript
import org.json.JSONObject

/** Rebuild through the same preparer and compare the complete canonical value. */
internal fun decodeMemoryRevision(operation: MemoryStatusOperation, arguments: String, key: UUID,
    trace: UUID): ValidatedMemoryStatusChange {
    val json = JSONObject(arguments)
    val id = UUID.fromString(json.getString("memoryId"))
    val expected = json.getLong("expectedVersion")
    val command = when (operation) {
        MemoryStatusOperation.DELETE -> ForgetMemory(id, expected, key, trace)
        MemoryStatusOperation.FORGET_CONVERSATION -> ForgetConversation(id, expected, key, trace)
        MemoryStatusOperation.ARCHIVE_CONVERSATION -> ArchiveConversation(id, key, trace,
            OrionConversationTranscript.decode(json.getString("transcript")))
        MemoryStatusOperation.CORRECT -> json.getJSONObject("replacement").let { replacement ->
            CorrectMemory(id, expected, key, trace, UUID.fromString(replacement.getString("memoryId")), replacement.getString("content"),
                replacement.optBoolean("allowSearchIndex"))
        }
        MemoryStatusOperation.CONFIRM_CLAIM, MemoryStatusOperation.REJECT_CLAIM ->
            ReviewMemoryClaim(id, expected, key, trace, UUID.fromString(json.getString("claimId")), json.getLong("claimVersion"),
                MemoryClaimDecision.entries.single { it.operation == operation })
    }
    val ready = OrionMemoryStatusPreparer.prepare(command) as? MemoryStatusPreparation.Ready
        ?: throw IllegalArgumentException("Invalid memory revision.")
    require(ready.change.canonicalNormalizedArguments == arguments)
    return ready.change
}
