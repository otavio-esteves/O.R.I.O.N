package network.orion.app.voice

import network.orion.app.openOrionDestination
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.service.voice.VoiceInteractionService
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import network.orion.app.MainActivity
import network.orion.voice.service.OrionVoiceInteractionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class OrionWakeWordPlatformTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun diagnosticsReportsWakeWordDisabledWhilePhysicalQualificationIsDeferred() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.openOrionDestination("Diagnóstico")
            compose.onNodeWithText("Palavra de ativação desativada (DISABLED). Qualificação física adiada.")
                .performScrollTo().assertIsDisplayed()
        }
    }

    @Suppress("DEPRECATION")
    @Test fun packagedVoiceComponentsExposeNoHotwordActivationPath() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val manager = app.packageManager
        val componentFlags = PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE
        val installed = manager.getPackageInfo(app.packageName, componentFlags or
            PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS)
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("network.orion.voice.wakeword.OrionWakeWordDetector", false, app.classLoader)
        }

        assertFalse(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE in installed.requestedPermissions.orEmpty())
        assertFalse("android.permission.CAPTURE_AUDIO_HOTWORD" in installed.requestedPermissions.orEmpty())
        for (service in installed.services.orEmpty()) {
            assertEquals(service.name, 0, service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            assertFalse(service.name, service.permission == "android.permission.BIND_HOTWORD_DETECTION_SERVICE")
        }
        val hotword = Intent("android.service.voice.HotwordDetectionService").setPackage(app.packageName)
        assertTrue(manager.queryIntentServices(hotword, componentFlags).isEmpty())

        val assistant = manager.getServiceInfo(ComponentName(app, OrionVoiceInteractionService::class.java),
            PackageManager.GET_META_DATA)
        checkNotNull(assistant.loadXmlMetaData(manager, VoiceInteractionService.SERVICE_META_DATA)).use { metadata ->
            while (metadata.eventType != XmlPullParser.START_TAG && metadata.eventType != XmlPullParser.END_DOCUMENT) {
                metadata.next()
            }
            assertEquals("voice-interaction-service", metadata.name)
            val android = "http://schemas.android.com/apk/res/android"
            assertFalse(metadata.getAttributeBooleanValue(android, "supportsLaunchVoiceAssistFromKeyguard", false))
            assertFalse(metadata.getAttributeBooleanValue(android, "supportsLocalInteraction", false))
            assertNull(metadata.getAttributeValue(android, "hotwordDetectionService"))
        }

        val reconciliation = "network.orion.app.scheduling.OrionReconciliationReceiver"
        val bootReceivers = mapOf(
            Intent.ACTION_BOOT_COMPLETED to setOf(reconciliation, "androidx.work.impl.background.systemalarm.RescheduleReceiver"),
            Intent.ACTION_LOCKED_BOOT_COMPLETED to emptySet(),
            Intent.ACTION_USER_UNLOCKED to emptySet(),
        )
        for ((action, allowed) in bootReceivers) {
            val receivers = manager.queryBroadcastReceivers(Intent(action).setPackage(app.packageName), componentFlags)
                .map { it.activityInfo.name }.toSet()
            assertTrue("$action reaches unexpected components ${receivers - allowed}", allowed.containsAll(receivers))
            if (action == Intent.ACTION_BOOT_COMPLETED) assertTrue(reconciliation in receivers)
        }
    }
}
