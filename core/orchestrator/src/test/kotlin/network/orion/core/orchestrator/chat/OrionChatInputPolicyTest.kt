package network.orion.core.orchestrator.chat

import java.util.UUID
import network.orion.core.orchestrator.routing.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class OrionChatInputPolicyTest(private val id: String, private val input: String, private val expected: OrionChatInputError?) {
    @Test fun editorPolicyAndAuthoritativeRouterAgreeOnRawInput() {
        assertEquals(id, expected, OrionChatInputPolicy.validate(input))
        val route = OrionIntentRouter().route(OrionRoutingInput(input, UUID.randomUUID(), UUID.randomUUID(), OrionRoutingSource.UI_TEXT, true))
        when (expected) {
            null -> assertTrue(id, route is OrionIntentRoute.CognitiveCandidate)
            OrionChatInputError.EMPTY_INPUT -> assertEquals(OrionRoutingClarification.EMPTY_INPUT,
                (route as OrionIntentRoute.Clarification).reason)
            else -> assertEquals(id, expected.name, (route as OrionIntentRoute.Rejected).reason.name)
        }
        assertFalse("No raw input in rejection diagnostics", route.toString().contains("private-fixture"))
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases(): List<Array<Any?>> = buildList {
            fun row(id: String, text: String, error: OrionChatInputError? = null) { add(arrayOf(id, text, error)) }
            for (bytes in 4095..4097) row("ascii-$bytes", "a".repeat(bytes),
                if (bytes > 4096) OrionChatInputError.INPUT_TOO_LARGE else null)
            row("accent-4095", "á".repeat(2047) + "a")
            row("accent-4096", "á".repeat(2048))
            row("accent-4097", "á".repeat(2048) + "a", OrionChatInputError.INPUT_TOO_LARGE)
            row("accent-4098", "á".repeat(2049), OrionChatInputError.INPUT_TOO_LARGE)
            row("emoji-4095", "😀".repeat(1023) + "abc")
            row("emoji-4096", "😀".repeat(1024))
            row("emoji-4097", "😀".repeat(1024) + "a", OrionChatInputError.INPUT_TOO_LARGE)
            row("emoji-4100", "😀".repeat(1025), OrionChatInputError.INPUT_TOO_LARGE)
            row("combining-4095", "e\u0301".repeat(1365))
            row("combining-4096", "e\u0301".repeat(1365) + "a")
            row("combining-4097-before-nfc", "e\u0301".repeat(1365) + "aa", OrionChatInputError.INPUT_TOO_LARGE)
            for ((id, text) in listOf("high" to "\uD800", "low" to "\uDC00", "middle" to "x\uD800y"))
                row("surrogate-$id", text, OrionChatInputError.INVALID_UNICODE)
            for ((id, c) in listOf("lf" to '\n', "cr" to '\r', "tab" to '\t', "nul" to '\u0000',
                "nel" to '\u0085', "line-separator" to '\u2028', "paragraph-separator" to '\u2029')) {
                row("control-$id", "private-fixture${c}data", OrionChatInputError.INVALID_CHARACTER)
                row("control-edge-$id", "${c}bateria", OrionChatInputError.INVALID_CHARACTER)
            }
            row("empty", "", OrionChatInputError.EMPTY_INPUT)
            row("spaces", "   ", OrionChatInputError.EMPTY_INPUT)
            row("unicode-spaces", "\u2003\u2003", OrionChatInputError.EMPTY_INPUT)
            row("raw-oversized-spaces", " ".repeat(4097), OrionChatInputError.INPUT_TOO_LARGE)
        }
    }
}
