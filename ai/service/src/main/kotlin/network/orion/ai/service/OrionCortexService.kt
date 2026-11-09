package network.orion.ai.service

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.ComponentCallbacks2
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import network.orion.ai.api.*
import network.orion.ai.cortex.OrionCortexRuntime
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.time.AndroidOrionClock

/** Unexported same-UID inference endpoint. Application bootstrap in :ai must remain storage-free. */
class OrionCortexService : Service() {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "orion_cortex_worker") }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "orion_cortex_watchdog") }
    private lateinit var runtime: OrionCortexRuntime
    private var watchdogTask: ScheduledFuture<*>? = null
    private val watchdogLifecycle = OrionCortexWatchdogLifecycle()

    override fun onCreate() {
        super.onCreate()
        check(OrionProcessRole.fromProcessName(packageName, Application.getProcessName()) == OrionProcessRole.AI)
        runtime = OrionCortexRuntime(OrionInferenceEngineFactory {
            checkNotNull(application as? OrionCortexEngineProvider).orionInferenceEngineFactory.create()
        }, AndroidOrionClock(contentResolver), worker, { Process.killProcess(Process.myPid()) })
    }

    override fun onBind(intent: Intent): IBinder? = endpoint.takeIf { intent.action == ACTION_BIND }

    private val endpoint = object : Binder() {
        init { attachInterface(null, OrionCortexWire.DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(OrionCortexWire.DESCRIPTOR); return true }
            if (reply == null || flags and IBinder.FLAG_ONEWAY != 0) return false
            return try {
                OrionCortexWire.checkFrame(data)
                when (code) {
                    OrionCortexWire.PROTOCOL -> {
                        OrionCortexWire.exhausted(data); reply.writeNoException(); reply.writeInt(1); reply.writeInt(0)
                    }
                    OrionCortexWire.LOAD -> load(data, reply)
                    OrionCortexWire.INFER -> infer(data, reply)
                    OrionCortexWire.CANCEL -> {
                        val id = OrionCortexWire.requestId(data); OrionCortexWire.exhausted(data)
                        runtime.cancelInference(id); reply.writeNoException(); reply.writeInt(1)
                    }
                    OrionCortexWire.REQUEST_STATUS -> {
                        val id = OrionCortexWire.requestId(data); OrionCortexWire.exhausted(data)
                        val status = runtime.getRequestStatus(id); reply.writeNoException(); reply.writeString(status?.name)
                    }
                    OrionCortexWire.STATUS -> {
                        OrionCortexWire.exhausted(data); val status = runtime.getStatus()
                        reply.writeNoException(); reply.writeString(status.name)
                    }
                    OrionCortexWire.UNLOAD -> {
                        OrionCortexWire.exhausted(data); val unloaded = runtime.unloadModel()
                        if (unloaded) armWatchdog()
                        reply.writeNoException(); reply.writeInt(if (unloaded) 1 else 0)
                    }
                    OrionCortexWire.HEALTH -> {
                        OrionCortexWire.exhausted(data); reply.writeNoException()
                        reply.writeInt(if (runtime.getStatus() != OrionCortexHealth.UNAVAILABLE) 1 else 0)
                    }
                    else -> return false
                }
                true
            } catch (_: Exception) {
                // Never serialize platform/native exception text, prompt or model metadata into errors.
                reply.setDataSize(0); reply.setDataPosition(0)
                reply.writeException(IllegalArgumentException("Invalid or unavailable Cortex request.")); true
            }
        }
    }

    private fun load(data: Parcel, reply: Parcel) {
        val id = OrionCortexWire.requestId(data)
        val modelId = requireNotNull(data.readString())
        val descriptor = requireNotNull(data.readTypedObject(ParcelFileDescriptor.CREATOR))
        var handedOff = false
        try {
            val size = data.readLong(); val timeout = data.readLong()
            val approval = OrionCortexWire.readApproval(data, modelId)
            val callback = requireNotNull(data.readStrongBinder())
            OrionCortexWire.exhausted(data)
            OrionCortexBounds.requireModelId(modelId)
            val stat = Os.fstat(descriptor.fileDescriptor)
            val mode = Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_GETFL, 0)
            require(OsConstants.S_ISREG(stat.st_mode) && mode and OsConstants.O_ACCMODE == OsConstants.O_RDONLY)
            require(size in 24..OrionCortexBounds.MAX_MODEL_BYTES && stat.st_size == size)
            val unlink = trackCallback(id, callback)
            val result = runtime.loadModel(id, modelId, descriptor.fileDescriptor, approval.contextSize, approval.threads, timeout,
                { OrionCortexWire.chunk(callback, it) }, { try { descriptor.close() } finally { unlink() } }, approval.keepAliveMs, approval)
            if (result == OrionCortexAdmission.ACCEPTED) armWatchdog()
            handedOff = true // Runtime owns cleanup, including refusals and cancellation before native load.
            reply.writeNoException(); reply.writeString(result.name)
        } finally { if (!handedOff) descriptor.close() }
    }
    private fun infer(data: Parcel, reply: Parcel) {
        val id = OrionCortexWire.requestId(data); val model = requireNotNull(data.readString())
        val prompt = requireNotNull(data.readString()); val tokens = data.readInt()
        val temperature = data.readFloat(); val timeout = data.readLong(); val priority = data.readInt()
        val approval = OrionCortexWire.readApproval(data, model)
        val callback = requireNotNull(data.readStrongBinder())
        OrionCortexWire.exhausted(data)
        val unlink = trackCallback(id, callback)
        val result = runtime.startInference(id, model, prompt, approval.contextSize, approval.threads, tokens, temperature, timeout,
            priority, { OrionCortexWire.chunk(callback, it) }, unlink, approval.keepAliveMs, approval)
        if (result == OrionCortexAdmission.ACCEPTED) armWatchdog()
        reply.writeNoException(); reply.writeString(result.name)
    }
    private fun trackCallback(id: UUID, callback: IBinder): () -> Unit {
        val died = IBinder.DeathRecipient { runtime.cancelInference(id) }
        callback.linkToDeath(died, 0)
        return { try { callback.unlinkToDeath(died, 0) } catch (_: Exception) { } }
    }
    @Synchronized private fun armWatchdog() {
        if (watchdogTask == null || watchdogTask?.isDone == true)
            watchdogTask = watchdog.scheduleWithFixedDelay({
                try { runtime.checkTimeouts() } catch (_: Exception) { Process.killProcess(Process.myPid()) }
                synchronized(this) {
                    when (watchdogLifecycle.disposition(runtime.needsWatchdog(), worker.isTerminated)) {
                        OrionCortexWatchdogDisposition.MONITOR -> Unit
                        OrionCortexWatchdogDisposition.IDLE -> { watchdogTask?.cancel(false); watchdogTask = null }
                        OrionCortexWatchdogDisposition.SHUTDOWN -> {
                            watchdogTask?.cancel(false); watchdogTask = null; watchdog.shutdown()
                        }
                    }
                }
            }, 50, 50, TimeUnit.MILLISECONDS)
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level in setOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
                ComponentCallbacks2.TRIM_MEMORY_MODERATE, ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
            forwardMemoryPressure()
    }
    override fun onLowMemory() {
        super.onLowMemory()
        forwardMemoryPressure()
    }
    private fun forwardMemoryPressure() {
        if (!::runtime.isInitialized) return
        runtime.onMemoryPressure()
        if (runtime.needsWatchdog()) armWatchdog()
    }
    override fun onDestroy() {
        runtime.close()
        watchdogLifecycle.beginDestruction()
        armWatchdog()
        worker.shutdown()
        // Native close has its own cancellation grace after inference returns.
        // Monitor through that transition, including any gap before its worker task starts.
        super.onDestroy()
    }
    companion object { const val ACTION_BIND = "network.orion.action.BIND_CORTEX_V1" }
}
