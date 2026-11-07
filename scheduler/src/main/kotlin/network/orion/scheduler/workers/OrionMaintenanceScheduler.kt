package network.orion.scheduler.workers

import android.app.Application
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import network.orion.core.common.process.OrionProcessRole

/**
 * Deferrable maintenance only, never the authority for exact reminder delivery.
 * MAIN must initialize WorkManager explicitly and remove its automatic initializer
 * before constructing this adapter. Worker execution re-enters Core recovery/ingress.
 */
class OrionMaintenanceScheduler(context: Context, private val worker: Class<out ListenableWorker>) {
    private val app = context.applicationContext

    init {
        require(OrionProcessRole.fromProcessName(app.packageName, Application.getProcessName()) == OrionProcessRole.MAIN)
    }

    fun reconcileHeartbeat(): Operation {
        val request = PeriodicWorkRequest.Builder(worker, HEARTBEAT_MINUTES, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        return WorkManager.getInstance(app).enqueueUniquePeriodicWork(HEARTBEAT_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun requestReconciliation(): Operation {
        val request = OneTimeWorkRequest.Builder(worker)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        return WorkManager.getInstance(app).enqueueUniqueWork(RECONCILE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val HEARTBEAT_NAME = "orion_heartbeat"
        const val RECONCILE_NAME = "orion_reminder_reconcile"
        const val HEARTBEAT_MINUTES = 30L
    }
}
