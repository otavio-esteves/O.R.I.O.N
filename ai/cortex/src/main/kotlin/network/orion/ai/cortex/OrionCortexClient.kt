package network.orion.ai.cortex

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import network.orion.ai.api.*
import network.orion.core.time.MonotonicClock
import network.orion.core.time.MonotonicInstant

/**
 * MAIN-owned optional inference client. Every bind/load/inference consults fresh
 * trusted resource policy. Binder death terminates requests and unbinds; only a new
 * explicit request may rebind. No callback creates a Skill or mutable command.
 */
class OrionCortexClient(
    context: Context,
    private val clock: MonotonicClock,
    private val guard: OrionCortexAdmissionGuard,
    private val onHealth: (OrionCortexHealth) -> Unit = {},
) : AutoCloseable {
    private data class Model(val id: String, val context: Int, val threads: Int)
    private class Pending(val id: UUID, val model: String, val operation: OrionCortexOperation,
        val timeout: Long, val started: MonotonicInstant, val release: () -> Unit,
        val invoke: (OrionCortexRemote, OrionCortexApproval) -> OrionCortexAdmission)
    private val app = context.applicationContext
    private val lock = Any()
    private val callbacksLock = Any()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "orion_cortex_client") }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "orion_cortex_deadlines") }
    private val ledger = OrionCortexStreamLedger()
    private val listeners = mutableMapOf<UUID, (OrionCortexChunk) -> Unit>()
    private val deadlines = mutableMapOf<UUID, Pair<MonotonicInstant, Long>>()
    private val pending = linkedMapOf<UUID, Pending>()
    private val loading = mutableMapOf<UUID, Model>()
    private var loaded: Model? = null
    private var connection: ServiceConnection? = null
    private var remote: OrionCortexRemote? = null
    private var remoteBinder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var epoch = 0L
    private var binding = false
    private var closed = false
    private var deadlineTask: ScheduledFuture<*>? = null
    private var loadedKeepAliveMillis = 0L
    private var idleSince: MonotonicInstant? = null
    @Volatile private var health = OrionCortexHealth.DISCONNECTED
    private val callback = OrionCortexCallbackBinder(::receive)

    init {
        require(Application.getProcessName() == app.packageName) { "Cortex client belongs to MAIN." }
    }

    /** Duplicates the caller's validated read-only registry FD; caller retains ownership of its FD. */
    fun loadModel(modelId: String, descriptor: ParcelFileDescriptor, modelSizeBytes: Long,
        timeoutMillis: Long = 30_000, onChunk: (OrionCortexChunk) -> Unit): UUID? {
        try { OrionCortexBounds.requireModelId(modelId); require(modelSizeBytes in 24..OrionCortexBounds.MAX_MODEL_BYTES) }
        catch (_: IllegalArgumentException) { return null }
        val copy = try { ParcelFileDescriptor.dup(descriptor.fileDescriptor) } catch (_: Exception) { return null }
        return submit(modelId, OrionCortexOperation.LOAD, timeoutMillis, onChunk, { copy.close() }) { id, service, _ ->
            publish(OrionCortexHealth.LOADING)
            val latest = approval(modelId, OrionCortexOperation.LOAD) ?: return@submit OrionCortexAdmission.INVALID
            synchronized(callbacksLock) {
                if (ledger.status(id)?.terminal != false) return@submit OrionCortexAdmission.INVALID
                synchronized(lock) {
                    loading[id] = Model(modelId, latest.contextSize, latest.threads); loaded = null
                    loadedKeepAliveMillis = latest.keepAliveMs
                }
            }
            service.loadModel(id, modelId, copy, modelSizeBytes, timeoutMillis, latest, callback)
        }
    }

    fun startInference(modelId: String, prompt: String, maxTokens: Int, temperature: Float = 0f,
        timeoutMillis: Long = 30_000, priority: Int = OrionCortexPriority.INTERACTIVE.value,
        onChunk: (OrionCortexChunk) -> Unit): UUID? {
        try {
            OrionCortexBounds.requireModelId(modelId)
            require(OrionCortexBounds.validPrompt(prompt) && maxTokens in 1..OrionCortexBounds.MAX_TOKENS &&
                temperature.isFinite() && temperature in 0f..2f && priority in 0..4)
        } catch (_: IllegalArgumentException) { return null }
        return submit(modelId, OrionCortexOperation.INFER, timeoutMillis, onChunk, {}) { id, service, _ ->
            publish(OrionCortexHealth.INFERENCING)
            val latest = approval(modelId, OrionCortexOperation.INFER) ?: return@submit OrionCortexAdmission.INVALID
            val expected = Model(modelId, latest.contextSize, latest.threads)
            if (synchronized(lock) { loaded } != expected || maxTokens > latest.maxTokens) return@submit OrionCortexAdmission.INVALID
            synchronized(lock) { loadedKeepAliveMillis = latest.keepAliveMs; idleSince = null }
            service.startInference(id, modelId, prompt, maxTokens, temperature, timeoutMillis, priority, latest, callback)
        }
    }

    private fun submit(modelId: String, operation: OrionCortexOperation, timeoutMillis: Long,
        listener: (OrionCortexChunk) -> Unit, release: () -> Unit,
        invoke: (UUID, OrionCortexRemote, OrionCortexApproval) -> OrionCortexAdmission): UUID? {
        if (timeoutMillis !in 1..OrionCortexBounds.MAX_TIMEOUT_MILLIS || approval(modelId, operation) == null) {
            release(); publish(OrionCortexHealth.DEGRADED); return null
        }
        val id = UUID.randomUUID(); val start = clock.monotonicNow()
        if (start.bootSessionId == null) { release(); return null }
        val shouldBind: Boolean
        synchronized(lock) {
            if (closed || pending.size >= OrionCortexBounds.MAX_PENDING || !ledger.register(id)) { release(); return null }
            listeners[id] = listener; deadlines[id] = start to timeoutMillis
            ensureWatchdog()
            pending[id] = Pending(id, modelId, operation, timeoutMillis, start, release) { service, permit -> invoke(id, service, permit) }
            shouldBind = remote == null && !binding
            if (shouldBind) binding = true
        }
        if (shouldBind) bind(modelId)
        else synchronized(lock) { remote }?.let { scheduleDrain() }
        return id
    }

    private fun approval(model: String, operation: OrionCortexOperation): OrionCortexApproval? = try {
        guard.approve(model, operation)?.takeIf { it.modelId == model && it.current(clock) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
    catch (_: Exception) { null }

    /** No timer thread/polling exists until an actual qualified request is admitted. */
    private fun ensureWatchdog() {
        if (deadlineTask == null || deadlineTask?.isDone == true)
            deadlineTask = watchdog.scheduleWithFixedDelay({
                try { checkTimeouts() } catch (_: Exception) {
                    disconnect(synchronized(lock) { epoch }, OrionCortexRequestStatus.FAILED)
                }
            }, 50, 50, TimeUnit.MILLISECONDS)
    }

    private fun bind(modelId: String) {
        val currentEpoch = synchronized(lock) { epoch = Math.addExact(epoch, 1); epoch }
        val bindApproval = approval(modelId, OrionCortexOperation.BIND)
        if (bindApproval == null) { disconnect(currentEpoch, OrionCortexRequestStatus.FAILED); return }
        val selected = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                worker.execute {
                    try {
                        val service = OrionCortexRemote(binder); service.getProtocolInfo()
                        val died = IBinder.DeathRecipient { disconnect(currentEpoch, OrionCortexRequestStatus.PROCESS_DIED) }
                        binder.linkToDeath(died, 0)
                        val accepted = synchronized(lock) {
                            if (closed || epoch != currentEpoch || !binder.isBinderAlive) false
                            else { remote = service; remoteBinder = binder; deathRecipient = died; binding = false; true }
                        }
                        if (!accepted) { binder.unlinkToDeath(died, 0); return@execute }
                        publish(OrionCortexHealth.UNLOADED); drain()
                    } catch (_: RemoteException) { disconnect(currentEpoch, OrionCortexRequestStatus.PROCESS_DIED) }
                    catch (_: Exception) { disconnect(currentEpoch, OrionCortexRequestStatus.FAILED) }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) { disconnect(currentEpoch, OrionCortexRequestStatus.PROCESS_DIED) }
            override fun onBindingDied(name: ComponentName) { disconnect(currentEpoch, OrionCortexRequestStatus.PROCESS_DIED) }
            override fun onNullBinding(name: ComponentName) { disconnect(currentEpoch, OrionCortexRequestStatus.FAILED) }
        }
        synchronized(lock) {
            if (closed || epoch != currentEpoch) return
            connection = selected
        }
        publish(OrionCortexHealth.CONNECTING)
        try {
            val resources = approval(modelId, OrionCortexOperation.LOAD)
            if (resources == null || !resources.current(clock) || !bindApproval.current(clock)) {
                disconnect(currentEpoch, OrionCortexRequestStatus.FAILED); return
            }
            val intent = Intent(ACTION_BIND).setComponent(ComponentName(app.packageName, SERVICE_CLASS))
            val bound = synchronized(lock) {
                if (closed || epoch != currentEpoch || connection !== selected || !resources.current(clock) || !bindApproval.current(clock)) return
                app.bindService(intent, selected, Context.BIND_AUTO_CREATE)
            }
            if (!bound) disconnect(currentEpoch, OrionCortexRequestStatus.FAILED)
        } catch (_: Exception) { disconnect(currentEpoch, OrionCortexRequestStatus.FAILED) }
    }
    private fun scheduleDrain() {
        try { worker.execute { drain() } } catch (_: Exception) { failPending() }
    }
    private fun drain() {
        while (true) {
            val service: OrionCortexRemote
            val serviceEpoch: Long
            val request: Pending
            synchronized(lock) {
                service = remote ?: return
                serviceEpoch = epoch
                request = pending.values.firstOrNull() ?: return
                pending.remove(request.id)
            }
            try {
                if (ledger.status(request.id)?.terminal != false) continue
                val current = approval(request.model, request.operation)
                if (current == null) { terminate(request.id, OrionCortexRequestStatus.FAILED); continue }
                val age = clock.monotonicNow().elapsedSince(request.started)?.toMillis()
                if (age == null || age >= request.timeout) { terminate(request.id, OrionCortexRequestStatus.TIMED_OUT); continue }
                if (ledger.status(request.id)?.terminal != false) continue
                val result = request.invoke(service, current)
                if (result == OrionCortexAdmission.ACCEPTED) ledger.markRunning(request.id)
                else terminate(request.id, OrionCortexRequestStatus.FAILED)
            } catch (_: RemoteException) {
                // A dead old transaction can return after DeathRecipient has
                // already admitted a new explicit connection. It owns only its epoch.
                disconnect(serviceEpoch, OrionCortexRequestStatus.PROCESS_DIED)
            } catch (_: CancellationException) {
                terminate(request.id, OrionCortexRequestStatus.CANCELLED)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); terminate(request.id, OrionCortexRequestStatus.CANCELLED)
            } catch (_: Exception) {
                terminate(request.id, OrionCortexRequestStatus.FAILED)
            } finally { try { request.release() } catch (_: Exception) { } }
        }
    }
    private fun receive(chunk: OrionCortexChunk) = synchronized(callbacksLock) {
        ledger.accept(chunk).forEach(::dispatch)
    }
    private fun dispatch(chunk: OrionCortexChunk) {
        var failedConnection: Long? = null
        val listener = synchronized(lock) {
            if (chunk.terminal != null) {
                deadlines.remove(chunk.requestId)
                loading.remove(chunk.requestId)?.let { loaded = it.takeIf { chunk.terminal == OrionCortexRequestStatus.SUCCEEDED } }
                if (chunk.terminal == OrionCortexRequestStatus.FAILED) loaded = null
                if (chunk.terminal == OrionCortexRequestStatus.FAILED && (remote != null || binding)) failedConnection = epoch
            }
            if (chunk.terminal == null) listeners[chunk.requestId] else listeners.remove(chunk.requestId)
        }
        if (failedConnection != null) {
            // The resident model may be invalid after pressure/native failure.
            // Tear down before delivering the terminal; any follow-up must be a
            // new explicit load with a fresh guarded bind, never an automatic retry.
            disconnect(checkNotNull(failedConnection), OrionCortexRequestStatus.FAILED, OrionCortexHealth.DEGRADED)
        } else if (chunk.terminal != null) {
            val idleHealth = synchronized(lock) {
                if (loaded != null && deadlines.isEmpty()) idleSince = clock.monotonicNow()
                if (remote != null && deadlines.isEmpty()) {
                    if (loaded != null) OrionCortexHealth.READY else OrionCortexHealth.UNLOADED
                } else null
            }
            idleHealth?.let(::publish)
        }
        // Publish this request's observations before user code can start a newer request.
        try { listener?.invoke(chunk) } catch (_: Exception) { cancelInference(chunk.requestId) }
    }
    private fun terminate(id: UUID, status: OrionCortexRequestStatus) = synchronized(callbacksLock) {
        ledger.terminate(id, status).forEach(::dispatch)
    }
    private fun checkTimeouts() {
        val now = clock.monotonicNow()
        val timedOut = synchronized(lock) { deadlines.filterValues { (start, duration) ->
            now.elapsedSince(start)?.toMillis()?.let { it >= duration } != false
        }.keys.toList() }
        for (id in timedOut) {
            terminate(id, OrionCortexRequestStatus.TIMED_OUT)
            synchronized(lock) { pending.remove(id) }?.let { try { it.release() } catch (_: Exception) { } }
            try { synchronized(lock) { remote }?.cancelInference(id) } catch (_: Exception) { }
        }
        val noOutstandingBinding = synchronized(lock) { binding && deadlines.isEmpty() }
        if (noOutstandingBinding) disconnect(synchronized(lock) { epoch }, OrionCortexRequestStatus.TIMED_OUT)
        val unloadIdle = synchronized(lock) {
            val idle = idleSince
            loaded != null && deadlines.isEmpty() && idle != null &&
                now.elapsedSince(idle)?.toMillis()?.let { it >= loadedKeepAliveMillis } != false
        }
        if (unloadIdle) unloadModel()
        synchronized(lock) {
            if (deadlines.isEmpty() && loaded == null) { deadlineTask?.cancel(false); deadlineTask = null }
        }
    }
    fun cancelInference(id: UUID) {
        terminate(id, OrionCortexRequestStatus.CANCELLED)
        synchronized(lock) { pending.remove(id) }?.let { try { it.release() } catch (_: Exception) { } }
        try { synchronized(lock) { remote }?.cancelInference(id) } catch (_: Exception) { }
    }
    fun getRequestStatus(id: UUID) = ledger.status(id)
    /** Blocking read; MAIN composition invokes this diagnostic on its IO dispatcher. */
    fun getStatus(): OrionCortexHealth {
        val selected = synchronized(lock) { remote?.let { it to epoch } } ?: return health
        try {
            val observed = selected.first.getStatus()
            if (synchronized(lock) { epoch != selected.second }) return health
            if (observed == OrionCortexHealth.DEGRADED || observed == OrionCortexHealth.UNAVAILABLE)
                disconnect(selected.second, OrionCortexRequestStatus.FAILED, OrionCortexHealth.DEGRADED)
            else {
                if (observed == OrionCortexHealth.UNLOADED) synchronized(lock) { loaded = null; idleSince = null }
                publish(observed)
            }
        } catch (_: RemoteException) { disconnect(selected.second, OrionCortexRequestStatus.PROCESS_DIED) }
        catch (_: Exception) { disconnect(selected.second, OrionCortexRequestStatus.FAILED, OrionCortexHealth.DEGRADED) }
        return health
    }
    fun healthCheck(): Boolean = try { synchronized(lock) { remote }?.healthCheck() == true } catch (_: Exception) { false }
    fun unloadModel(): Boolean = try {
        val result = synchronized(lock) { remote }?.unloadModel() == true
        if (result) { synchronized(lock) { loaded = null; idleSince = null }; getStatus() }
        result
    } catch (_: Exception) { false }
    private fun failPending() {
        val requests = synchronized(lock) { pending.values.toList().also { pending.clear() } }
        for (request in requests) { terminate(request.id, OrionCortexRequestStatus.FAILED); try { request.release() } catch (_: Exception) { } }
    }
    private fun disconnect(expectedEpoch: Long, status: OrionCortexRequestStatus,
        observedHealth: OrionCortexHealth = OrionCortexHealth.UNAVAILABLE) {
        val oldConnection: ServiceConnection?
        val requests: List<Pending>
        synchronized(lock) {
            if (expectedEpoch != epoch) return
            epoch = Math.addExact(epoch, 1)
            try { deathRecipient?.let { remoteBinder?.unlinkToDeath(it, 0) } } catch (_: Exception) { }
            remote = null; remoteBinder = null; deathRecipient = null; loaded = null; loading.clear(); binding = false
            idleSince = null; deadlineTask?.cancel(false); deadlineTask = null
            oldConnection = connection; connection = null
            requests = pending.values.toList(); pending.clear()
        }
        try { oldConnection?.let(app::unbindService) } catch (_: Exception) { }
        requests.forEach { try { it.release() } catch (_: Exception) { } }
        publish(observedHealth)
        synchronized(callbacksLock) { ledger.terminateAll(status).forEach(::dispatch) }
    }
    private fun publish(value: OrionCortexHealth) {
        health = value
        try { onHealth(value) } catch (_: Exception) { }
    }
    override fun close() {
        val selected = synchronized(lock) { if (closed) return; closed = true; epoch }
        disconnect(selected, OrionCortexRequestStatus.CANCELLED)
        watchdog.shutdownNow(); worker.shutdown()
    }
    companion object {
        private const val SERVICE_CLASS = "network.orion.ai.service.OrionCortexService"
        private const val ACTION_BIND = "network.orion.action.BIND_CORTEX_V1"
    }
}
