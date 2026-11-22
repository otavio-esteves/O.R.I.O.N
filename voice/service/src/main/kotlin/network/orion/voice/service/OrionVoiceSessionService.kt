package network.orion.voice.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import network.orion.core.ipc.*
import network.orion.core.time.AndroidOrionClock
import network.orion.voice.ipc.*

/** The microphone, VAD, STT and TTS live only in the explicitly bound session process. */
class OrionVoiceSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder by lazy { OrionVoiceRecorder(this) }
    private val clock by lazy { AndroidOrionClock(contentResolver) }
    private var active: Session? = null
    private val endpoint = object : IOrionPushToTalk.Stub() {
        override fun begin(protocolMajor: Int, sessionId: String, callback: IOrionPushToTalkCallback) {
            requireVoiceAppCaller()
            if (protocolMajor != OrionIpcProtocol.CURRENT.protocolMajor) return
            val id = OrionVoiceWire.requestId(sessionId) ?: return
            scope.launch {
                if (active != null) {
                    try { callback.onState(sessionId, OrionVoiceSessionState.FAILED.name, null, false) }
                    catch (_: RemoteException) { }
                    return@launch
                }
                val session = Session(id, callback)
                active = session
                try { callback.asBinder().linkToDeath(session, 0) }
                catch (_: RemoteException) { active = null; return@launch }
                session.job = launch { runSession(session) }
            }
        }

        override fun finishCapture(protocolMajor: Int, sessionId: String) {
            requireVoiceAppCaller()
            if (protocolMajor != OrionIpcProtocol.CURRENT.protocolMajor) return
            scope.launch { active?.takeIf { it.id.toString() == sessionId }?.finishCapture?.set(true) }
        }

        override fun cancel(protocolMajor: Int, sessionId: String) {
            requireVoiceAppCaller()
            if (protocolMajor != OrionIpcProtocol.CURRENT.protocolMajor) return
            scope.launch { active?.takeIf { it.id.toString() == sessionId }?.cancel() }
        }
    }

    private inner class Session(val id: UUID, val callback: IOrionPushToTalkCallback) : IBinder.DeathRecipient {
        val requestId = UUID.randomUUID()
        val modelOperation = operationIds.incrementAndGet()
        val finishCapture = AtomicBoolean()
        val decoding = AtomicBoolean()
        var job: Job? = null
        var terminal = false

        fun cancel() {
            finishCapture.set(true)
            if (decoding.get()) {
                try { OrionWhisper.cancel(modelOperation) } catch (_: LinkageError) { }
            }
            job?.cancel()
        }

        override fun binderDied() { scope.launch { cancel() } }

        fun report(state: OrionVoiceSessionState, text: String? = null, confirm: Boolean = false) {
            if (terminal) return
            terminal = state.terminal
            try { callback.onState(id.toString(), state.name, text, confirm) }
            catch (_: RemoteException) { cancel() }
        }
    }

    private suspend fun runSession(session: Session) {
        val core = OrionBoundVoiceCoreClient(this)
        try {
            val model = withContext(Dispatchers.IO) { OrionVoiceModel.verified(this@OrionVoiceSessionService) }
                ?: return session.report(OrionVoiceSessionState.MODEL_UNAVAILABLE)
            val pcm = try {
                recorder.capture(session.finishCapture::get) {
                    scope.launch { if (active === session) session.report(OrionVoiceSessionState.LISTENING) }
                }
            } catch (_: SecurityException) {
                return session.report(OrionVoiceSessionState.MICROPHONE_UNAVAILABLE)
            } catch (_: IllegalStateException) {
                return session.report(OrionVoiceSessionState.MICROPHONE_UNAVAILABLE)
            } ?: return session.report(OrionVoiceSessionState.NO_SPEECH)
            val capturedAt = clock.wallNow()
            session.report(OrionVoiceSessionState.TRANSCRIBING)
            val text = try {
                withContext(Dispatchers.IO) {
                    session.decoding.set(true)
                    try {
                        currentCoroutineContext().ensureActive()
                        val raw = OrionWhisper.transcribe(session.modelOperation, model.absolutePath, pcm)
                        try { raw?.decodeToString(throwOnInvalidSequence = true)?.trim() }
                        finally { raw?.fill(0) }
                    } finally { session.decoding.set(false) }
                }
            } finally { pcm.fill(0) }
            if (text.isNullOrBlank()) return session.report(OrionVoiceSessionState.NO_SPEECH)

            session.report(OrionVoiceSessionState.WAITING_FOR_CORE)
            val request = VoiceUtteranceRequest(IpcSchemaVersion(1), session.requestId, session.id, text, true,
                "pt-BR", capturedAt, OrionVoiceSource.PUSH_TO_TALK, 0, UUID.randomUUID())
            val reply = withTimeoutOrNull(15_000) { submitAndWait(core, request) }
                ?: return session.report(OrionVoiceSessionState.CORE_UNAVAILABLE)
            if (reply.text != null) {
                session.report(OrionVoiceSessionState.SPEAKING, reply.text, reply.confirm)
                try { speakOrionVoiceReply(this, reply.text, session.id.toString()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: RuntimeException) { /* The visible response is already available. */ }
            }
            session.report(OrionVoiceSessionState.SUCCEEDED, reply.text, reply.confirm)
        } catch (cancelled: CancellationException) {
            session.report(OrionVoiceSessionState.CANCELLED)
            throw cancelled
        } catch (_: Exception) {
            session.report(OrionVoiceSessionState.FAILED)
        } catch (_: LinkageError) {
            session.report(OrionVoiceSessionState.MODEL_UNAVAILABLE)
        } finally {
            withContext(NonCancellable) {
                if (session.terminal && core.state == VoiceCoreConnectionState.CONNECTED) core.cancel(session.requestId)
                core.disconnect()
            }
            try { session.callback.asBinder().unlinkToDeath(session, 0) } catch (_: NoSuchElementException) { }
            if (active === session) active = null
        }
    }

    private data class Reply(val text: String?, val confirm: Boolean)

    private suspend fun submitAndWait(core: VoiceCoreClient, request: VoiceUtteranceRequest): Reply? {
        var submitted = false
        var sequence = -1L
        var text: String? = null
        val started = TimeSource.Monotonic.markNow()
        while (currentCoroutineContext().isActive) {
            if (core.state != VoiceCoreConnectionState.CONNECTED &&
                core.connect() != VoiceCoreConnectionState.CONNECTED) {
                delay(150)
                continue
            }
            if (!submitted) {
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
                if (started.elapsedNow().inWholeMilliseconds >= 14_000) return null
                delay(100)
            }
        }
        return null
    }

    override fun onBind(intent: Intent) = endpoint
    override fun onUnbind(intent: Intent): Boolean {
        active?.cancel()
        return false
    }
    override fun onDestroy() {
        active?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private companion object { val operationIds = AtomicLong() }
}
