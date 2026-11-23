package network.orion.voice.service

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Debug
import android.os.Process
import java.io.Closeable
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import network.orion.core.time.AndroidOrionClock
import network.orion.core.ipc.OrionHeavyWorkReservation
import network.orion.voice.ipc.IOrionVoiceMemoryCallback
import network.orion.voice.ipc.OrionVoiceProcess
import network.orion.voice.ipc.OrionVoiceProcessInstance
import network.orion.voice.ipc.OrionVoiceResources

/** Passive private receiver. A MAIN query never creates a voice process. */
object OrionVoiceMemoryEndpoint {
    @Volatile private var lease: Closeable? = null

    internal fun acquireHeavy(context: Context): Closeable? =
        lease?.let { OrionHeavyWorkReservation.tryAcquire(context) }

    fun initialize(context: Context, role: OrionVoiceProcess) {
        val app = context.applicationContext
        val process = app.packageName + role.suffix
        require(Application.getProcessName() == process)
        val epoch = SecureRandom().nextLong().let { if (it == 0L) 1L else it }
        val instance = OrionVoiceProcessInstance(Process.myPid(), epoch)
        val acquired = acquireRole(app, role, instance) ?: return
        val executor = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, ArrayBlockingQueue(1))
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val request = intent.getBundleExtra(OrionVoiceResources.MEMORY_REQUEST) ?: return
                if (intent.action != OrionVoiceResources.MEMORY_ACTION ||
                    request.getString(OrionVoiceResources.MEMORY_ROLE) != role.name ||
                    request.getInt(OrionVoiceResources.MEMORY_MAJOR) != OrionVoiceResources.MEMORY_PROTOCOL) return
                val callback = IOrionVoiceMemoryCallback.Stub.asInterface(
                    request.getBinder(OrionVoiceResources.MEMORY_CALLBACK)) ?: return
                val nonce = request.getLong(OrionVoiceResources.MEMORY_NONCE)
                val pending = goAsync()
                try {
                    executor.execute {
                        try {
                            val stamp = AndroidOrionClock(app.contentResolver).monotonicNow()
                            val boot = stamp.bootSessionId ?: return@execute
                            val pss = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong() * 1024
                            if (pss > 0) callback.sample(OrionVoiceResources.MEMORY_PROTOCOL,
                                nonce, process, epoch, boot.bootCount, stamp.elapsedRealtimeMillis, pss)
                        } catch (_: Exception) {
                            // MAIN refuses admission on a missing receipt.
                        } finally {
                            pending.finish()
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    pending.finish()
                }
            }
        }
        val permission = "${app.packageName}.permission.ORION_VOICE_TELEMETRY"
        val filter = IntentFilter(OrionVoiceResources.MEMORY_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, permission, null, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter, permission, null)
            }
            lease = acquired
        } catch (_: RuntimeException) {
            acquired.close()
            executor.shutdownNow()
        }
    }

    private fun acquireRole(app: Context, role: OrionVoiceProcess, instance: OrionVoiceProcessInstance): Closeable? {
        // MAIN's idle probe can briefly own the role file while this process starts.
        repeat(3) {
            OrionVoiceResources.track(app, role, instance)?.let { return it }
            if (it < 2) Thread.sleep(2)
        }
        return null
    }
}
