package network.orion.app.voice

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.RemoteCallbackList
import android.os.RemoteException
import java.util.UUID
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import network.orion.app.OrionApplication
import network.orion.core.ipc.OrionIpcProtocol
import network.orion.core.ipc.VoiceCoreResponse
import network.orion.voice.ipc.*

/** Private versioned transport. The MAIN runtime owns the durable, encrypted admission. */
class OrionCoreGatewayService : Service() {
    private val core get() = (application as OrionApplication).mainRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callbacks = RemoteCallbackList<IOrionVoiceCallback>()
    private class Subscriber(val id: UUID, var lastSequence: Long = -1) {
        val registeredAt = TimeSource.Monotonic.markNow()
    }
    private val pump = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (signal in pump) {
                do {
                    val ingress = core?.voiceIngress
                    try {
                        ingress?.drain()
                        publishCallbacks(ingress)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: RuntimeException) {
                        core?.refreshVoiceHealth(failure = true)
                    }
                    if (callbacks.registeredCallbackCount == 0) break
                    delay(150)
                } while (isActive)
            }
        }
    }

    private val endpoint = object : IOrionVoiceCoreGateway.Stub() {
        override fun getProtocolVersion(): IntArray {
            enforceAppCaller()
            val local = OrionIpcProtocol.CURRENT
            return intArrayOf(local.protocolMajor, local.protocolMinor)
        }

        override fun getCoreReadiness(protocolMajor: Int, protocolMinor: Int): String? {
            enforceAppCaller()
            if (!compatible(protocolMajor) || protocolMinor < 0) return null
            return core?.health?.value?.core?.readiness?.name
        }

        override fun submitUtterance(protocolMajor: Int, request: Bundle): String {
            enforceAppCaller()
            if (!compatible(protocolMajor)) return OrionVoiceAdmission.PROTOCOL_MISMATCH.name
            val decoded = OrionVoiceWire.readRequest(request) ?: return OrionVoiceAdmission.REJECTED_INVALID.name
            val result = core?.voiceIngress?.submit(decoded) ?: OrionVoiceAdmission.CORE_UNAVAILABLE
            if (result in setOf(OrionVoiceAdmission.ACCEPTED, OrionVoiceAdmission.QUEUED_DURING_RECOVERY))
                pump.trySend(Unit)
            return result.name
        }

        override fun getVoiceRequestStatus(protocolMajor: Int, requestId: String, afterSequence: Long): Bundle? {
            enforceAppCaller()
            if (!compatible(protocolMajor) || afterSequence !in -1..2) return null
            val id = OrionVoiceWire.requestId(requestId) ?: return null
            return core?.voiceIngress?.responses(id)?.firstOrNull { it.sequence > afterSequence }?.let(OrionVoiceWire::response)
        }

        override fun cancelVoiceRequest(protocolMajor: Int, requestId: String): Boolean {
            enforceAppCaller()
            if (!compatible(protocolMajor)) return false
            val id = OrionVoiceWire.requestId(requestId) ?: return false
            val cancelled = core?.voiceIngress?.cancel(id) == true
            if (cancelled) pump.trySend(Unit)
            return cancelled
        }

        override fun registerVoiceCallback(protocolMajor: Int, requestId: String, callback: IOrionVoiceCallback): Boolean {
            enforceAppCaller()
            if (!compatible(protocolMajor)) return false
            val id = OrionVoiceWire.requestId(requestId) ?: return false
            val registered = synchronized(callbacks) {
                callbacks.registeredCallbackCount < 8 && callbacks.register(callback, Subscriber(id))
            }
            if (registered) pump.trySend(Unit)
            return registered
        }

        override fun unregisterVoiceCallback(callback: IOrionVoiceCallback) {
            enforceAppCaller()
            callbacks.unregister(callback)
        }

        override fun healthCheck(protocolMajor: Int): Boolean {
            enforceAppCaller()
            return compatible(protocolMajor) && core?.health?.value?.core?.readiness?.isStartupBarrierOpen == true
        }
    }

    private fun publishCallbacks(ingress: OrionVoiceIngress?) {
        val finished = mutableListOf<IOrionVoiceCallback>()
        val count = callbacks.beginBroadcast()
        try {
            repeat(count) { index ->
                val callback = callbacks.getBroadcastItem(index)
                val owner = callbacks.getBroadcastCookie(index) as Subscriber
                if (owner.registeredAt.elapsedNow().inWholeMilliseconds > 15_000) {
                    finished += callback
                    return@repeat
                }
                val responses: List<VoiceCoreResponse> = ingress?.responses(owner.id).orEmpty()
                for (response in responses.filter { it.sequence > owner.lastSequence }) {
                    try {
                        callback.onResponse(OrionVoiceWire.response(response))
                    } catch (_: RemoteException) {
                        finished += callback
                        break
                    }
                    owner.lastSequence = response.sequence
                    if (response.terminalState != null) finished += callback
                }
            }
        } finally { callbacks.finishBroadcast() }
        finished.forEach(callbacks::unregister)
    }

    override fun onBind(intent: Intent) = endpoint

    override fun onDestroy() {
        scope.cancel()
        callbacks.kill()
        super.onDestroy()
    }

    private fun compatible(major: Int) = major == OrionIpcProtocol.CURRENT.protocolMajor

    private fun enforceAppCaller() {
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Invalid voice gateway caller.")
    }
}
