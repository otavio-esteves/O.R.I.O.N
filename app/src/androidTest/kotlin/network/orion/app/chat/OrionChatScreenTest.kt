package network.orion.app.chat

import java.util.UUID
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import network.orion.app.MainActivity
import network.orion.app.OrionApplication
import network.orion.core.queries.tasks.GetTasks
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OrionChatScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val runtime get() = checkNotNull((compose.activity.application as OrionApplication).mainRuntime)
    private fun scroll(text: String) {
        compose.onNodeWithTag("orion_chat").performScrollToNode(hasText(text))
    }
    private fun awaitChatIdle() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("orion_chat").fetchSemanticsNodes().singleOrNull()?.config
            ?.getOrNull(SemanticsProperties.StateDescription) == "Chat disponível" }
    }
    private fun send(text: String) {
        awaitChatIdle()
        scroll("Mensagem"); compose.onNodeWithTag("orion_chat_input").assertIsEnabled().performTextReplacement(text)
        scroll("Enviar")
        compose.onNodeWithText("Enviar").assertIsEnabled().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        // Observe this submission rather than an idle snapshot from the previous request.
        scroll(text)
        compose.onNode(hasText(text, substring = false) and !hasSetTextAction()).assertIsDisplayed()
        awaitChatIdle()
    }
    @Test fun rejectedTaskDraftRemainsEditableAndCanBeCorrected() {
        compose.waitUntil(30_000) { runtime.taskStartupBarrier.isOpen() && compose.activity.hasWindowFocus() }
        compose.onNodeWithText("Chat", useUnmergedTree = true).performClick()
        awaitChatIdle()
        scroll("Mensagem")
        compose.onNodeWithTag("orion_chat_input").performTextReplacement("criar tarefa:")
        scroll("Enviar")
        compose.onNodeWithText("Enviar").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        scroll("Mensagem")
        compose.onNodeWithTag("orion_chat_input").assert(SemanticsMatcher("Exact rejected draft") {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == "criar tarefa:"
        }).assertIsEnabled()
        scroll("Informe o título da tarefa.")
        compose.onNodeWithText("Informe o título da tarefa.").assertIsDisplayed()
        compose.onAllNodes(hasText("criar tarefa:", substring = false) and !hasSetTextAction()).assertCountEquals(0)
        scroll("Mensagem"); compose.onNodeWithTag("orion_chat_input").performTextReplacement("bateria")
        scroll("Enviar")
        compose.onNodeWithText("Enviar").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        scroll("Mensagem"); compose.onNodeWithTag("orion_chat_input").assert(SemanticsMatcher("Empty admitted draft") {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == ""
        })
        scroll("bateria"); compose.onNode(hasText("bateria", substring = false) and !hasSetTextAction()).assertIsDisplayed()
    }
    @Test fun actualChatFastPathsAndDurableExactConfirmationWorkWithoutCortex() {
        compose.waitUntil(30_000) { runtime.taskStartupBarrier.isOpen() && compose.activity.hasWindowFocus() }
        compose.onNodeWithText("Chat", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        val title = "Chat F6 " + UUID.randomUUID()
        send("bateria")
        scroll("Cortex: Sob demanda")
        send("criar tarefa: $title")
        val before = runBlocking(Dispatchers.IO) { runtime.taskKernel!!.query.get(GetTasks()).items }
        assertFalse(before.any { it.title == title })
        scroll("Confirmar criação"); compose.onNodeWithText("Confirmar criação").performClick()
        compose.waitUntil(15_000) {
            runBlocking(Dispatchers.IO) { runtime.taskKernel!!.query.get(GetTasks()).items.any { it.title == title } }
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { runtime.taskStartupBarrier.isOpen() && compose.onAllNodesWithTag("orion_chat").fetchSemanticsNodes().size == 1 }
        send("minhas tarefas")
        assertEquals(1, runBlocking(Dispatchers.IO) { runtime.taskKernel!!.query.get(GetTasks()).items.count { it.title == title } })
        send("Olá, pode conversar?")
        scroll("Cortex: Indisponível")
        send("bateria")
    }
}
