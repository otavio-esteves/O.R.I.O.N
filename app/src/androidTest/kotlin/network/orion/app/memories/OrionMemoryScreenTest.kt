package network.orion.app.memories

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import network.orion.app.MainActivity
import network.orion.core.commands.memories.*
import network.orion.core.orchestrator.memories.*
import network.orion.core.queries.memories.*
import network.orion.feature.memories.MemoriesScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OrionMemoryScreenTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun nativeMemoryFlowRequiresSeparateExactHumanConfirmationBeforeCreateAndForget() {
        val confirms=AtomicInteger()
        var row:MemorySummary?=null
        var request:CreateMemory?=null
        var pending:MemoryConfirmation?=null
        val gateway=object:MemoryGateway {
            override suspend fun create(command:CreateMemory):CreateMemoryResult {
                request=command
                pending=MemoryConfirmation(UUID.randomUUID(),1,MemoryOperation.CREATE,command.content)
                return CreateMemoryResult.QUEUED
            }
            override suspend fun change(command:MemoryStatusCommand):MemoryStatusResult {
                assertEquals(row!!.memoryId,command.memoryId); assertEquals(row!!.version,command.expectedVersion)
                pending=MemoryConfirmation(UUID.randomUUID(),1,MemoryOperation.DELETE,row!!.content)
                return MemoryStatusResult.QUEUED
            }
            override suspend fun list(request:GetMemories)=MemoryPage(listOfNotNull(row),null)
            override suspend fun pending()=listOfNotNull(pending)
            override suspend fun confirm(actionId:UUID,displayedVersion:Long):MemoryOperationOutcome {
                assertEquals(pending!!.actionId,actionId); assertEquals(pending!!.version,displayedVersion)
                confirms.incrementAndGet()
                row=if (pending!!.operation==MemoryOperation.CREATE) MemorySummary(request!!.memoryId,request!!.content,MemoryState.ACTIVE,0,MemoryContentState.READABLE) else null
                pending=null
                return MemoryOperationOutcome.APPLIED
            }
            override suspend fun dismiss(actionId:UUID,displayedVersion:Long):MemoryOperationOutcome=throw AssertionError("Unexpected dismissal")
            override suspend fun processPending()=0
        }
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { MaterialTheme { MemoriesScreen(gateway) } } }
        compose.onNodeWithText("O que você quer lembrar?").performTextInput("Preferência pessoal de teste")
        compose.onNodeWithText("Preparar memória").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Guardar esta memória pessoal?").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0,confirms.get()); assertNull(row)
        compose.onNodeWithText("Confirmar memória").performScrollTo().performClick()
        compose.waitUntil(10_000) { confirms.get()==1 && compose.onAllNodesWithTag("orion_memory_row").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Esquecer memória").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Esquecer esta memória?").fetchSemanticsNodes().isNotEmpty() }
        assertNotNull(row); assertEquals(1,confirms.get())
        compose.onNodeWithText("Confirmar esquecimento").performScrollTo().performClick()
        compose.waitUntil(10_000) { confirms.get()==2 && compose.onAllNodesWithTag("orion_memory_row").fetchSemanticsNodes().isEmpty() }
        assertNull(row)
    }
    @Test fun recoveryReadRefreshPreservesUnsubmittedDraftWithoutMutatingTheCore() {
        val available=mutableStateOf(false)
        val version=mutableStateOf<Instant?>(null)
        val reads=AtomicInteger()
        val row=MemorySummary(UUID.randomUUID(),"Memória restaurada",MemoryState.ACTIVE,0,MemoryContentState.READABLE)
        val gateway=object:MemoryGateway {
            override suspend fun list(request:GetMemories):MemoryPage? { reads.incrementAndGet(); return if (available.value) MemoryPage(listOf(row),null) else null }
            override suspend fun pending()=emptyList<MemoryConfirmation>()
            override suspend fun processPending()=0
            override suspend fun create(command:CreateMemory):CreateMemoryResult=throw AssertionError("Unexpected mutation")
            override suspend fun change(command:MemoryStatusCommand):MemoryStatusResult=throw AssertionError("Unexpected mutation")
            override suspend fun confirm(actionId:UUID,displayedVersion:Long):MemoryOperationOutcome=throw AssertionError("Unexpected mutation")
            override suspend fun dismiss(actionId:UUID,displayedVersion:Long):MemoryOperationOutcome=throw AssertionError("Unexpected mutation")
        }
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { MaterialTheme { MemoriesScreen(gateway,version.value) } } }
        compose.waitUntil(10_000) { reads.get()>0 }
        compose.onNodeWithText("O que você quer lembrar?").performTextInput("Rascunho transitório")
        compose.runOnIdle { available.value=true; version.value=Instant.EPOCH }
        compose.waitUntil(10_000) { reads.get()>=2 && compose.onAllNodesWithText("Memória restaurada").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("O que você quer lembrar?").assertTextContains("Rascunho transitório")
    }
}
