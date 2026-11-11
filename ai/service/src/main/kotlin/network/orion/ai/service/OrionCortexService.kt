package network.orion.ai.service

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Debug
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import android.system.Os
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import network.orion.ai.api.IOrionCortex
import network.orion.ai.api.IOrionInferenceCallback
import network.orion.ai.api.OrionCortexWire
import network.orion.ai.api.OrionCortexProtocol
import network.orion.ai.api.OrionIssuedRequestHistory
import network.orion.ai.api.OrionRetiredRequestException
import network.orion.ai.llama.OrionLlamaFinish
import network.orion.ai.llama.OrionLlamaRuntime
import network.orion.core.time.AndroidOrionClock
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicDeadline
import network.orion.core.time.MonotonicDeadlineStatus
import network.orion.core.time.MonotonicInstant

/** Disposable inference mechanics only. MAIN remains the sole resource/policy authority. */
class OrionCortexService : Service() {
    private val lock = Any()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(2))
    private val timer = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val requests = linkedMapOf<String, Request>()
    private val issuedRequests = OrionIssuedRequestHistory<Request> { it.terminal != null && active !== it }
    private lateinit var clock: AndroidOrionClock
    private lateinit var watchdog: OrionNativeUnresponsiveWatchdog
    @Volatile private var runtime: OrionLlamaRuntime? = null
    @Volatile private var active: Request? = null
    private var loadedId: String? = null
    private var modelFd: ParcelFileDescriptor? = null
    @Volatile private var closing = false
    private var unloading = false
    @Volatile private var nativeValidationCompleted = false

    private class Request(val id: String, val deadline: MonotonicDeadline?,
        val callback: IOrionInferenceCallback? = null, val loading: Boolean = false,
        var fingerprint: String? = null) {
        @Volatile var terminal: String? = null
        var sequence = 0L
        var watcher: ScheduledFuture<*>? = null
        var death: IBinder.DeathRecipient? = null
        var work: OrionNativeUnresponsiveWatchdog.Ticket? = null
    }

    override fun onCreate() {
        super.onCreate()
        check(Application.getProcessName() == "$packageName:ai")
        clock = AndroidOrionClock(contentResolver)
        watchdog = OrionNativeUnresponsiveWatchdog(clock, 5_000, OrionNativeWatchdogScheduler()) {
            check(Application.getProcessName() == "$packageName:ai")
            Process.killProcess(Process.myPid())
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IOrionCortex.Stub() {
        override fun getProtocolInfo(): Bundle {
            caller()
            return reply("OK") {
                putInt("major", OrionCortexProtocol.CURRENT.protocolMajor)
                putInt("minor", OrionCortexProtocol.CURRENT.protocolMinor)
            }
        }

        override fun getCapabilities(): Bundle {
            caller()
            return reply("OK") { putString("capabilities", OrionCortexProtocol.ISSUED_HISTORY.value) }
        }

        override fun issueRequestId(): Bundle {
            caller()
            return synchronized(lock) {
                if (closing) reply("UNAVAILABLE")
                else reply("OK") { putString("requestId", issuedRequests.issue()) }
            }
        }

        override fun loadModel(model: ParcelFileDescriptor?, specification: Bundle?): Bundle {
            caller()
            return guarded(model) { fd ->
                val spec = requireNotNull(specification)
                OrionCortexWire.requireEnvelope(spec, LOAD_KEYS)
                val id = OrionCortexWire.text(spec, "requestId")
                val modelId = OrionCortexWire.text(spec, "modelId")
                val hash = OrionCortexWire.text(spec, "sha256", 64)
                require(hash.matches(Regex("[a-f0-9]{64}")) && hash.any { it != '0' } && modelId == hash)
                val size = OrionCortexWire.long(spec, "sizeBytes")
                OrionCortexWire.requireReadOnlyFile(fd, size, MAX_MODEL_BYTES)
                val context = OrionCortexWire.int(spec, "contextTokens").also { require(it in 32..OrionCortexWire.MAX_CONTEXT) }
                val threads = OrionCortexWire.int(spec, "threads").also { require(it in 1..OrionCortexWire.MAX_THREADS) }
                val deadline = deadline(spec)
                val fingerprint = OrionCortexRequestFingerprint.load(modelId, hash, size, context, threads, deadline)
                val request = Request(id, deadline, loading = true, fingerprint = fingerprint)
                synchronized(lock) {
                    findRequest(id)?.let { return@guarded duplicateReply(it, fingerprint, deadline) }
                    require(deadline.statusAt(clock.monotonicNow()) == MonotonicDeadlineStatus.ACTIVE)
                    if (!hasCapacity(id)) return@guarded reply("CAPACITY_REACHED")
                    if (closing || unloading || active != null || loadedId != null) return@guarded reply("BUSY")
                    val owned = ParcelFileDescriptor.dup(fd.fileDescriptor)
                    try { admit(request) } catch (failure: Exception) { owned.close(); throw failure }
                    worker.execute { perform(request) {
                        var retained = false
                        try {
                            ensureActive(request)
                            require(hash(owned, size, request) == hash)
                            ensureActive(request)
                            val native = runtime ?: OrionLlamaRuntime().also { runtime = it }
                            ensureActive(request)
                            val path = "/proc/self/fd/${owned.fd}"
                            nativeValidationCompleted = false
                            val valid = native.validateModel(path)
                            nativeValidationCompleted = true
                            require(valid)
                            ensureActive(request)
                            if (!native.load(path, context, threads)) finish(request, "CANCELLED")
                            else {
                                synchronized(lock) { synchronized(request) {
                                    ensureActive(request)
                                    loadedId = modelId; modelFd = owned; retained = true
                                    finish(request, "SUCCEEDED")
                                } }
                            }
                        } finally {
                            if (!retained) { try { runtime?.unload() } finally { owned.close() } }
                        }
                    } }
                    accepted(request)
                }
            }
        }

        override fun startInference(input: SharedMemory?, specification: Bundle?, callback: IOrionInferenceCallback?): Bundle {
            caller()
            return guardedInput(input) { memory ->
                val spec = requireNotNull(specification)
                OrionCortexWire.requireEnvelope(spec, INFERENCE_KEYS)
                val id = OrionCortexWire.text(spec, "requestId")
                val modelId = OrionCortexWire.text(spec, "modelId")
                val bytes = OrionCortexWire.long(spec, "inputSize")
                require(bytes in 1..OrionCortexWire.MAX_INPUT_BYTES.toLong() && memory.size.toLong() == bytes)
                val tokens = OrionCortexWire.int(spec, "maxTokens").also { require(it in 1..OrionCortexWire.MAX_TOKENS) }
                val temperature = OrionCortexWire.temperature(spec)
                val deadline = deadline(spec)
                val target = requireNotNull(callback)
                // Snapshot exactly once before admission; the digest and native worker use the same immutable text.
                val prompt = OrionCortexWire.readUtf8(memory, bytes)
                val fingerprint = OrionCortexRequestFingerprint.inference(modelId, prompt, bytes, tokens, temperature, deadline)
                val request = Request(id, deadline, target, fingerprint = fingerprint)
                synchronized(lock) {
                    findRequest(id)?.let { return@guardedInput duplicateReply(it, fingerprint, deadline) }
                    require(deadline.statusAt(clock.monotonicNow()) == MonotonicDeadlineStatus.ACTIVE)
                    if (!hasCapacity(id)) return@guardedInput reply("CAPACITY_REACHED")
                    if (closing || unloading || active != null) return@guardedInput reply("BUSY")
                    if (loadedId != modelId) return@guardedInput reply("MODEL_MISMATCH")
                    admit(request)
                    worker.execute { perform(request) {
                            ensureActive(request)
                            val decoder = OrionUtf8Pieces()
                            val result = checkNotNull(runtime).generate(prompt, tokens, temperature) { piece ->
                                if (!isActive(request)) false
                                else { emitText(request, decoder.accept(piece)); isActive(request) }
                            }
                            if (result != OrionLlamaFinish.CANCELLED && isActive(request)) {
                                emitText(request, decoder.accept(ByteArray(0), final = true))
                                finish(request, "SUCCEEDED")
                            } else finish(request, "CANCELLED")
                    } }
                    accepted(request)
                }
            }
        }

        override fun cancelInference(requestId: String?) {
            caller(); val id = validId(requestId)
            active?.takeIf { it.id == id }?.work?.let(watchdog::arm)
            val request = try { synchronized(lock) {
                findRequest(id) ?: Request(id, null).also { remember(it) }
            } } catch (_: OrionRetiredRequestException) { return }
            finish(request, "CANCELLED")
            synchronized(lock) { if (active === request) runtime?.cancel() }
        }

        override fun getRequestStatus(requestId: String?): Bundle {
            caller(); val id = validId(requestId)
            val request = try { synchronized(lock) { findRequest(id) } }
                catch (_: OrionRetiredRequestException) { return reply("DUPLICATE_REQUEST") }
            if (request == null) return reply("NOT_FOUND")
            checkDeadline(request)
            return requestReply(request)
        }

        override fun getStatus(): Bundle {
            caller()
            return synchronized(lock) {
                reply(when { unloading -> "UNLOADING"; active?.loading == true -> "LOADING"; active != null -> "INFERENCING"
                    loadedId != null -> "LOADED"; else -> "UNLOADED" }) { loadedId?.let { putString("modelId", it) } }
            }
        }

        override fun unloadModel() {
            caller()
            watchdog.armAll()
            synchronized(lock) {
                active?.let { finish(it, "CANCELLED"); runtime?.cancel() }
                loadedId = null
                // Reserved worker owns cleanup after any still-running native call returns.
                if (!closing && !unloading) {
                    unloading = true
                    val cleanup = watchdog.beginWork(armed = true)
                    worker.execute {
                        try { runtime?.unload() }
                        finally {
                            try { synchronized(lock) { modelFd?.close(); modelFd = null; unloading = false } }
                            finally { watchdog.finishWork(cleanup) }
                        }
                    }
                }
            }
        }

        override fun healthCheck(): Bundle {
            caller()
            val sampled = clock.monotonicNow()
            val pss = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong() * 1024
            val health = synchronized(lock) {
                when { closing -> "UNAVAILABLE"; requests.size >= MAX_REQUESTS || !issuedRequests.hasCapacity() -> "DEGRADED"; else -> "OK" }
            }
            return reply(health) {
                putString("processName", Application.getProcessName()); putInt("pid", Process.myPid())
                putInt("bootCount", sampled.bootSessionId?.bootCount ?: -1)
                putLong("sampledElapsed", sampled.elapsedRealtimeMillis)
                putLong("totalPssBytes", pss); putBoolean("nativeIncluded", true)
                putBoolean("nativeRuntimeLoaded", runtime != null)
                putBoolean("nativeValidationCompleted", nativeValidationCompleted)
            }
        }
    }

    private fun caller() { check(Binder.getCallingUid() == Process.myUid()) { "Private Cortex caller required." } }

    private fun deadline(spec: Bundle): MonotonicDeadline {
        val duration = OrionCortexWire.long(spec, "durationMillis").also { require(it in 1..MAX_DURATION_MILLIS) }
        return MonotonicInstant(BootSessionId(OrionCortexWire.int(spec, "bootCount")),
            OrionCortexWire.long(spec, "startedElapsed")).deadlineAfter(duration)
    }

    private fun admit(request: Request) {
        remember(request)
        request.work = watchdog.beginWork()
        active = request
        request.callback?.let { callback ->
            val death = IBinder.DeathRecipient {
                request.work?.let(watchdog::arm)
                synchronized(lock) { if (active === request) { finish(request, "CANCELLED"); runtime?.cancel() } }
            }
            request.death = death
            try { callback.asBinder().linkToDeath(death, 0) } catch (_: Exception) { finish(request, "CANCELLED") }
        }
        request.watcher = timer.scheduleWithFixedDelay({
            checkDeadline(request)
            if (request.terminal != null) request.work?.let(watchdog::arm)
            synchronized(lock) { if (request.terminal != null && active === request) runtime?.cancel() }
        }, 25, 25, TimeUnit.MILLISECONDS)
    }

    private fun remember(request: Request) {
        if (OrionIssuedRequestHistory.isIssuedIdentity(request.id)) issuedRequests.put(request.id, request)
        else {
            check(requests.size < MAX_REQUESTS) { "Cortex request capacity reached." }
            requests[request.id] = request
        }
    }

    /** Protocol-1.0 history is unchanged; reserved issued identities never fall back to legacy. */
    private fun findRequest(id: String): Request? =
        if (OrionIssuedRequestHistory.isIssuedIdentity(id)) issuedRequests.find(id) else requests[id]

    private fun hasCapacity(id: String): Boolean =
        if (OrionIssuedRequestHistory.isIssuedIdentity(id)) issuedRequests.hasCapacity() else requests.size < MAX_REQUESTS

    private fun perform(request: Request, operation: () -> Unit) {
        try { operation() }
        catch (_: Exception) { finish(request, "FAILED") }
        catch (_: LinkageError) { finish(request, "FAILED") }
        finally {
            request.watcher?.cancel(false)
            request.death?.let { death -> try { request.callback?.asBinder()?.unlinkToDeath(death, 0) } catch (_: Exception) { } }
            try { synchronized(lock) { if (active === request) active = null } }
            finally { request.work?.let(watchdog::finishWork) }
        }
    }

    private fun checkDeadline(request: Request) {
        if (request.terminal != null) return
        when (request.deadline?.statusAt(clock.monotonicNow())) {
            MonotonicDeadlineStatus.EXPIRED -> finish(request, "TIMED_OUT")
            MonotonicDeadlineStatus.INVALID_REFERENCE -> finish(request, "FAILED")
            else -> Unit
        }
    }

    private fun isActive(request: Request): Boolean { checkDeadline(request); return request.terminal == null }
    private fun ensureActive(request: Request) { check(isActive(request)) }

    private fun finish(request: Request, state: String) {
        if (state != "SUCCEEDED") request.work?.let(watchdog::arm)
        synchronized(request) {
            if (request.terminal != null) return@synchronized
            request.terminal = state
            sendChunk(request, "", state)
        }
    }

    private fun emitText(request: Request, text: String) {
        var offset = 0
        while (offset < text.length && isActive(request)) {
            var end = minOf(offset + 1024, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            synchronized(request) { if (request.terminal == null) sendChunk(request, text.substring(offset, end), null) }
            offset = end
        }
    }

    private fun sendChunk(request: Request, delta: String, terminal: String?) {
        val callback = request.callback ?: return
        val chunk = Bundle().apply {
            putInt("schemaVersion", OrionCortexWire.SCHEMA); putString("requestId", request.id)
            putLong("sequence", request.sequence++); putString("delta", delta)
            putBoolean("isFinal", terminal != null); terminal?.let { putString("finishReason", it) }
        }
        try { OrionCortexWire.requireEnvelope(chunk, CHUNK_KEYS); callback.onChunk(chunk) }
        // The watcher cancels only the matching active slot. Never acquire the
        // service lock while holding a request monitor (service -> request order).
        catch (_: Exception) { request.work?.let(watchdog::arm); request.terminal = request.terminal ?: "CANCELLED" }
    }

    private fun hash(fd: ParcelFileDescriptor, size: Long, request: Request): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var offset = 0L
        while (offset < size) {
            ensureActive(request)
            val count = Os.pread(fd.fileDescriptor, buffer, 0, minOf(buffer.size.toLong(), size - offset).toInt(), offset)
            check(count > 0)
            digest.update(buffer, 0, count); offset += count
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validId(value: String?): String = requireNotNull(value).also {
        require(it.length in 1..128 && it == it.trim() && it.none(Char::isISOControl))
    }

    /** Called only under the admission lock. A cancellation tombstone binds its first valid payload, never executes it. */
    private fun duplicateReply(request: Request, fingerprint: String, deadline: MonotonicDeadline): Bundle {
        if (request.fingerprint == null) {
            check(request.terminal == "CANCELLED")
            require(deadline.statusAt(clock.monotonicNow()) == MonotonicDeadlineStatus.ACTIVE)
            request.fingerprint = fingerprint
        }
        require(request.fingerprint == fingerprint) { "Cortex request identity conflict." }
        return requestReply(request)
    }

    private fun requestReply(request: Request) = reply("OK") {
        putString("requestId", request.id); putString("state", request.terminal ?: "RUNNING")
    }
    private fun accepted(request: Request) = reply("ACCEPTED") { putString("requestId", request.id) }

    private fun reply(status: String, extra: Bundle.() -> Unit = {}): Bundle = OrionCortexWire.reply(status).apply {
        extra(); OrionCortexWire.requireEnvelope(this, REPLY_KEYS)
    }

    private fun guarded(descriptor: ParcelFileDescriptor?, operation: (ParcelFileDescriptor) -> Bundle): Bundle = try {
        requireNotNull(descriptor).use(operation)
    } catch (_: OrionRetiredRequestException) { reply("DUPLICATE_REQUEST") }
      catch (_: Exception) { reply("INVALID_REQUEST") }

    private fun guardedInput(memory: SharedMemory?, operation: (SharedMemory) -> Bundle): Bundle = try {
        requireNotNull(memory).use(operation)
    } catch (_: OrionRetiredRequestException) { reply("DUPLICATE_REQUEST") }
      catch (_: Exception) { reply("INVALID_REQUEST") }

    override fun onDestroy() {
        closing = true
        watchdog.armAll()
        // Track queued destruction before waiting for a possibly blocked callback
        // monitor. The independent watchdog never needs that monitor to terminate us.
        val cleanup = watchdog.beginWork(armed = true)
        synchronized(lock) {
            active?.let { finish(it, "CANCELLED") }
            runtime?.cancel()
            timer.shutdownNow()
            worker.execute {
                try {
                    runtime?.close(); runtime = null
                    synchronized(lock) { modelFd?.close(); modelFd = null; loadedId = null }
                } finally { watchdog.finishWork(cleanup) }
            }
            worker.shutdown()
        }
        watchdog.closeWhenQuiet()
        super.onDestroy()
    }

    companion object {
        private const val MAX_MODEL_BYTES = 4L * 1024 * 1024 * 1024
        private const val MAX_DURATION_MILLIS = 120_000L
        private const val MAX_REQUESTS = 64
        private val TIME_KEYS = setOf("bootCount", "startedElapsed", "durationMillis")
        private val LOAD_KEYS = TIME_KEYS + setOf("schemaVersion", "requestId", "modelId", "sha256", "sizeBytes", "contextTokens", "threads")
        private val INFERENCE_KEYS = TIME_KEYS + setOf("schemaVersion", "requestId", "modelId", "inputSize", "maxTokens", "temperature")
        private val REPLY_KEYS = setOf("schemaVersion", "status", "state", "requestId", "modelId", "major", "minor", "capabilities", "processName", "pid", "bootCount", "sampledElapsed", "totalPssBytes", "nativeIncluded", "nativeRuntimeLoaded", "nativeValidationCompleted")
        private val CHUNK_KEYS = setOf("schemaVersion", "requestId", "sequence", "delta", "isFinal", "finishReason")
    }
}
