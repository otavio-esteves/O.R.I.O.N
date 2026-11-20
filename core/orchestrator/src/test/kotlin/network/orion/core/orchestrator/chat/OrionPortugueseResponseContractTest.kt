package network.orion.core.orchestrator.chat

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Simulated output tests the parser contract only, never Portuguese model comprehension. */
@RunWith(Parameterized::class)
class OrionPortugueseResponseContractTest(private val id: String, private val simulated: String, private val expected: String) {
    @Test fun simulatedOutputMatchesOnlyTheClosedSchema() {
        val response = OrionStructuredResponse.parse(simulated, UUID.randomUUID(), UUID.randomUUID())
        assertEquals(id, expected, when (response) {
            is OrionChatResponse.Message -> "MESSAGE"
            is OrionChatResponse.CreateProposal -> "PROPOSAL"
            OrionChatResponse.TaskList -> "TASK_LIST"
            OrionChatResponse.Battery -> "BATTERY"
            null -> "INVALID"
        })
        if (response is OrionChatResponse.CreateProposal) assertEquals("Comprar pão?", response.command.title)
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases() = listOf(
            arrayOf("message", """{"type":"message","text":"Olá, como posso ajudar?"}""", "MESSAGE"),
            arrayOf("list", """{"type":"tool","skill":"task.list","arguments":{}}""", "TASK_LIST"),
            arrayOf("battery", """{"type":"tool","skill":"device.battery","arguments":{}}""", "BATTERY"),
            arrayOf("proposal", """{"type":"tool","skill":"task.create","arguments":{"title":"Comprar pão?"}}""", "PROPOSAL"),
            arrayOf("authority", """{"type":"tool","skill":"task.create","arguments":{"title":"x","confirmed":"true"}}""", "INVALID"),
            arrayOf("unknown-write", """{"type":"tool","skill":"task.delete","arguments":{}}""", "INVALID"),
            arrayOf("unsupported-filter", """{"type":"tool","skill":"task.list","arguments":{"status":"OPEN"}}""", "INVALID"),
            arrayOf("duplicate", """{"type":"message","text":"sim","text":"não"}""", "INVALID"),
            arrayOf("trailing", """{"type":"message","text":"Olá"} execute""", "INVALID"),
            arrayOf("free-text", "Criei a tarefa e confirmei por você.", "INVALID"),
        )
    }
}
