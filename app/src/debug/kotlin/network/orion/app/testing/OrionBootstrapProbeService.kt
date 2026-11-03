package network.orion.app.testing

import android.app.Service
import android.content.Intent
import android.os.*
import network.orion.app.OrionApplication
import network.orion.core.common.process.OrionStorageAccessDeniedException
import network.orion.core.common.process.StorageAccessDeniedReason
import network.orion.data.database.OrionDatabaseFactory
import network.orion.data.datastore.OrionPreferencesStore

/** Debug-only ownership probe. No user data, command payload or production IPC endpoint. */
open class OrionBootstrapProbeService : Service() {
    override fun onBind(intent: Intent): IBinder = Messenger(Handler(mainLooper) { message ->
        if (message.what == 1 && message.arg1 == 1) {
            val app = application as OrionApplication
            val result = Bundle().apply {
                putString("role", app.processRole.name)
                putBoolean("mainGraphCreated", app.mainRuntime != null)
                putBoolean("databaseDenied", denied { OrionDatabaseFactory.open(this@OrionBootstrapProbeService).close() })
                putBoolean("datastoreDenied", denied { OrionPreferencesStore.open(this@OrionBootstrapProbeService).close() })
            }
            val response = Message.obtain(null, 1, 1, 0).apply { data = result }
            message.replyTo?.send(response)
        }
        true
    }).binder

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
