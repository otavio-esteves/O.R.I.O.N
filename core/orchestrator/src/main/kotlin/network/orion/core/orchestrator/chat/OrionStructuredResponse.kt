package network.orion.core.orchestrator.chat

import java.util.UUID
import network.orion.core.commands.tasks.*
import network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor
import network.orion.core.skills.metadata.tasks.OrionListTasksDescriptor
import network.orion.core.skills.metadata.device.OrionDeviceDescriptors

/** Interpretation only. A proposal carries no policy, presence or confirmation authority. */
sealed interface OrionChatResponse {
    class Message(val text: String) : OrionChatResponse { override fun toString() = "Message(<redacted>)" }
    data object TaskList : OrionChatResponse
    data object Battery : OrionChatResponse
    class CreateProposal(val command: CreateTask) : OrionChatResponse { override fun toString() = "CreateProposal(<redacted>)" }
}

/** Small closed JSON schema: only objects and strings, no coercion, duplicate members or trailing tokens. */
object OrionStructuredResponse {
    const val MAX_OUTPUT_BYTES = 16384
    fun parse(raw: String, inputId: UUID, correlationId: UUID): OrionChatResponse? = try {
        require(raw.length <= MAX_OUTPUT_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_OUTPUT_BYTES)
        val reader = Reader(raw)
        val root = reader.obj(0)
        reader.ws(); require(reader.atEnd())
        when (root["type"]) {
            "message" -> {
                require(root.keys == setOf("type", "text"))
                val text = root["text"] as? String ?: error("schema")
                require(text.isNotBlank())
                OrionChatResponse.Message(text)
            }
            "tool" -> {
                require(root.keys == setOf("type", "skill", "arguments"))
                val args = root["arguments"] as? Map<*, *> ?: error("schema")
                when (root["skill"]) {
                    OrionListTasksDescriptor.value.name -> { require(args.isEmpty()); OrionChatResponse.TaskList }
                    OrionDeviceDescriptors.battery.name -> { require(args.isEmpty()); OrionChatResponse.Battery }
                    OrionCreateTaskDescriptor.value.name -> {
                        require(args.keys == setOf("title"))
                        val title = args["title"] as? String ?: error("schema")
                        when (val prepared = OrionCreateTaskPreparer.prepare(CreateTask(inputId, title, inputId, correlationId))) {
                            is CreateTaskPreparation.Ready -> OrionChatResponse.CreateProposal(CreateTask(inputId, prepared.task.title, inputId, correlationId))
                            is CreateTaskPreparation.Rejected -> null
                        }
                    }
                    else -> null
                }
            }
            else -> null
        }
    } catch (_: IllegalArgumentException) { null } catch (_: IllegalStateException) { null }

    /** Preview is display-only and discarded on any failed terminal. Tools never stream into execution. */
    fun messagePreview(raw: String): String? = try {
        val r = Reader(raw)
        r.expect('{'); require(r.string() == "type"); r.expect(':'); require(r.string() == "message")
        r.expect(','); require(r.string() == "text"); r.expect(':')
        r.string(partial = true).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
    } catch (_: IllegalArgumentException) { null } catch (_: IllegalStateException) { null }

    /** Encodes all external text as a JSON string; role delimiters cannot become prompt framing. */
    fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c -> when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            '<' -> append("\\u003c"); '>' -> append("\\u003e")
            else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
        } }
        append('"')
    }

    private class Reader(private val raw: String) {
        private var i = 0
        fun atEnd() = i == raw.length
        fun ws() { while (i < raw.length && raw[i] in " \t\r\n") i++ }
        fun expect(c: Char) { ws(); require(i < raw.length && raw[i++] == c) }
        fun obj(depth: Int): Map<String, Any> {
            require(depth < 3); expect('{'); ws()
            val result = linkedMapOf<String, Any>()
            if (i < raw.length && raw[i] == '}') { i++; return result }
            while (true) {
                val key = string(); require(key !in result && result.size < 8); expect(':'); ws()
                val value: Any = if (i < raw.length && raw[i] == '{') obj(depth + 1) else string()
                result[key] = value; ws(); require(i < raw.length)
                val c = raw[i++]; if (c == '}') return result; require(c == ',')
            }
        }
        fun string(partial: Boolean = false): String {
            expect('"')
            val out = StringBuilder()
            while (i < raw.length) {
                var c = raw[i++]
                if (c == '"') return validate(out.toString())
                require(c.code >= 32)
                if (c == '\\') {
                    if (partial && i == raw.length) return out.toString()
                    require(i < raw.length)
                    c = when (val escaped = raw[i++]) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> {
                            if (partial && i + 4 > raw.length) return out.toString()
                            require(i + 4 <= raw.length)
                            val code = raw.substring(i, i + 4); require(code.all { it in "0123456789abcdefABCDEF" }); i += 4
                            code.toInt(16).toChar()
                        }
                        else -> error("escape")
                    }
                }
                out.append(c)
            }
            require(partial); return out.toString()
        }
        private fun validate(text: String): String {
            var index = 0
            while (index < text.length) {
                val c = text[index++]
                if (c.isHighSurrogate()) { require(index < text.length && text[index++].isLowSurrogate()) }
                else require(!c.isLowSurrogate())
                require(c.code >= 32 || c == '\n' || c == '\r' || c == '\t')
            }
            return text
        }
    }
}
