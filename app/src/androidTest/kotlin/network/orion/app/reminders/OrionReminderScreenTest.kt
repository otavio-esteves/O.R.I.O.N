package network.orion.app.reminders

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import network.orion.app.MainActivity
import network.orion.app.OrionApplication
import org.junit.Rule
import org.junit.Test

class OrionReminderScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun ready() {
        compose.waitUntil(10_000) {
            compose.activity.hasWindowFocus() && compose.onAllNodesWithTag("orion_reminders").fetchSemanticsNodes().singleOrNull()?.config
                ?.getOrNull(SemanticsProperties.StateDescription) == "Lembretes atualizados"
        }
    }
    private fun within(title: String, tag: String) = hasTestTag(tag) and hasAnyDescendant(hasText(title, substring = true))
    private fun click(label: String, title: String? = null, tag: String = "orion_reminder_confirmation") {
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
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Título do lembrete"))
        compose.onNodeWithText("Título do lembrete").performTextInput(title)
        // Synchronize with the accepted draft, not only the preceding list reload.
        // The button's enabled assertion below must still hold after scrolling.
        compose.onNodeWithText("Título do lembrete").assertTextContains(title)
    }
    @Test fun reminderRequiresExplicitConfirmationAndSurvivesRecreationBeforeCompletion() {
        compose.waitUntil(30_000) {
            checkNotNull((compose.activity.application as OrionApplication).mainRuntime).taskStartupBarrier.isOpen()
        }
        compose.onNodeWithText("Lembretes", useUnmergedTree = true).performClick()
        val first = "Revisão UI ${java.util.UUID.randomUUID()}"
        val second = "Cancelar UI ${java.util.UUID.randomUUID()}"
        type(first)
        click("Preparar criação")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(within(first, "orion_reminder_confirmation"))
        compose.onNode(within(first, "orion_reminder_row")).assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        ready()
        click("Confirmar criação", first)
        click("Adiar 10 min", first, "orion_reminder_row")
        click("Confirmar adiamento", first)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Pendente") and hasAnyAncestor(within(first, "orion_reminder_row")))
        type(second)
        click("Preparar criação")
        click("Confirmar criação", second)
        click("Cancelar lembrete", second, "orion_reminder_row")
        click("Confirmar cancelamento", second)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Cancelado") and hasAnyAncestor(within(second, "orion_reminder_row")))
    }
}
