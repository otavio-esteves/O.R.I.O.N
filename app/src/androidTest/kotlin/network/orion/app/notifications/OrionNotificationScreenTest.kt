package network.orion.app.notifications

import androidx.activity.compose.setContent
import network.orion.app.MainActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import network.orion.core.commands.notifications.*
import network.orion.core.orchestrator.notifications.*
import network.orion.feature.notifications.NotificationsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionNotificationScreenTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun permissionRefreshPreservesUnsentDraftAndNeverCreatesOrConfirmsAnAction() {
        val port=FakeGateway();var prompts=0;val revision=mutableLongStateOf(0)
        compose.activityRule.scenario.onActivity { activity->
            activity.setContent { MaterialTheme { NotificationsScreen(port,refreshRevision=revision.longValue,onRequestPermission={prompts++}) } }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Título da notificação").performScrollTo().performTextInput("Título exato")
        compose.onNodeWithText("Mensagem da notificação").performScrollTo().performTextInput("Corpo confidencial")
        compose.onNodeWithText("Permitir notificações").performScrollTo().performClick()
        assertEquals(1,prompts);assertEquals(0,port.preparations);assertEquals(0,port.posts)
        compose.runOnIdle { port.capability=NotificationAvailability.AVAILABLE;revision.longValue++ }
        compose.waitForIdle()
        compose.onNodeWithText("Título exato").assertExists();compose.onNodeWithText("Corpo confidencial").assertExists()
        assertEquals(1,port.drains);assertEquals(0,port.preparations);assertEquals(0,port.posts)
        compose.onNodeWithText("Preparar notificação").performScrollTo().performClick()
        compose.waitUntil { port.preparations==1 }
        compose.onNodeWithText("Publicar esta notificação?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Título exato").assertExists();compose.onNodeWithText("Corpo confidencial").assertExists()
        assertEquals(0,port.posts)
        compose.onNodeWithText("Confirmar e publicar").performScrollTo().performClick()
        compose.waitUntil { port.posts==1 }
        compose.onNodeWithText("Notificação publicada.").performScrollTo().assertIsDisplayed()
    }
    private class FakeGateway:NotificationGateway {
        var capability=NotificationAvailability.PERMISSION_DENIED
        @Volatile var preparations=0
        @Volatile var posts=0
        @Volatile var drains=0
        private var command:PostNotification?=null
        private val id=UUID.randomUUID()
        override suspend fun prepare(command:PostNotification):PostNotificationResult { this.command=command;preparations++;return PostNotificationResult.QUEUED }
        override suspend fun pending()=command?.let { listOf(NotificationConfirmation(id,1,it.title,it.body)) }?:emptyList()
        override suspend fun confirm(actionId:UUID,displayedVersion:Long):NotificationOutcome { posts++;command=null;return NotificationOutcome.POSTED }
        override suspend fun dismiss(actionId:UUID,displayedVersion:Long)=NotificationOutcome.APPLIED
        override suspend fun availability()=capability
        override suspend fun processPending():Int { drains++;return 0 }
    }
}
