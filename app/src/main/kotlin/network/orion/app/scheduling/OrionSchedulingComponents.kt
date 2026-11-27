package network.orion.app.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.await
import java.util.UUID
import kotlinx.coroutines.*
import network.orion.app.OrionApplication
import network.orion.app.OrionFoundationRuntime
import network.orion.scheduler.alarms.AndroidOrionAlarmBackend
import network.orion.core.observability.OrionMaintenanceOrigin
import network.orion.scheduler.workers.OrionMaintenanceScheduler
import java.util.concurrent.TimeUnit

/** Framework callback carries references only; the Core persists/validates them before any delivery. */
class OrionReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidOrionAlarmBackend.ACTION_REMINDER_DUE) return
        val raw = intent.getStringExtra(AndroidOrionAlarmBackend.EXTRA_REMINDER_ID) ?: return
        val id = try { UUID.fromString(raw) }
            catch (_: IllegalArgumentException) { return }
        val generation = intent.getLongExtra(AndroidOrionAlarmBackend.EXTRA_GENERATION, -1)
        if (generation <= 0 || intent.dataString != "orion://reminder/$id/$generation") return
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        val runtime = (context.applicationContext as? OrionApplication)?.mainRuntime ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(8_000) { runtime.receiveReminderAlarm(id, generation) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Room state is authoritative; maintenance will reconcile. */ }
            finally { pending.finish() }
        }
    }
}

/** Android delivers BOOT_COMPLETED after the user has unlocked credential storage. */
class OrionReconciliationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS || context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        val runtime = (context.applicationContext as? OrionApplication)?.mainRuntime ?: return
        val startupEvent = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    if (startupEvent) persistStartupReconciliation(context, runtime)
                    runtime.requestReconciliation(rebuildAlarmArtifacts = true)
                }
            } catch (_: TimeoutCancellationException) {
                // The durable worker or Core startup can finish after the broadcast returns.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                runtime.recordMaintenanceFailure()
            }
            finally { pending.finish() }
        }
    }

    private suspend fun persistStartupReconciliation(context: Context, runtime: OrionFoundationRuntime) {
        // Commit a durable trigger before waiting for Room recovery. The worker enters through the same barrier.
        try {
            val scheduled = withTimeoutOrNull(2_000) {
                OrionMaintenanceScheduler(context, OrionMaintenanceWorker::class.java).requestReconciliation().await()
            }
            if (scheduled == null) runtime.recordMaintenanceFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            runtime.recordMaintenanceFailure()
        }
    }

    companion object {
        private val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
    }
}

/** Deferrable maintenance only. WorkManager retries this internal reconciliation, never an external UNKNOWN. */
class OrionMaintenanceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val runtime = (applicationContext as? OrionApplication)?.mainRuntime ?: return Result.failure()
        return try {
            val origin = withContext(Dispatchers.IO) {
                try {
                    val work = androidx.work.WorkManager.getInstance(applicationContext)
                    val heartbeat = work.getWorkInfosForUniqueWork(OrionMaintenanceScheduler.HEARTBEAT_NAME).get(3, TimeUnit.SECONDS)
                    val reconciliation = work.getWorkInfosForUniqueWork(OrionMaintenanceScheduler.RECONCILE_NAME).get(3, TimeUnit.SECONDS)
                    OrionMaintenanceOrigin.forWorker(id, heartbeat.map { it.id }.toSet(), reconciliation.map { it.id }.toSet())
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
                catch (_: Exception) { OrionMaintenanceOrigin.WORKER_UNCLASSIFIED }
            }
            val healthy = runtime.performMaintenance(origin)
            when {
                healthy -> Result.success()
                runtime.maintenanceNeedsContinuation -> Result.retry() // A persisted cursor advances; this is not retrying an external effect.
                else -> Result.failure() // Stable degradation is not success; heartbeat/capability events may reevaluate.
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { runtime.recordMaintenanceFailure(); if (runAttemptCount < 2) Result.retry() else Result.failure() }
    }
}
