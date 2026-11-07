package network.orion.app

import android.app.Application
import network.orion.core.common.process.OrionProcessBootstrapper
import network.orion.core.common.process.OrionProcessInitializer
import network.orion.core.common.process.OrionProcessRole

class OrionApplication : Application() {
    lateinit var processRole: OrionProcessRole
        private set
    var mainRuntime: OrionFoundationRuntime? = null
        private set

    /** Also used after user unlock; serializes the one technical scheduler initialization. */
    internal fun initializeMainSchedulerIfUnlocked(): Boolean {
        require(OrionProcessRole.fromProcessName(packageName, getProcessName()) == OrionProcessRole.MAIN)
        if (getSystemService(android.os.UserManager::class.java)?.isUserUnlocked != true) return false
        synchronized(schedulerInitializationLock) {
            if (!androidx.work.WorkManager.isInitialized())
                androidx.work.WorkManager.initialize(this, androidx.work.Configuration.Builder().build())
        }
        return true
    }

    companion object { private val schedulerInitializationLock = Any() }

    override fun onCreate() {
        super.onCreate()
        processRole = OrionProcessRole.fromProcessName(packageName, getProcessName())
        OrionProcessBootstrapper(mapOf(
            OrionProcessRole.MAIN to OrionProcessInitializer {
                val runtime = OrionFoundationRuntime(this)
                mainRuntime = runtime
                // JobScheduler may create SystemJobService immediately after onCreate returns.
                // Initialize only the MAIN technical scheduler here; all work still awaits Core recovery.
                initializeMainSchedulerIfUnlocked()
                runtime.start()
            },
            OrionProcessRole.AI to OrionProcessInitializer { },
            OrionProcessRole.VOICE_CONTROL to OrionProcessInitializer { },
            OrionProcessRole.VOICE_SESSION to OrionProcessInitializer { },
        )).bootstrap(processRole)
    }
}
