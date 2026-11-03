package network.orion.data.datastore

import android.app.Application
import android.content.Context
import android.os.UserManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import network.orion.core.common.process.MainProcessStoragePolicy
import network.orion.core.common.process.OrionProcessRole

data class OrionPreferencesSnapshot(val formatVersion: Int)

/** One instance per main-process graph/file. No business preferences are written in F1. */
class OrionPreferencesStore private constructor(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
) : AutoCloseable {
    suspend fun read(): OrionPreferencesSnapshot {
        val version = store.data.first()[intPreferencesKey("orion_config_format_version")] ?: 1
        check(version == 1) { "Unsupported configuration format." }
        return OrionPreferencesSnapshot(version)
    }

    override fun close() { scope.cancel() }

    companion object {
        fun open(context: Context): OrionPreferencesStore {
            val app = context.applicationContext
            MainProcessStoragePolicy.requireAccess(
                OrionProcessRole.fromProcessName(app.packageName, Application.getProcessName()),
                app.getSystemService(UserManager::class.java).isUserUnlocked,
                context.isDeviceProtectedStorage,
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = PreferenceDataStoreFactory.create(scope = scope,
                produceFile = { app.preferencesDataStoreFile("orion_settings") })
            return OrionPreferencesStore(store, scope)
        }
    }
}
