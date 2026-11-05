package network.orion.app.timers

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import network.orion.core.commands.timers.*
import network.orion.core.orchestrator.timers.*
import network.orion.core.queries.timers.*
import network.orion.feature.timers.TimersScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionTimerScreenTest {
    @get:Rule val compose=createComposeRule()
    @Test fun timerUiShowsExactDurationBeforeSendingSeparateHumanConfirmation() {
        val port=FakeGateway()
        compose.setContent { MaterialTheme { TimersScreen(port) } }
        compose.waitForIdle()
        compose.onNodeWithText("Título do timer").performTextInput("Preparar café")
        compose.onNodeWithText("Duração em segundos").performTextClearance()
        compose.onNodeWithText("Duração em segundos").performTextInput("30")
        compose.onNodeWithText("Preparar criação").performClick()
        compose.waitUntil { port.last!=null }
        assertEquals(30_000L,port.last!!.durationMillis)
        assertEquals(0,port.confirmations)
        compose.onNodeWithText("Duração: 30 segundos").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confirmar criação").performScrollTo().performClick()
        compose.waitUntil { port.confirmations==1 }
        compose.onNodeWithText("Alertas em segundo plano ainda não estão disponíveis.",substring=true).assertExists()
    }
    private class FakeGateway:TimerGateway {
        @Volatile var last:CreateTimer?=null
        @Volatile var confirmations=0
        private val id=UUID.randomUUID()
        override suspend fun create(command:CreateTimer):CreateTimerResult { last=command;return CreateTimerResult.QUEUED }
        override suspend fun change(command:TimerStatusCommand)=TimerStatusResult.UNAVAILABLE
        override suspend fun list(request:GetTimers)=TimerPage(emptyList(),null)
        override suspend fun status(request:GetTimerStatus):TimerSummary?=null
        override suspend fun pending()=last?.let { listOf(TimerConfirmation(id,1,TimerOperation.CREATE,it.title,it.durationMillis)) } ?: emptyList()
        override suspend fun confirm(actionId:UUID,displayedVersion:Long):TimerOperationOutcome { confirmations++;last=null;return TimerOperationOutcome.APPLIED }
        override suspend fun dismiss(actionId:UUID,displayedVersion:Long)=TimerOperationOutcome.UNAVAILABLE
        override suspend fun processPending()=0
    }
}
