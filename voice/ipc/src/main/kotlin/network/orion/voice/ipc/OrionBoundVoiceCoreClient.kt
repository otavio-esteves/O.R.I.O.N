package network.orion.voice.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import java.util.UUID
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import network.orion.core.ipc.IpcProtocolNegotiation
import network.orion.core.ipc.OrionIpcProtocol
import network.orion.core.ipc.ProtocolInfo
import network.orion.core.ipc.VoiceUtteranceRequest

class OrionBoundVoiceCoreClient(context: Context) : VoiceCoreClient {
    private val app = context.applicationContext
    private val operations = Mutex()
    @Volatile private var binding: ServiceConnection? = null
    @Volatile private var remote: IOrionVoiceCoreGateway? = null

    override val protocolInfo = OrionIpcProtocol.CURRENT
    override val state get() = if (remote?.asBinder()?.isBinderAlive == true) {
        VoiceCoreConnectionState.CONNECTED
    } else VoiceCoreConnectionState.UNAVAILABLE

    override suspend fun connect(): VoiceCoreConnectionState = withContext(Dispatchers.IO) {
        operations.withLock {
            if (state == VoiceCoreConnectionState.CONNECTED) return@withLock state
            releaseBinding()
            val connected = CompletableDeferred<IBinder?>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    connected.complete(service)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    if (binding === this) remote = null
                    connected.complete(null)
                }

                override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
            }
            val intent = Intent().setClassName(app, CORE_GATEWAY_CLASS)
            try {
                if (app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                    binding = connection
                    val candidate = withTimeoutOrNull(3_000) { connected.await() }
                        ?.let(IOrionVoiceCoreGateway.Stub::asInterface)
                    if (candidate != null && compatible(candidate.protocolVersion)) remote = candidate
                }
            } catch (_: RemoteException) {
                remote = null
            } catch (_: SecurityException) {
                remote = null
            } finally {
                if (state != VoiceCoreConnectionState.CONNECTED) releaseBinding()
            }
            state
        }
    }

    override suspend fun getCoreReadiness(): String? = withContext(Dispatchers.IO) {
        operations.withLock {
            try {
                remote?.getCoreReadiness(protocolInfo.protocolMajor, protocolInfo.protocolMinor)
            } catch (_: RemoteException) {
                releaseBinding()
                null
            } catch (_: SecurityException) {
                releaseBinding()
                null
            }
        }
    }

    override suspend fun submitUtterance(request: VoiceUtteranceRequest): OrionVoiceAdmission =
        invoke { OrionVoiceAdmission.valueOf(it.submitUtterance(protocolInfo.protocolMajor, OrionVoiceWire.request(request))) }
            ?: OrionVoiceAdmission.CORE_UNAVAILABLE

    override suspend fun status(requestId: UUID, afterSequence: Long) = invoke {
        it.getVoiceRequestStatus(protocolInfo.protocolMajor, requestId.toString(), afterSequence)?.let(OrionVoiceWire::readResponse)
            ?.takeIf { response -> response.requestId == requestId }
    }

    override suspend fun cancel(requestId: UUID): Boolean =
        invoke { it.cancelVoiceRequest(protocolInfo.protocolMajor, requestId.toString()) } == true

    private suspend fun <T> invoke(call: (IOrionVoiceCoreGateway) -> T): T? = withContext(Dispatchers.IO) {
        operations.withLock {
            try { remote?.let(call) }
            catch (_: RemoteException) { releaseBinding(); null }
            catch (_: SecurityException) { releaseBinding(); null }
        }
    }

    override suspend fun disconnect() {
        operations.withLock { releaseBinding() }
    }

    private fun compatible(version: IntArray?): Boolean = version?.let {
        it.size == 2 && it[0] > 0 && it[1] >= 0 &&
            protocolInfo.negotiateWith(ProtocolInfo(it[0], it[1])) is IpcProtocolNegotiation.Compatible
    } ?: false

    private fun releaseBinding() {
        remote = null
        val previous = binding
        binding = null
        if (previous != null) app.unbindService(previous)
    }

    companion object {
        private const val CORE_GATEWAY_CLASS = "network.orion.app.voice.OrionCoreGatewayService"
    }
}
