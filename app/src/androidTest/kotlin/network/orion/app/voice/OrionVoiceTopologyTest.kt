package network.orion.app.voice

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import network.orion.voice.service.OrionVoiceControlService
import network.orion.voice.service.OrionVoiceSessionService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionVoiceTopologyTest {
    @Suppress("DEPRECATION")
    @Test fun voiceComponentsArePrivateAndRolesAreSeparated() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val services = listOf(
            OrionCoreGatewayService::class.java to app.packageName,
            OrionVoiceControlService::class.java to "${app.packageName}:voice",
            OrionVoiceSessionService::class.java to "${app.packageName}:voice_session",
        )
        for ((type, process) in services) {
            val service = app.packageManager.getServiceInfo(ComponentName(app, type), 0)
            assertEquals(process, service.processName)
            assertFalse(service.exported)
            assertEquals(0, service.flags and android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS)
        }
    }
}
