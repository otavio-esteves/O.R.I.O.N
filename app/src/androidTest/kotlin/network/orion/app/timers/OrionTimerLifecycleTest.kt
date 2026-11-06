package network.orion.app.timers

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import network.orion.app.MainActivity
import network.orion.core.commands.timers.*
import network.orion.core.orchestrator.timers.*
import network.orion.core.queries.timers.*
import network.orion.feature.timers.TimersScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The composition remains alive; only the injected lifecycle stops and restarts. */
@RunWith(AndroidJUnit4::class)
class OrionTimerLifecycleTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun periodicStatusReadsStopBelowStartedAndResumeWithoutLosingDraftOrDrainingQueues() {
        val owner=TestLifecycleOwner()
        val port=ReadGateway()
        compose.activityRule.scenario.onActivity { activity ->
            owner.registry.currentState=Lifecycle.State.CREATED
            activity.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    MaterialTheme { TimersScreen(port) }
                }
            }
        }
        compose.waitUntil(10_000) { port.listReads.get()==1 }
        compose.waitForIdle()
        assertEquals(0,port.statusReads.get())
        compose.onNodeWithText("Título do timer").performTextInput("Rascunho preservado")
        compose.runOnIdle { owner.registry.currentState=Lifecycle.State.STARTED }
        compose.waitUntil(10_000) { port.statusReads.get()>0 }
        compose.runOnIdle { owner.registry.currentState=Lifecycle.State.CREATED }
        compose.waitForIdle()
        val frozen=port.statusReads.get()
        // Wait longer than a full polling interval without blocking the UI thread.
        assertFalse(CountDownLatch(1).await(1500,TimeUnit.MILLISECONDS))
        assertEquals(frozen,port.statusReads.get())
        assertEquals(1,port.listReads.get());assertEquals(1,port.queueDrains.get())
        compose.onNodeWithText("Título do timer").assertTextContains("Rascunho preservado")
        compose.runOnIdle { owner.registry.currentState=Lifecycle.State.STARTED }
        compose.waitUntil(10_000) { port.statusReads.get()>frozen }
        compose.onNodeWithText("Título do timer").assertTextContains("Rascunho preservado")
        assertEquals(1,port.listReads.get());assertEquals(1,port.queueDrains.get())
        compose.runOnIdle { owner.registry.currentState=Lifecycle.State.DESTROYED }
    }
    private class TestLifecycleOwner:LifecycleOwner {
        val registry=LifecycleRegistry(this)
        override val lifecycle:Lifecycle get()=registry
    }
    private class ReadGateway:TimerGateway {
        val listReads=AtomicInteger();val statusReads=AtomicInteger();val queueDrains=AtomicInteger()
        private val row=TimerSummary(UUID.randomUUID(),"Timer persistente",TimerState.ACTIVE,0,
            TimerContentState.READABLE,60_000,59_000,TimerClockBasis.MONOTONIC)
        override suspend fun list(request:GetTimers):TimerPage { listReads.incrementAndGet();return TimerPage(listOf(row),null) }
        override suspend fun status(request:GetTimerStatus):TimerSummary { assertEquals(row.timerId,request.timerId);statusReads.incrementAndGet();return row }
        override suspend fun pending()=emptyList<TimerConfirmation>()
        override suspend fun processPending():Int { queueDrains.incrementAndGet();return 0 }
        override suspend fun create(command:CreateTimer):CreateTimerResult=throw AssertionError("Polling cannot submit mutations")
        override suspend fun change(command:TimerStatusCommand):TimerStatusResult=throw AssertionError("Polling cannot submit mutations")
        override suspend fun confirm(actionId:UUID,displayedVersion:Long):TimerOperationOutcome=throw AssertionError("Polling cannot confirm actions")
        override suspend fun dismiss(actionId:UUID,displayedVersion:Long):TimerOperationOutcome=throw AssertionError("Polling cannot dismiss actions")
    }
}
