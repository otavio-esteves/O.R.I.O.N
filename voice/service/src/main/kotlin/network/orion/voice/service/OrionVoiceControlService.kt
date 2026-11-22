package network.orion.voice.service

import android.app.Service
import android.content.*
import android.os.Binder
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import network.orion.voice.ipc.*
import network.orion.core.ipc.OrionIpcProtocol

internal fun requireVoiceAppCaller() {
    if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Invalid voice caller.")
}

enum class OrionVoiceSessionState(val terminal: Boolean = false) {
    CONNECTING, LISTENING, TRANSCRIBING, WAITING_FOR_CORE, SPEAKING,
    SUCCEEDED(true), CANCELLED(true), NO_SPEECH(true), MODEL_UNAVAILABLE(true),
    MICROPHONE_UNAVAILABLE(true), CORE_UNAVAILABLE(true), FAILED(true),
}

/** Only forwards the user's bounded foreground session to the heavyweight process. */
class OrionVoiceControlService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var active: Forwarding? = null

    private inner class Forwarding(val id: String, val owner: IOrionPushToTalkCallback) : ServiceConnection {
        var remote: IOrionPushToTalk? = null
        var bound = false
        var finishRequested = false
        private val relay = object : IOrionPushToTalkCallback.Stub() {
            override fun onState(sessionId: String, state: String, text: String?, requiresConfirmation: Boolean) {
                handler.post {
                    if (active !== this@Forwarding || sessionId != id) return@post
                    val decoded = OrionVoiceSessionState.entries.firstOrNull { it.name == state } ?: return@post
                    report(decoded, text, requiresConfirmation)
                    if (decoded.terminal) release()
                }
            }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (active !== this) return
            val connected = IOrionPushToTalk.Stub.asInterface(service)
            remote = connected
            try {
                connected.begin(OrionIpcProtocol.CURRENT.protocolMajor, id, relay)
                if (finishRequested) connected.finishCapture(OrionIpcProtocol.CURRENT.protocolMajor, id)
            } catch (_: RemoteException) { fail() }
        }

        override fun onServiceDisconnected(name: ComponentName) = fail()
        override fun onBindingDied(name: ComponentName) = fail()
        override fun onNullBinding(name: ComponentName) = fail()

        fun report(state: OrionVoiceSessionState, text: String? = null, confirmation: Boolean = false) {
            try { owner.onState(id, state.name, text, confirmation) } catch (_: RemoteException) { release() }
        }

        fun fail() {
            if (active !== this) return
            report(OrionVoiceSessionState.FAILED)
            release()
        }

        fun release() {
            if (active !== this) return
            active = null
            try { remote?.cancel(OrionIpcProtocol.CURRENT.protocolMajor, id) } catch (_: RemoteException) { }
            if (bound) unbindService(this)
        }
    }

    private val endpoint = object : IOrionPushToTalk.Stub() {
        override fun begin(protocolMajor: Int, sessionId: String, callback: IOrionPushToTalkCallback) {
            requireVoiceAppCaller()
            if (protocolMajor != OrionIpcProtocol.CURRENT.protocolMajor) return
            if (OrionVoiceWire.requestId(sessionId) == null) return
            handler.post {
                active?.release()
                val session = Forwarding(sessionId, callback)
                active = session
                session.report(OrionVoiceSessionState.CONNECTING)
                if (active !== session) return@post
                session.bound = try {
                    bindService(Intent(this@OrionVoiceControlService, OrionVoiceSessionService::class.java), session, BIND_AUTO_CREATE)
                } catch (_: SecurityException) { false }
                if (!session.bound) session.fail()
                else handler.postDelayed({ if (active === session && session.remote == null) session.fail() }, 3_000)
            }
        }

        override fun finishCapture(protocolMajor: Int, sessionId: String) {
            requireVoiceAppCaller()
            if (protocolMajor != OrionIpcProtocol.CURRENT.protocolMajor) return
            handler.post {
                val session = active?.takeIf { it.id == sessionId } ?: return@post
                session.finishRequested = true
                try { session.remote?.finishCapture(protocolMajor, sessionId) } catch (_: RemoteException) { session.fail() }
            }
        }

        override fun cancel(protocolMajor: Int, sessionId: String) {
            requireVoiceAppCaller()
            if (protocolMajor != OrionIpcProtocol.CURRENT.protocolMajor) return
            handler.post { active?.takeIf { it.id == sessionId }?.release() }
        }
    }

    override fun onBind(intent: Intent) = endpoint
    override fun onUnbind(intent: Intent): Boolean {
        active?.release()
        return false
    }
    override fun onDestroy() {
        active?.release()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
