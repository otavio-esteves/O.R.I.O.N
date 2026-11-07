package network.orion.scheduler.alarms

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.concurrent.CancellationException
import network.orion.core.common.process.OrionProcessRole

/** Android adapter has no database, user content, notification or Core authority. */
class AndroidOrionAlarmBackend(context: Context, private val receiver: ComponentName) : OrionAlarmBackend {
    private val app = context.applicationContext

    init {
        require(OrionProcessRole.fromProcessName(app.packageName, Application.getProcessName()) == OrionProcessRole.MAIN)
        require(receiver.packageName == app.packageName) { "Alarm receiver must belong to O.R.I.O.N." }
    }

    private fun manager(): AlarmManager = checkNotNull(app.getSystemService(AlarmManager::class.java))

    override fun availability(): ExactAlarmAvailability = try {
        val service = app.getSystemService(AlarmManager::class.java)
        when {
            service == null -> ExactAlarmAvailability.UNAVAILABLE
            service.canScheduleExactAlarms() -> ExactAlarmAvailability.AVAILABLE
            else -> ExactAlarmAvailability.DENIED
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        ExactAlarmAvailability.UNAVAILABLE
    }

    override fun exact(token: OrionAlarmToken, triggerAtEpochMillis: Long) {
        val operation = checkNotNull(operation(token, PendingIntent.FLAG_UPDATE_CURRENT))
        val service = manager()
        // Prepare the PendingIntent before resampling: capability is fresh at invocation.
        if (!service.canScheduleExactAlarms()) throw SecurityException("Exact alarm capability denied")
        service.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMillis, operation)
    }

    override fun approximate(token: OrionAlarmToken, triggerAtEpochMillis: Long) {
        manager().setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMillis,
            checkNotNull(operation(token, PendingIntent.FLAG_UPDATE_CURRENT)))
    }

    override fun cancel(token: OrionAlarmToken) {
        val operation = operation(token, PendingIntent.FLAG_NO_CREATE) ?: return
        manager().cancel(operation)
        operation.cancel()
    }

    private fun operation(token: OrionAlarmToken, flags: Int): PendingIntent? {
        val intent = Intent(ACTION_REMINDER_DUE).setComponent(receiver)
            .setData(Uri.parse(token.identity))
            .putExtra(EXTRA_REMINDER_ID, token.reminderId.toString())
            .putExtra(EXTRA_GENERATION, token.generation)
        return PendingIntent.getBroadcast(app, token.requestCode, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val ACTION_REMINDER_DUE = "network.orion.action.REMINDER_DUE"
        const val EXTRA_REMINDER_ID = "orion_reminder_id"
        const val EXTRA_GENERATION = "orion_fire_generation"
    }
}
