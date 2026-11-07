package network.orion.app.scheduling

import android.app.NotificationManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import network.orion.app.OrionApplication
import network.orion.app.notifications.OrionAndroidNotificationPoster
import network.orion.app.notifications.OrionNotificationInvocationPermit
import network.orion.core.commands.notifications.PostNotification
import network.orion.core.commands.notifications.PostNotificationPreparation
import network.orion.core.commands.notifications.OrionPostNotificationPreparer
import network.orion.core.orchestrator.notifications.NotificationAvailability
import network.orion.core.time.AndroidOrionClock
import network.orion.skills.notifications.NotificationPostResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Host changes the existing channel through official Settings UI, then restores its original choice. */
@RunWith(AndroidJUnit4::class)
class OrionF4ChannelQualificationTest {
    @Test fun channelStateReflectsTheActualAndroidUserSetting() {
        val mode = InstrumentationRegistry.getArguments().getString("orion_channel_mode") ?: return
        require(mode in setOf("disabled", "restored"))
        val app: OrionApplication = ApplicationProvider.getApplicationContext()
        val manager = app.getSystemService(NotificationManager::class.java)
        val poster = OrionAndroidNotificationPoster(app, AndroidOrionClock(app.contentResolver),
            OrionAndroidNotificationPoster.REMINDER_CHANNEL)
        assertTrue(poster.permissionGranted())
        assertTrue(manager.areNotificationsEnabled())
        val channel = checkNotNull(manager.getNotificationChannel(OrionAndroidNotificationPoster.REMINDER_CHANNEL))
        if (mode == "disabled") {
            assertEquals(NotificationManager.IMPORTANCE_NONE, channel.importance)
            assertEquals(NotificationAvailability.CHANNEL_DISABLED, poster.availability())
            val id = UUID.randomUUID()
            val prepared = OrionPostNotificationPreparer.prepare(PostNotification(id,
                "O.R.I.O.N. channel qualification", "Synthetic blocked invocation", id, id)) as PostNotificationPreparation.Ready
            var invocationChecks = 0
            assertEquals(NotificationPostResult.NOT_INVOKED, poster.post(id, prepared.notification,
                OrionNotificationInvocationPermit { invocationChecks++; false }))
            assertEquals(0, invocationChecks)
            assertFalse(manager.activeNotifications.any { it.tag == id.toString() })
        } else {
            assertTrue(channel.importance > NotificationManager.IMPORTANCE_NONE)
            assertEquals(NotificationAvailability.AVAILABLE, poster.availability())
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("orion_channel_importance", channel.importance.toString())
        })
    }
}
