package network.orion.app.tasks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(target)
        compose.onNode(target).assertIsEnabled().performClick()
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
