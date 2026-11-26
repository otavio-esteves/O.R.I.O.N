package network.orion.voice.service

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import network.orion.core.ipc.IpcSchemaVersion
import network.orion.core.ipc.OrionVoiceResponseKind
import network.orion.core.ipc.OrionVoiceSource
import network.orion.core.ipc.OrionVoiceTerminalState
import network.orion.core.ipc.VoiceUtteranceRequest
import network.orion.core.time.AndroidOrionClock
import network.orion.voice.ipc.ORION_VOICE_REPLY_TIMEOUT_MILLIS
import network.orion.voice.ipc.OrionBoundVoiceCoreClient
import network.orion.voice.ipc.OrionVoiceAdmission
import network.orion.voice.ipc.VoiceCoreClient
import network.orion.voice.ipc.VoiceCoreConnectionState

/** One explicit push-to-talk capture in :voice_session, shared by the app and the system overlay. */
internal class OrionVoiceCapture(
    private val context: Context,
    private val scope: CoroutineScope,
    private val id: UUID,
    private val mayCapture: () -> Boolean,
    private val observe: (OrionVoiceSessionState, String?, Boolean) -> Unit,
) {
    private val operation = operationIds.incrementAndGet()
    private val requestId = UUID.randomUUID()
    private val finishRequested = AtomicBoolean()
    private val decoding = AtomicBoolean()
    private val watchdog = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var terminal = false

    fun start(onComplete: () -> Unit) {
        check(job == null)
        val capture = scope.launch(start = CoroutineStart.LAZY) { run() }
        job = capture
        capture.invokeOnCompletion { watchdog.post { onComplete() } }
        capture.start()
    }

    fun finishCapture() {
        finishRequested.set(true)
    }

    fun cancel() {
        finishCapture()
        if (decoding.get()) {
            try { OrionWhisper.cancel(operation) } catch (_: LinkageError) { }
        }
        job?.cancel()
    }

    private fun report(state: OrionVoiceSessionState, text: String? = null, confirmation: Boolean = false) {
        if (terminal) return
        terminal = state.terminal
        observe(state, text, confirmation)
    }

    private fun requireCaptureAuthority() {
        if (!mayCapture()) throw CancellationException()
    }

    private suspend fun run() {
        val core = OrionBoundVoiceCoreClient(context)
        if (!captureLock.tryLock()) {
            report(OrionVoiceSessionState.FAILED)
            return
        }
        var reservation: java.io.Closeable? = null
        try {
            requireCaptureAuthority()
            withContext(Dispatchers.IO) { reservation = OrionVoiceMemoryEndpoint.acquireHeavy(context) }
            if (reservation == null) return report(OrionVoiceSessionState.RESOURCE_BUSY)
            requireCaptureAuthority()
            val pcm = try {
                OrionVoiceRecorder(context).capture(finishRequested::get, ::requireCaptureAuthority) {
                    scope.launch { if (job?.isActive == true) report(OrionVoiceSessionState.LISTENING) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                return report(OrionVoiceSessionState.MICROPHONE_UNAVAILABLE)
            } catch (_: IllegalStateException) {
                return report(OrionVoiceSessionState.MICROPHONE_UNAVAILABLE)
            } ?: return report(OrionVoiceSessionState.NO_SPEECH)
            val capturedAt = AndroidOrionClock(context.contentResolver).wallNow()
            report(OrionVoiceSessionState.TRANSCRIBING)
            val limit = Runnable {
                if (decoding.get() && Application.getProcessName() == "${context.packageName}:voice_session") {
                    Process.killProcess(Process.myPid())
                }
            }
            val text = try {
                requireCaptureAuthority()
                val model = withContext(Dispatchers.IO) {
                    OrionVoiceModel.upgradeBundled(context)
                    OrionVoiceModel.verified(context)
                } ?: return report(OrionVoiceSessionState.MODEL_UNAVAILABLE)
                requireCaptureAuthority()
                withContext(Dispatchers.IO) {
                    decoding.set(true)
                    watchdog.postDelayed(limit, 25_000)
                    try {
                        currentCoroutineContext().ensureActive()
                        val raw = OrionWhisper.transcribe(operation, model.absolutePath, pcm)
                        try { raw?.decodeToString(throwOnInvalidSequence = true)?.trim() }
                        finally { raw?.fill(0) }
                    } finally { decoding.set(false) }
                }
            } finally {
                watchdog.removeCallbacks(limit)
                pcm.fill(0)
                reservation?.close()
                reservation = null
            }
            if (text.isNullOrBlank()) return report(OrionVoiceSessionState.NO_SPEECH)
            requireCaptureAuthority()
            report(OrionVoiceSessionState.WAITING_FOR_CORE)
            val request = VoiceUtteranceRequest(IpcSchemaVersion(1), requestId, id, text, true,
                "pt-BR", capturedAt, OrionVoiceSource.PUSH_TO_TALK, 0, UUID.randomUUID())
            // Includes the foreground preview's exit cooldown and the full Cortex request deadline.
            val reply = withTimeoutOrNull(ORION_VOICE_REPLY_TIMEOUT_MILLIS) { submitAndWait(core, request) }
                ?: return report(OrionVoiceSessionState.CORE_UNAVAILABLE)
            requireCaptureAuthority()
            if (reply.text != null) {
                report(OrionVoiceSessionState.SPEAKING, reply.text, reply.confirmation)
                try { speakOrionVoiceReply(context, reply.text, id.toString()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: RuntimeException) { /* The visible response is already available. */ }
            }
            report(OrionVoiceSessionState.SUCCEEDED, reply.text, reply.confirmation)
        } catch (cancelled: CancellationException) {
            report(OrionVoiceSessionState.CANCELLED)
            throw cancelled
        } catch (_: Exception) {
            report(OrionVoiceSessionState.FAILED)
        } catch (_: LinkageError) {
            report(OrionVoiceSessionState.MODEL_UNAVAILABLE)
        } finally {
            try {
                withContext(NonCancellable) {
                    try {
                        if (core.state == VoiceCoreConnectionState.CONNECTED) core.cancel(requestId)
                    } finally { core.disconnect() }
                }
            } finally {
                reservation?.close()
                captureLock.unlock()
            }
        }
    }

    private data class Reply(val text: String?, val confirmation: Boolean)

    private suspend fun submitAndWait(core: VoiceCoreClient, request: VoiceUtteranceRequest): Reply? {
        var submitted = false
        var sequence = -1L
        var text: String? = null
        val admissionStarted = TimeSource.Monotonic.markNow()
        while (currentCoroutineContext().isActive) {
            requireCaptureAuthority()
            if (!submitted && admissionStarted.elapsedNow().inWholeMilliseconds >= 14_000) return null
            if (core.state != VoiceCoreConnectionState.CONNECTED &&
                core.connect() != VoiceCoreConnectionState.CONNECTED) {
                delay(150)
                continue
            }
            if (!submitted) {
                requireCaptureAuthority()
                when (core.submitUtterance(request)) {
                    OrionVoiceAdmission.ACCEPTED, OrionVoiceAdmission.QUEUED_DURING_RECOVERY,
                    OrionVoiceAdmission.DUPLICATE_REQUEST -> submitted = true
                    OrionVoiceAdmission.CORE_UNAVAILABLE -> {
                        delay(150)
                        continue
                    }
                    else -> return null
                }
            }
            val response = core.status(request.requestId, sequence)
            if (response != null && response.sequence > sequence) {
                sequence = response.sequence
                if (response.kind == OrionVoiceResponseKind.TEXT_DELTA) text = response.textDelta
                response.terminalState?.let { return if (it == OrionVoiceTerminalState.SUCCEEDED)
                    Reply(text, response.requiresConfirmation) else null }
            } else {
                delay(250)
            }
        }
        return null
    }

    private companion object {
        val operationIds = AtomicLong()
        val captureLock = Mutex()
    }
}
