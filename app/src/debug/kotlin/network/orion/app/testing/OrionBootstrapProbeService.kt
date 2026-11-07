package network.orion.app.testing

import android.app.Service
import android.content.Intent
import android.os.*
import network.orion.app.OrionApplication
import network.orion.core.common.process.OrionStorageAccessDeniedException
import network.orion.core.common.process.StorageAccessDeniedReason
import network.orion.core.ipc.InlineIpcPayloadPolicy
import network.orion.core.ipc.IpcPayloadTransport
import network.orion.core.ipc.IpcProtocolNegotiation
import network.orion.core.ipc.IpcSchemaVersion
import network.orion.core.ipc.OrionIpcProtocol
import network.orion.core.ipc.ProtocolInfo
import network.orion.data.database.OrionDatabaseFactory
import network.orion.data.datastore.OrionPreferencesStore

/** Debug-only ownership probe. No user data, command payload or production IPC endpoint. */
open class OrionBootstrapProbeService : Service() {
    override fun onBind(intent: Intent): IBinder = Messenger(Handler(mainLooper) { message ->
        val protocol = if (message.what == 1 && message.obj == null && inline(message) &&
            message.peekData()?.isEmpty != false) negotiate(message) else null
        if (protocol != null) {
            val app = application as OrionApplication
            val result = Bundle().apply {
                putInt("schemaVersion", IpcSchemaVersion(1).value)
                putString("role", app.processRole.name)
                putBoolean("mainGraphCreated", app.mainRuntime != null)
                putBoolean("workManagerInitialized", androidx.work.WorkManager.isInitialized())
                putBoolean("databaseDenied", denied { OrionDatabaseFactory.open(this@OrionBootstrapProbeService).close() })
                putBoolean("datastoreDenied", denied { OrionPreferencesStore.open(this@OrionBootstrapProbeService).close() })
            }
            val response = Message.obtain(null, 1, protocol.protocolMajor, protocol.protocolMinor).apply { data = result }
            if (inline(response)) {
                try { message.replyTo?.send(response) } catch (_: RemoteException) { /* Diagnostic client disappeared. */ }
            }
        }
        true
    }).binder

    private fun negotiate(message: Message): IpcProtocolNegotiation.Compatible? = try {
        ProtocolInfo(message.arg1, message.arg2).negotiateWith(OrionIpcProtocol.CURRENT) as? IpcProtocolNegotiation.Compatible
    } catch (_: IllegalArgumentException) { null }

    private fun inline(message: Message): Boolean {
        val parcel = Parcel.obtain()
        return try {
            message.writeToParcel(parcel, 0)
            InlineIpcPayloadPolicy.transportFor(parcel.dataSize()) == IpcPayloadTransport.INLINE
        } catch (_: RuntimeException) { false } finally { parcel.recycle() }
    }

    private fun denied(open: () -> Unit): Boolean = try {
        open()
        false
    } catch (denial: OrionStorageAccessDeniedException) {
        denial.reason == StorageAccessDeniedReason.NON_MAIN_PROCESS
    } catch (_: Exception) {
        // A Room main-thread failure or another platform error is not ownership evidence.
        false
    }
}

class OrionAiBootstrapProbeService : OrionBootstrapProbeService()
class OrionVoiceBootstrapProbeService : OrionBootstrapProbeService()
class OrionVoiceSessionBootstrapProbeService : OrionBootstrapProbeService()
