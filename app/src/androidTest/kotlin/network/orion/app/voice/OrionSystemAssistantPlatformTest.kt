package network.orion.app.voice

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.service.voice.VoiceInteractionService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.app.OrionApplication
import network.orion.core.health.OrionAssistantRoleState
import network.orion.voice.ipc.OrionBoundVoiceCoreClient
import network.orion.voice.ipc.VoiceCoreConnectionState
import network.orion.voice.service.OrionAssistantRecognitionService
import network.orion.voice.service.OrionVoiceInteractionService
import network.orion.voice.service.OrionVoiceInteractionSessionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class OrionSystemAssistantPlatformTest {
    @Suppress("DEPRECATION")
    @Test fun systemAssistantDeclaresOnlyItsRequiredProtectedServices() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val manager = app.packageManager
        val interactor = OrionVoiceInteractionService::class.java
        val session = OrionVoiceInteractionSessionService::class.java
        val recognizer = OrionAssistantRecognitionService::class.java
        val filter = Intent(VoiceInteractionService.SERVICE_INTERFACE).setPackage(app.packageName)
        val resolved = manager.queryIntentServices(filter, PackageManager.GET_META_DATA)
        assertEquals(listOf(interactor.name), resolved.map { it.serviceInfo.name })
        val controlInfo = resolved.single().serviceInfo
        val sessionInfo = manager.getServiceInfo(ComponentName(app, session), 0)
        val recognizerInfo = manager.getServiceInfo(ComponentName(app, recognizer), 0)
        val gatewayInfo = manager.getServiceInfo(ComponentName(app, OrionCoreGatewayService::class.java), 0)

        assertTrue(controlInfo.exported)
        assertEquals(Manifest.permission.BIND_VOICE_INTERACTION, controlInfo.permission)
        assertFalse(sessionInfo.exported)
        assertEquals(Manifest.permission.BIND_VOICE_INTERACTION, sessionInfo.permission)
        assertFalse(recognizerInfo.exported)
        assertEquals(Manifest.permission.BIND_VOICE_INTERACTION, recognizerInfo.permission)
        assertFalse(gatewayInfo.exported)
        assertEquals("${app.packageName}:voice", controlInfo.processName)
        assertEquals("${app.packageName}:voice_session", sessionInfo.processName)
        assertEquals("${app.packageName}:voice_session", recognizerInfo.processName)
        assertEquals(app.packageName, gatewayInfo.processName)
        for (service in listOf(controlInfo, sessionInfo, recognizerInfo, gatewayInfo)) {
            assertEquals(0, service.flags and ServiceInfo.FLAG_ISOLATED_PROCESS)
        }
        val metadata = checkNotNull(controlInfo.loadXmlMetaData(manager, VoiceInteractionService.SERVICE_META_DATA))
        metadata.use {
            while (it.eventType != XmlPullParser.START_TAG && it.eventType != XmlPullParser.END_DOCUMENT) it.next()
            assertEquals("voice-interaction-service", it.name)
            val android = "http://schemas.android.com/apk/res/android"
            assertEquals(session.name, it.getAttributeValue(android, "sessionService"))
            assertEquals(recognizer.name, it.getAttributeValue(android, "recognitionService"))
            assertTrue(it.getAttributeBooleanValue(android, "supportsAssist", false))
            assertFalse(it.getAttributeBooleanValue(android, "supportsLaunchVoiceAssistFromKeyguard", false))
        }
    }

    @Test fun coreRespondsWithoutAnAssistantRole() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<OrionApplication>()
        val roles = checkNotNull(app.getSystemService(RoleManager::class.java))
        assertFalse(roles.isRoleHeld(RoleManager.ROLE_ASSISTANT))
        val runtime = checkNotNull(app.mainRuntime)
        runtime.refreshAssistantRole()
        assertTrue(runtime.assistantRoleState.value in setOf(
            OrionAssistantRoleState.NOT_SELECTED, OrionAssistantRoleState.UNAVAILABLE,
        ))
        assertFalse(runtime.capabilities.isAvailable(OrionAssistantRole.capability))
        awaitAssistantFixtureCore(app)
    }
}

internal suspend fun awaitAssistantFixtureCore(app: Context) {
    val core = OrionBoundVoiceCoreClient(app)
    try {
        assertEquals(VoiceCoreConnectionState.CONNECTED, core.connect())
        withTimeout(30_000) {
            while (!core.healthCheck()) delay(100)
        }
    } finally {
        core.disconnect()
    }
}
