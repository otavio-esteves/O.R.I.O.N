package network.orion.ai.cortex

import java.io.FileDescriptor
import java.util.UUID
import java.util.concurrent.Executor
import network.orion.ai.api.*
import network.orion.core.time.MonotonicClock
import network.orion.core.time.MonotonicInstant

/** AI-owned mechanics. No repositories, Skills, policy authority, logs or persistence. */
class OrionCortexRuntime(
    private val engines: OrionInferenceEngineFactory,
    private val clock: MonotonicClock,
    private val worker: Executor,
    private val hardStop: () -> Unit,
) : AutoCloseable {
    private class Request(val id: UUID, val modelId: String, val deadline: MonotonicInstant,
        val timeoutMillis: Long, callback: (OrionCortexChunk) -> Unit, val priority: Int,
        run: (Request) -> Unit, cleanup: () -> Unit) {
        var callback: ((OrionCortexChunk) -> Unit)? = callback
        var run: ((Request) -> Unit)? = run
        var cleanup: (() -> Unit)? = cleanup
        @Volatile var status = OrionCortexRequestStatus.QUEUED
        @Volatile var cancelAt: MonotonicInstant? = null
        var sequence = 0L
        var outputChars = 0
        var completed = false
        var unloading = false
    }
    private val lock = Any()
    private val requests = linkedMapOf<UUID, Request>()
    private val cancelledBeforeStart = linkedSetOf<UUID>()
    private val queue = mutableListOf<Request>()
    private var active: Request? = null
    private var engine: OrionInferenceEngine? = null
    private var loadedModel: String? = null
    private var loadedContext = 0
    private var loadedThreads = 0
    private var closed = false
    private var hardStopped = false
    private var idleSince: MonotonicInstant? = null
    private var keepAliveMillis = 0L
    private var pressureFenced = false
    private var pressureCleanupNeeded = false

    fun loadModel(id: UUID, modelId: String, model: FileDescriptor, contextSize: Int, threads: Int,
        timeoutMillis: Long, callback: (OrionCortexChunk) -> Unit, release: () -> Unit = {},
        keepAliveMillis: Long = 30_000, approval: OrionCortexApproval): OrionCortexAdmission {
        try {
            OrionCortexBounds.requireModelId(modelId)
            require(contextSize in 32..OrionCortexBounds.MAX_CONTEXT && threads in 1..OrionCortexBounds.MAX_THREADS)
            require(keepAliveMillis in 0..60_000)
            require(approval.modelId == modelId && approval.contextSize == contextSize && approval.threads == threads &&
                approval.keepAliveMs == keepAliveMillis)
        } catch (_: IllegalArgumentException) { release(); return OrionCortexAdmission.INVALID }
        return submit(id, modelId, timeoutMillis, OrionCortexPriority.INTERACTIVE.value, callback, release) { request ->
            if (cancelled(request)) return@submit
            check(approval.current(clock))
            val runtime = engine ?: engines.create().also { engine = it }
            runtime.unload()
            synchronized(lock) { loadedModel = null }
            check(approval.current(clock))
            runtime.load(model, contextSize, threads) { cancelled(request) }
            if (cancelled(request)) { runtime.unload(); return@submit }
            synchronized(lock) {
                loadedModel = modelId; loadedContext = contextSize; loadedThreads = threads
                this.keepAliveMillis = keepAliveMillis
            }
        }
    }

    fun startInference(id: UUID, modelId: String, prompt: String, contextSize: Int, threads: Int,
        maxTokens: Int, temperature: Float, timeoutMillis: Long, priority: Int,
        callback: (OrionCortexChunk) -> Unit, release: () -> Unit = {}, keepAliveMillis: Long = 30_000,
        approval: OrionCortexApproval): OrionCortexAdmission {
        try {
            OrionCortexBounds.requireModelId(modelId)
            require(OrionCortexBounds.validPrompt(prompt) && maxTokens in 1..OrionCortexBounds.MAX_TOKENS &&
                temperature.isFinite() && temperature in 0f..2f && priority in 0..4)
            require(keepAliveMillis in 0..60_000)
            require(approval.modelId == modelId && approval.contextSize == contextSize && approval.threads == threads &&
                maxTokens <= approval.maxTokens && keepAliveMillis == approval.keepAliveMs)
        } catch (_: IllegalArgumentException) { release(); return OrionCortexAdmission.INVALID }
        return submit(id, modelId, timeoutMillis, priority, callback, release) { request ->
            val runtime = synchronized(lock) {
                check(loadedModel == modelId && loadedContext == contextSize && loadedThreads == threads)
                this.keepAliveMillis = keepAliveMillis
                checkNotNull(engine)
            }
            check(approval.current(clock))
            runtime.infer(prompt, maxTokens, temperature, { text -> emit(request, text) }, { cancelled(request) })
        }
    }

    private fun submit(id: UUID, modelId: String, timeoutMillis: Long, priority: Int,
        callback: (OrionCortexChunk) -> Unit, reject: () -> Unit, action: (Request) -> Unit): OrionCortexAdmission {
        if (timeoutMillis !in 1..OrionCortexBounds.MAX_TIMEOUT_MILLIS) { reject(); return OrionCortexAdmission.INVALID }
        val sampled = try { clock.monotonicNow() } catch (_: Exception) {
            reject(); return OrionCortexAdmission.UNAVAILABLE
        }
        if (sampled.bootSessionId == null || sampled.elapsedRealtimeMillis > Long.MAX_VALUE - timeoutMillis) {
            reject(); return OrionCortexAdmission.UNAVAILABLE
        }
        synchronized(lock) {
            if (closed || pressureFenced) { reject(); return OrionCortexAdmission.UNAVAILABLE }
            if (id in requests || id in cancelledBeforeStart) { reject(); return OrionCortexAdmission.DUPLICATE_REQUEST }
            if (requests.size + cancelledBeforeStart.size >= OrionCortexBounds.MAX_REQUESTS || queue.size >= OrionCortexBounds.MAX_PENDING) {
                reject(); return OrionCortexAdmission.BUSY
            }
            val request = Request(id, modelId, sampled, timeoutMillis, callback, priority, action, reject)
            requests[id] = request; queue += request
            if (active == null) scheduleNext()
        }
        return OrionCortexAdmission.ACCEPTED
    }

    private fun scheduleNext() {
        if (closed || active != null) return
        val request = queue.maxByOrNull { it.priority } ?: return
        queue.remove(request); active = request
        try { worker.execute { run(request) } }
        catch (_: Exception) {
            active = null; request.completed = true
            try { request.cleanup?.invoke() } catch (_: Exception) { }
            request.run = null; request.cleanup = null
            finish(request, OrionCortexRequestStatus.FAILED); request.callback = null; scheduleNext()
        }
    }
    private fun run(request: Request) {
        try {
            if (!cancelled(request)) {
                val action = synchronized(lock) {
                    if (request.status.terminal) null else { request.status = OrionCortexRequestStatus.RUNNING; request.run }
                }
                action?.invoke(request)
            }
            finish(request, if (cancelled(request)) request.status.takeIf { it.terminal } ?: OrionCortexRequestStatus.CANCELLED
                else OrionCortexRequestStatus.SUCCEEDED)
        } catch (_: Exception) {
            synchronized(lock) { loadedModel = null }
            finish(request, OrionCortexRequestStatus.FAILED)
            try { engine?.unload() } catch (_: Exception) { synchronized(lock) { closed = true } }
        }
        finally {
            try { request.cleanup?.invoke() } catch (_: Exception) { }
            synchronized(lock) {
                request.run = null; request.callback = null; request.cleanup = null
                request.completed = true
                if (active === request) active = null
                scheduleNext()
                if (active == null && loadedModel != null) idleSince = try { clock.monotonicNow() } catch (_: Exception) { null }
                if (!closed && active == null && pressureCleanupNeeded) {
                    pressureCleanupNeeded = false
                    unloadModel()
                }
            }
        }
    }
    private fun cancelled(request: Request): Boolean {
        checkTimeouts()
        return closed || request.status.terminal
    }
    private fun emit(request: Request, text: String): Boolean {
        if (cancelled(request)) return false
        for (part in orionCortexTextParts(text)) {
            val chunk = synchronized(lock) {
                if (request.status.terminal) return false
                if (request.outputChars > OrionCortexBounds.MAX_OUTPUT_CHARS - part.length || request.sequence >= OrionCortexBounds.MAX_CHUNKS) {
                    finish(request, OrionCortexRequestStatus.FAILED); return false
                }
                request.outputChars += part.length
                OrionCortexChunk(request.id, request.sequence++, part)
            }
            if (!deliver(request, chunk)) return false
        }
        return !cancelled(request)
    }
    private fun deliver(request: Request, chunk: OrionCortexChunk): Boolean {
        return try {
            val callback = synchronized(lock) { request.callback } ?: return false
            callback(chunk); true
        } catch (_: Exception) { cancelInference(request.id); false }
    }
    private fun finish(request: Request, status: OrionCortexRequestStatus) {
        val delivery = synchronized(lock) {
            if (request.status.terminal) return
            request.status = status
            if (status != OrionCortexRequestStatus.SUCCEEDED && request.cancelAt == null)
                request.cancelAt = try { clock.monotonicNow() } catch (_: Exception) { request.deadline }
            OrionCortexChunk(request.id, request.sequence++, terminal = status) to request.callback
        }
        // Native completion may clear the stored listener concurrently with a
        // watchdog terminal. Preserve this one delivery without retaining it in history.
        try { delivery.second?.invoke(delivery.first) } catch (_: Exception) { cancelInference(request.id) }
    }
    fun cancelInference(id: UUID) {
        val request = synchronized(lock) {
            (requests[id] ?: active?.takeIf { it.id == id }).also {
                if (it == null && requests.size + cancelledBeforeStart.size < OrionCortexBounds.MAX_REQUESTS)
                    cancelledBeforeStart += id
            }
        } ?: return
        if (request.status.terminal) return
        request.cancelAt = try { clock.monotonicNow() } catch (_: Exception) { request.deadline }
        finish(request, OrionCortexRequestStatus.CANCELLED)
    }
    /**
     * AI-local pressure invalidates the whole resident session. The fence is
     * sticky for this service instance: recovery needs a new explicit MAIN request,
     * fresh policy approval and a recreated service, never an automatic reload.
     */
    fun onMemoryPressure() {
        val waiting: List<Request>
        val affected: List<Request>
        synchronized(lock) {
            if (closed || pressureFenced) return
            pressureFenced = true
            pressureCleanupNeeded = engine != null || active != null
            loadedModel = null; idleSince = null
            waiting = queue.toList(); queue.clear()
            affected = (requests.values + listOfNotNull(active)).filter { !it.completed }.distinctBy { it.id }
        }
        affected.forEach { finish(it, OrionCortexRequestStatus.FAILED) }
        for (request in waiting) {
            try { request.cleanup?.invoke() } catch (_: Exception) { }
            synchronized(lock) {
                request.run = null; request.cleanup = null; request.callback = null; request.completed = true
            }
        }
        synchronized(lock) {
            if (!closed && active == null && pressureCleanupNeeded) {
                pressureCleanupNeeded = false
                unloadModel()
            }
        }
    }
    fun checkTimeouts() {
        val now = try { clock.monotonicNow() } catch (_: Exception) {
            val affected = synchronized(lock) { closed = true; (requests.values + listOfNotNull(active)).filter { !it.completed }.distinctBy { it.id } }
            affected.forEach { finish(it, OrionCortexRequestStatus.FAILED) }
            val stop = synchronized(lock) {
                if (hardStopped || active == null && loadedModel == null) false
                else { hardStopped = true; true }
            }
            if (stop) hardStop()
            return
        }
        val snapshot = synchronized(lock) { (requests.values + listOfNotNull(active)).filter { !it.completed }.distinctBy { it.id } }
        for (request in snapshot) {
            val age = now.elapsedSince(request.deadline)?.toMillis()
            if (!request.status.terminal && (age == null || age >= request.timeoutMillis)) {
                request.cancelAt = now; finish(request, OrionCortexRequestStatus.TIMED_OUT)
            }
        }
        // Identity, completion and elapsed cancellation grace are one ownership
        // check. A stale watchdog snapshot cannot terminate a newer healthy task.
        val stop = synchronized(lock) {
            val running = active
            val cancelledAt = running?.cancelAt
            val cancelAge = cancelledAt?.let { now.elapsedSince(it)?.toMillis() }
            if (hardStopped || running == null || running.completed || cancelledAt == null ||
                cancelAge != null && cancelAge < 1_000) false
            else { hardStopped = true; closed = true; true }
        }
        if (stop) hardStop()
        val unloadIdle = synchronized(lock) {
            val idle = idleSince
            active == null && queue.isEmpty() && loadedModel != null && idle != null &&
                now.elapsedSince(idle)?.toMillis()?.let { it >= keepAliveMillis } != false
        }
        if (unloadIdle) unloadModel()
    }
    fun getRequestStatus(id: UUID): OrionCortexRequestStatus? = synchronized(lock) {
        requests[id]?.status ?: OrionCortexRequestStatus.CANCELLED.takeIf { id in cancelledBeforeStart }
    }
    fun needsWatchdog(): Boolean = synchronized(lock) { active != null || queue.isNotEmpty() || loadedModel != null }
    fun getStatus(): OrionCortexHealth = synchronized(lock) {
        when {
            closed -> OrionCortexHealth.UNAVAILABLE
            pressureFenced -> OrionCortexHealth.DEGRADED
            active?.unloading == true -> OrionCortexHealth.UNLOADING
            active?.completed == false -> if (loadedModel == null) OrionCortexHealth.LOADING else OrionCortexHealth.INFERENCING
            loadedModel != null -> OrionCortexHealth.READY
            else -> OrionCortexHealth.UNLOADED
        }
    }
    fun unloadModel(): Boolean = synchronized(lock) {
        if (active != null || queue.isNotEmpty()) return@synchronized false
        if (closed) return@synchronized false
        val sampled = try { clock.monotonicNow() } catch (_: Exception) { closed = true; return@synchronized false }
        if (sampled.bootSessionId == null) { closed = true; return@synchronized false }
        val request = Request(UUID.randomUUID(), loadedModel ?: "orion_unload", sampled, 5_000, {}, 4,
            { engine?.unload() }, {}).also { it.unloading = true }
        loadedModel = null; idleSince = null; active = request
        try { worker.execute { run(request) } }
        catch (_: Exception) { active = null; closed = true; return@synchronized false }
        true
    }
    override fun close() {
        val abandoned: List<Request>
        val remaining = synchronized(lock) {
            closed = true; abandoned = queue.toList(); queue.clear()
            (requests.values + listOfNotNull(active)).distinctBy { it.id }
        }
        remaining.forEach { cancelInference(it.id) }
        for (request in abandoned) {
            try { request.cleanup?.invoke() } catch (_: Exception) { }
            synchronized(lock) { request.run = null; request.cleanup = null; request.callback = null; request.completed = true }
        }
        // Unload is serialized after native execution; a stuck native call is handled by the watchdog.
        try { worker.execute {
            val mark = try { clock.monotonicNow() } catch (_: Exception) { remaining.firstOrNull()?.deadline }
            val closing = mark?.let { Request(UUID.randomUUID(), "orion_close", it, 1_000, {},4,{},{}).also { request ->
                request.cancelAt = it; request.status = OrionCortexRequestStatus.CANCELLED; request.unloading = true
            } }
            synchronized(lock) { active = closing }
            try { engine?.close() } catch (_: Exception) { }
            finally {
                engine = null
                synchronized(lock) { closing?.completed = true; active = null; loadedModel = null }
            }
        } } catch (_: Exception) { }
    }
}
