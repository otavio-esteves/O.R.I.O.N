package network.orion.voice.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import network.orion.core.ipc.OrionIpcProtocol
import network.orion.voice.ipc.IOrionPushToTalk
import network.orion.voice.ipc.IOrionPushToTalkCallback
import network.orion.voice.ipc.OrionVoiceWire

/** App-owned Binder adapter for the foreground capture in :voice_session. */
class OrionVoiceSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
                try { callback.asBinder().linkToDeath(session, 0) }
                catch (_: RemoteException) { return@launch }
                active = session
                session.capture.start(session::complete)
            }
        }

        override fun finishCapture(protocolMajor: Int, sessionId: String) {
            requireVoiceAppCaller()
            if (protocolMajor != OrionIpcProtocol.CURRENT.protocolMajor) return
            scope.launch { active?.takeIf { it.id.toString() == sessionId }?.capture?.finishCapture() }
        }

        override fun cancel(protocolMajor: Int, sessionId: String) {
            requireVoiceAppCaller()
            if (protocolMajor != OrionIpcProtocol.CURRENT.protocolMajor) return
            scope.launch { active?.takeIf { it.id.toString() == sessionId }?.capture?.cancel() }
        }
    }

    private inner class Session(val id: UUID, private val callback: IOrionPushToTalkCallback) : IBinder.DeathRecipient {
        val capture = OrionVoiceCapture(this@OrionVoiceSessionService, scope, id, { true }, ::report)

        private fun report(state: OrionVoiceSessionState, text: String?, confirmation: Boolean) {
            try { callback.onState(id.toString(), state.name, text, confirmation) }
            catch (_: RemoteException) { capture.cancel() }
        }

        override fun binderDied() {
            scope.launch { capture.cancel() }
        }

        fun complete() {
            try { callback.asBinder().unlinkToDeath(this, 0) } catch (_: NoSuchElementException) { }
            if (active === this) active = null
        }
    }

    override fun onBind(intent: Intent) = endpoint

    override fun onUnbind(intent: Intent): Boolean {
        active?.capture?.cancel()
        return false
    }

    override fun onDestroy() {
        active?.capture?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
