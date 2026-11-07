package network.orion.data.datastore

import android.app.Application
import android.content.Context
import android.os.UserManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import network.orion.core.common.process.MainProcessStoragePolicy
import network.orion.core.common.process.OrionProcessRole

data class OrionPreferencesSnapshot(val formatVersion: Int, val fieldKeyProvisioningAttempted: Boolean = false,
    val lastForceStopDetectedAtMillis: Long? = null)

/** One instance per main-process graph/file. No business preferences are written in F1. */
class OrionPreferencesStore private constructor(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
) : AutoCloseable {
    suspend fun read(): OrionPreferencesSnapshot {
        val values = store.data.first()
        val version = values[intPreferencesKey("orion_config_format_version")] ?: 1
        check(version == 1) { "Unsupported configuration format." }
        return OrionPreferencesSnapshot(version, values[booleanPreferencesKey("orion_field_key_provisioning_attempted")] ?: false,
            values[longPreferencesKey("orion_last_force_stop_detected_at")])
    }

    /** Durable intent: never clear this marker as a response to a missing key. */
    suspend fun markFieldKeyProvisioningAttempted() {
        store.edit { it[booleanPreferencesKey("orion_field_key_provisioning_attempted")] = true }
    }

    suspend fun recordForceStopDetection(at: Long) {
        store.edit { it[longPreferencesKey("orion_last_force_stop_detected_at")] = at }
    }

    /** Scan position is only a maintenance optimization; Room remains authoritative. */
    suspend fun maintenanceCursor(name: String): String? {
        require(name in setOf("reminders", "alarm_cleanup", "due_reminders"))
        return store.data.first()[stringPreferencesKey("orion_cursor_$name")]
    }

    suspend fun saveMaintenanceCursor(name: String, cursor: String?) {
        require(name in setOf("reminders", "alarm_cleanup", "due_reminders"))
        if (cursor != null) require(java.util.UUID.fromString(cursor).toString() == cursor)
        store.edit { values ->
            val key = stringPreferencesKey("orion_cursor_$name")
            if (cursor == null) values.remove(key) else values[key] = cursor
        }
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
