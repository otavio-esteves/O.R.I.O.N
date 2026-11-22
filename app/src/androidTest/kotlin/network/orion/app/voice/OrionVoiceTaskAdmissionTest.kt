package network.orion.app.voice

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.app.MainActivity
import network.orion.app.OrionApplication
import network.orion.core.ipc.*
import network.orion.core.queries.tasks.GetTasks
import network.orion.core.time.AndroidOrionClock
import network.orion.voice.ipc.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OrionVoiceTaskAdmissionTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test fun finalVoiceRequestPreparesButDoesNotExecuteTask() {
        val context = compose.activity.application as OrionApplication
        val runtime = checkNotNull(context.mainRuntime)
        compose.waitUntil(30_000) { runtime.taskStartupBarrier.isOpen() && compose.activity.hasWindowFocus() }
        val marker = "estudar voz " + UUID.randomUUID()
        runBlocking(Dispatchers.IO) {
            val client = OrionBoundVoiceCoreClient(context)
            try {
                assertEquals(VoiceCoreConnectionState.CONNECTED, client.connect())
                val request = VoiceUtteranceRequest(IpcSchemaVersion(1), UUID.randomUUID(), UUID.randomUUID(),
                    "criar tarefa $marker", true, "pt-BR", AndroidOrionClock(context.contentResolver).wallNow(),
                    OrionVoiceSource.PUSH_TO_TALK, 0, UUID.randomUUID())
                assertEquals(OrionVoiceAdmission.ACCEPTED, client.submitUtterance(request))
                val terminal = withTimeout(10_000) { client.responses(request.requestId).toList() }.last()
                assertEquals(OrionVoiceTerminalState.SUCCEEDED, terminal.terminalState)
                assertTrue(terminal.requiresConfirmation)
                val tasks = checkNotNull(runtime.taskKernel)
                assertTrue(tasks.pending().any { it.display.title == marker })
                assertFalse(tasks.query.get(GetTasks()).items.any { it.title == marker })
            } finally { client.disconnect() }
        }
    }
}
