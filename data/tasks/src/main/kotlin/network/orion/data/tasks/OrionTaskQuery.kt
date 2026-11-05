package network.orion.data.tasks

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import network.orion.core.commands.tasks.*
import network.orion.core.queries.tasks.*
import network.orion.core.security.*
import network.orion.data.database.OrionDatabase
import org.json.JSONObject

/** MAIN-only read adapter. Legacy opaque/corrupt content stays explicitly unreadable. */
class OrionTaskQuery(private val database: OrionDatabase, private val encryption: OrionFieldEncryption) : TaskQuery {
    override fun get(request: GetTasks): TaskPage {
        val rows = database.tasks().page(request.after?.toString(), request.limit + 1)
        val selected = rows.take(request.limit).map { row ->
            val id = UUID.fromString(row.taskId)
            var state = TaskContentState.UNREADABLE
            var title: String? = null
            val parsed = FieldEnvelopeCodec.decode(row.contentEnvelope)
            if (parsed is FieldEnvelopeDecodingResult.Decoded) {
                when (val result = encryption.decrypt(parsed.envelope, FieldEncryptionContext("task/$id", "content", 1))) {
                    is FieldDecryptionResult.Plaintext -> {
                        val clear = result.copyBytes()
                        try { title = decodeContent(id, clear); if (title != null) state = TaskContentState.READABLE }
                        finally { clear.fill(0) }
                    }
                    is FieldDecryptionResult.Failure -> if (result.reason in setOf(FieldCryptoFailure.KEY_UNAVAILABLE,
                        FieldCryptoFailure.KEY_INVALIDATED, FieldCryptoFailure.CRYPTO_UNAVAILABLE)) state = TaskContentState.KEY_UNAVAILABLE
                }
            }
            TaskSummary(id, title, TaskState.valueOf(row.status.name), row.version, state)
        }
        return TaskPage(selected, if (rows.size > request.limit) selected.last().taskId else null)
    }

    private fun decodeContent(id: UUID, bytes: ByteArray): String? {
        if (bytes.size !in 1..8192) return null
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val json = JSONObject(text)
            if (json.length() != 2 || json.opt("taskId") != id.toString() || json.opt("title") !is String) return null
            val command = CreateTask(id, json.getString("title"), id, id)
            val ready = OrionCreateTaskPreparer.prepare(command) as? CreateTaskPreparation.Ready ?: return null
            ready.task.title.takeIf { ready.task.canonicalNormalizedArguments == text }
        } catch (_: Exception) { null }
    }
}
