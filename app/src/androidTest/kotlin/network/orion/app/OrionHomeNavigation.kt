package network.orion.app

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo

internal fun ComposeTestRule.openOrionDestination(label: String) {
    if (onAllNodes(hasText("Início") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithText("Início").performClick()
    }
    onNodeWithTag("orion_globe").performClick()
    onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
}
