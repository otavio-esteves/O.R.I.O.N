package network.orion.core.orchestrator.chat

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class OrionStructuredResponseTest {
    private val id = UUID.randomUUID()
    private fun parse(json: String) = OrionStructuredResponse.parse(json, id, id)
    @Test fun closedToolsNormalizeButNeverAcceptModelAuthority() {
        val proposal = parse("""{"type":"tool","skill":"task.create","arguments":{"title":" cafe\u0301 "}}""") as OrionChatResponse.CreateProposal
        assertEquals("café", proposal.command.title)
        assertEquals(id, proposal.command.taskId)
        assertNotNull(parse("""{"type":"tool","skill":"task.list","arguments":{}}"""))
        assertNotNull(parse("""{"type":"tool","skill":"device.battery","arguments":{}}"""))
        for (field in listOf("authorizationLevel", "confirmed", "actionId", "policyVersion", "idempotencyKey")) {
            assertNull(parse("""{"type":"tool","skill":"task.create","arguments":{"title":"ok","$field":"yes"}}"""))
        }
    }
    @Test fun rejectsAmbiguityUnknownToolsFreeTextAndPartialOutput() {
        val invalid = listOf("create task", "{}", "[]", """{"type":"message","text":"ok","text":"bad"}""",
            """{"type":"tool","skill":"shell","arguments":{}}""", """{"type":"message","text":"ok"}junk""",
            """{"type":"tool","skill":"task.create","arguments":{"title":" "}}""",
            """{"type":"tool","skill":"task.create","arguments":{"title":"ok"}""",
            """{"type":"tool","skill":"task.list","arguments":{"limit":true}}""",
            """{"type":"message","text":"ok","authorization":"allow"}""")
        invalid.forEach { assertNull(it, parse(it)) }
    }
    @Test fun unicodeEscapesAndBidiMalformedStringsAreBounded() {
        assertNull(parse("""{"type":"message","text":"\ud800"}"""))
        assertNull(parse("""{"type":"tool","skill":"task.create","arguments":{"title":"""" + "x".repeat(1025) + """"}}"""))
        assertNull(parse(" ".repeat(20000)))
        assertEquals("hi\n🌟", (parse("""{"type":"message","text":"hi\n\ud83c\udf1f"}""") as OrionChatResponse.Message).text)
    }
    @Test fun streamingOnlyPreviewsMessageTextNeverToolArguments() {
        assertEquals("Hel", OrionStructuredResponse.messagePreview("""{"type":"message","text":"Hel"""))
        assertEquals("a\n", OrionStructuredResponse.messagePreview("""{"type":"message","text":"a\n"""))
        assertNull(OrionStructuredResponse.messagePreview("""{"type":"tool","skill":"task.create","arguments":{"title":"Hello"""))
    }
}
