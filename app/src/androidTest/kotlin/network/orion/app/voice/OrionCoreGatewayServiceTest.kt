package network.orion.app.voice

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.core.ipc.*
import network.orion.core.time.AndroidOrionClock
import network.orion.voice.ipc.OrionVoiceAdmission
import network.orion.core.health.CoreReadiness
import network.orion.voice.ipc.OrionBoundVoiceCoreClient
import network.orion.voice.ipc.VoiceCoreConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionCoreGatewayServiceTest {
    @Test fun privateMainGatewayNegotiatesAndReportsReadiness() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(app, OrionCoreGatewayService::class.java)
        @Suppress("DEPRECATION")
        val service = app.packageManager.getServiceInfo(component, 0)
        assertFalse(service.exported)
        assertEquals(app.packageName, service.processName)
        val client = OrionBoundVoiceCoreClient(app)
        try {
            assertEquals(VoiceCoreConnectionState.CONNECTED, client.connect())
            assertTrue(client.getCoreReadiness() in CoreReadiness.entries.map { it.name })
        } finally {
            client.disconnect()
        }
        assertEquals(VoiceCoreConnectionState.UNAVAILABLE, client.state)
        assertNull(client.getCoreReadiness())
    }

    @Test fun callbackStreamsOneTerminalResponseForAnAcceptedRequest() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val client = OrionBoundVoiceCoreClient(app)
        try {
            assertEquals(VoiceCoreConnectionState.CONNECTED, client.connect())
            withTimeout(30_000) {
                while (!client.healthCheck()) delay(100)
            }
            val request = VoiceUtteranceRequest(IpcSchemaVersion(1), UUID.randomUUID(), UUID.randomUUID(),
                "bateria", true, "pt-BR", AndroidOrionClock(app.contentResolver).wallNow(),
                OrionVoiceSource.PUSH_TO_TALK, 0, UUID.randomUUID())
            assertEquals(OrionVoiceAdmission.ACCEPTED, client.submitUtterance(request))
            val responses = withTimeout(10_000) { client.responses(request.requestId).toList() }
            assertEquals(listOf(1L, 2L), responses.filter { it.kind != OrionVoiceResponseKind.STATUS }.map { it.sequence })
            assertEquals(OrionVoiceTerminalState.SUCCEEDED, responses.last().terminalState)
            assertEquals(1, responses.count { it.terminalState != null })
        } finally { client.disconnect() }
    }
}
