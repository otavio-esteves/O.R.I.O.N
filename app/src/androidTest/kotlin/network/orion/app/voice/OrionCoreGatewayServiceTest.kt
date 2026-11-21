package network.orion.app.voice

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
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
}
