package network.orion.app.tasks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import network.orion.app.MainActivity
import network.orion.app.OrionApplication
import org.junit.Rule
import org.junit.Test

class OrionTaskScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun ready() {
        compose.waitUntil(10_000) {
            compose.activity.hasWindowFocus() && compose.onAllNodesWithTag("orion_tasks").fetchSemanticsNodes().singleOrNull()?.config
                ?.getOrNull(SemanticsProperties.StateDescription) == "Tarefas atualizadas"
        }
    }
    private fun within(title: String, tag: String) = hasTestTag(tag) and hasAnyDescendant(hasText(title))
    private fun click(label: String, title: String? = null, tag: String = "orion_task_confirmation") {
        ready()
        val target = if (title == null) hasText(label) else hasText(label) and hasAnyAncestor(within(title, tag))
        try {
            // An idle first frame after recreation can precede the asynchronous pending read.
            // Only retry absence of the same exact scoped selector; never replay a click.
            var missing: AssertionError? = null
            try {
                compose.waitUntil(10_000) {
                    try {
                        compose.onNode(hasScrollToIndexAction()).performScrollToNode(target)
                        true
                    } catch (failure: AssertionError) {
                        if (!failure.message.orEmpty().startsWith("No node found that matches")) throw failure
                        missing = failure
                        false
                    }
                }
            } catch (timeout: ComposeTimeoutException) {
                throw missing ?: timeout
            }
            compose.onNode(target).assertIsEnabled().performClick()
        } catch (failure: AssertionError) {
            if (title != null) {
                try {
                    val runtime = checkNotNull((compose.activity.application as OrionApplication).mainRuntime)
                    val selected = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                        runtime.taskKernel?.pending().orEmpty()
                    }
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                        putString("orion_ui_pending_total", selected.size.toString())
                        putString("orion_ui_pending_fixture_matches", selected.count { it.display.title == title }.toString())
                        putString("orion_ui_pending_unreadable", selected.count { it.display.title == null }.toString())
                    })
                } catch (_: Exception) { /* The original UI assertion remains the failure. */ }
            }
            throw failure
        }
        ready()
        if (label.startsWith("Confirmar")) {
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            try { compose.onNodeWithText("Alteração concluída.").assertExists() }
            catch (failure: AssertionError) { throw AssertionError(compose.onRoot().printToString(maxDepth = 8), failure) }
        }
    }
    private fun type(title: String) {
        ready()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Título da tarefa"))
        compose.onNodeWithText("Título da tarefa").performTextInput(title)
    }
    @Test fun taskRequiresExplicitConfirmationAndSurvivesRecreationBeforeCompletion() {
        compose.waitUntil(30_000) {
            checkNotNull((compose.activity.application as OrionApplication).mainRuntime).taskStartupBarrier.isOpen()
        }
        compose.onNodeWithText("Tarefas", useUnmergedTree = true).performClick()
        val first = "Revisão UI ${java.util.UUID.randomUUID()}"
        val second = "Cancelar UI ${java.util.UUID.randomUUID()}"
        type(first)
        click("Preparar criação")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(within(first, "orion_task_confirmation"))
        compose.onNode(within(first, "orion_task_row")).assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        ready()
        click("Confirmar criação", first)
        click("Concluir", first, "orion_task_row")
        click("Confirmar conclusão", first)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Concluída") and hasAnyAncestor(within(first, "orion_task_row")))
        type(second)
        click("Preparar criação")
        click("Confirmar criação", second)
        click("Cancelar tarefa", second, "orion_task_row")
        click("Confirmar cancelamento", second)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Cancelada") and hasAnyAncestor(within(second, "orion_task_row")))
    }
}
