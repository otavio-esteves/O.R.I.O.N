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
import network.orion.core.time.MonotonicClock

/** Trusted MAIN platform boundary. IDs or a direct instance are never execution authority. */
/** One trusted attempt's exact revalidation, never producer data or a reusable authorization flag. */
internal fun interface OrionNotificationInvocationPermit { fun allows():Boolean }

internal interface OrionNotificationPoster {
    fun availability():NotificationAvailability
    fun permissionGranted():Boolean
    fun post(actionId:UUID,notification:ValidatedPostNotification,permit:OrionNotificationInvocationPermit):NotificationPostResult
}

internal class OrionAndroidNotificationPoster(context:Context,private val clock:MonotonicClock,
    private val channelId:String=USER_CHANNEL):OrionNotificationPoster {
    init { require(channelId in setOf(USER_CHANNEL, REMINDER_CHANNEL)) }
    private val app=context.applicationContext
    private val manager=app.getSystemService(NotificationManager::class.java)
    /** MAIN calls this deliberately. Existing user channel choices are preserved by Android. */
    fun initializeChannels() {
        manager?.createNotificationChannels(listOf(
            NotificationChannel(REMINDER_CHANNEL,"Lembretes",NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility=Notification.VISIBILITY_PRIVATE
            },
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
            manager.getNotificationChannel(channelId)==null->NotificationAvailability.UNAVAILABLE
            manager.getNotificationChannel(channelId).importance==NotificationManager.IMPORTANCE_NONE->NotificationAvailability.CHANNEL_DISABLED
            else->NotificationAvailability.AVAILABLE
        }
    } catch(_:Exception) { NotificationAvailability.UNAVAILABLE }
    override fun post(actionId:UUID,notification:ValidatedPostNotification,permit:OrionNotificationInvocationPermit):NotificationPostResult {
        // Distinguish proven pre-invocation denial from any ambiguous outcome after invoke.
        if(availability()!=NotificationAvailability.AVAILABLE)return NotificationPostResult.NOT_INVOKED
        val service=manager?:return NotificationPostResult.NOT_INVOKED
        val public=Notification.Builder(app,channelId).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("O.R.I.O.N.").setContentText(if(channelId==REMINDER_CHANNEL) "Lembrete" else "Notificação solicitada").build()
        val platform=Notification.Builder(app,channelId).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notification.title).setContentText(notification.body)
            .setStyle(Notification.BigTextStyle().bigText(notification.body)).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public).setLocalOnly(true).setOnlyAlertOnce(true).setAllowSystemGeneratedContextualActions(false).build()
        if(availability()!=NotificationAvailability.AVAILABLE)return NotificationPostResult.NOT_INVOKED
        val allowed=try { permit.allows() }
            catch(cancelled:java.util.concurrent.CancellationException) { throw cancelled }
            catch(interrupted:InterruptedException) { Thread.currentThread().interrupt();throw interrupted }
            catch(_:Exception) { return NotificationPostResult.NOT_INVOKED }
        if(!allowed)return NotificationPostResult.NOT_INVOKED
        return try {
            service.notify(actionId.toString(),0,platform)
            // Android may enqueue notification registration asynchronously. A bounded
            // observation window proves a matching record; it never repeats notify.
            val start=clock.monotonicNow()
            for(attempt in 0 until 10) {
                val age=clock.monotonicNow().elapsedSince(start)?:return NotificationPostResult.UNKNOWN
                if(age.toMillis()>500)return NotificationPostResult.UNKNOWN
                val active=service.activeNotifications.singleOrNull { it.tag==actionId.toString()&&it.id==0 }
                val posted=active?.notification
                if(posted?.channelId==channelId&&posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()==notification.title&&
                    posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()==notification.body)return NotificationPostResult.POSTED
                if(attempt<9)Thread.sleep(25)
            }
            NotificationPostResult.UNKNOWN
        } catch(cancelled:java.util.concurrent.CancellationException) { throw cancelled }
        catch(interrupted:InterruptedException) {
            Thread.currentThread().interrupt();throw interrupted
        } catch(_:Exception) { NotificationPostResult.UNKNOWN }
    }

    companion object { const val USER_CHANNEL="orion_user_notifications"; const val REMINDER_CHANNEL="orion_reminders" }
}
