package network.orion.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.UUID
import network.orion.core.commands.notifications.ValidatedPostNotification
import network.orion.core.orchestrator.notifications.NotificationAvailability
import network.orion.skills.notifications.NotificationPostResult

/** Trusted MAIN platform boundary. IDs or a direct instance are never execution authority. */
internal interface OrionNotificationPoster {
    fun availability():NotificationAvailability
    fun permissionGranted():Boolean
    fun post(actionId:UUID,notification:ValidatedPostNotification):NotificationPostResult
}

internal class OrionAndroidNotificationPoster(context:Context):OrionNotificationPoster {
    private val app=context.applicationContext
    private val manager=app.getSystemService(NotificationManager::class.java)
    /** MAIN calls this deliberately. Existing user channel choices are preserved by Android. */
    fun initializeChannels() {
        manager?.createNotificationChannels(listOf(
            NotificationChannel("orion_reminders","Lembretes",NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("orion_proactive","Sugestões",NotificationManager.IMPORTANCE_LOW),
            NotificationChannel("orion_voice_service","Voz",NotificationManager.IMPORTANCE_LOW),
            NotificationChannel("orion_system_status","Estado do sistema",NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(USER_CHANNEL,"Notificações solicitadas",NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility=Notification.VISIBILITY_PRIVATE
            },
        ))
    }
    override fun permissionGranted()=Build.VERSION.SDK_INT<33||app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
    override fun availability():NotificationAvailability=try {
        when {
            !permissionGranted()->NotificationAvailability.PERMISSION_DENIED
            manager==null->NotificationAvailability.UNAVAILABLE
            !manager.areNotificationsEnabled()->NotificationAvailability.APP_DISABLED
            manager.getNotificationChannel(USER_CHANNEL)==null->NotificationAvailability.UNAVAILABLE
            manager.getNotificationChannel(USER_CHANNEL).importance==NotificationManager.IMPORTANCE_NONE->NotificationAvailability.CHANNEL_DISABLED
            else->NotificationAvailability.AVAILABLE
        }
    } catch(_:Exception) { NotificationAvailability.UNAVAILABLE }
    override fun post(actionId:UUID,notification:ValidatedPostNotification):NotificationPostResult {
        // Distinguish proven pre-invocation denial from any ambiguous outcome after invoke.
        if(availability()!=NotificationAvailability.AVAILABLE)return NotificationPostResult.NOT_INVOKED
        val service=manager?:return NotificationPostResult.NOT_INVOKED
        val public=Notification.Builder(app,USER_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("O.R.I.O.N.").setContentText("Notificação solicitada").build()
        val platform=Notification.Builder(app,USER_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notification.title).setContentText(notification.body)
            .setStyle(Notification.BigTextStyle().bigText(notification.body)).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public).setLocalOnly(true).setOnlyAlertOnce(true).setAllowSystemGeneratedContextualActions(false).build()
        return try {
            service.notify(actionId.toString(),0,platform)
            val active=service.activeNotifications.singleOrNull { it.tag==actionId.toString()&&it.id==0 }
            val posted=active?.notification
            if(posted?.channelId==USER_CHANNEL&&posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()==notification.title&&
                posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()==notification.body)NotificationPostResult.POSTED
            else NotificationPostResult.UNKNOWN
        } catch(_:Exception) { NotificationPostResult.UNKNOWN }
    }
    companion object { const val USER_CHANNEL="orion_user_notifications" }
}
