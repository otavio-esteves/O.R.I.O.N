package network.orion.app

import android.app.Application
import android.app.Activity
import android.os.Bundle
import network.orion.ai.api.OrionCortexEngineProvider
import network.orion.ai.api.OrionInferenceEngineFactory
import network.orion.ai.llama.OrionLlamaEngine
import network.orion.core.common.process.OrionProcessBootstrapper
import network.orion.core.common.process.OrionProcessInitializer
import network.orion.core.common.process.OrionProcessRole

class OrionApplication : Application(), OrionCortexEngineProvider {
    // The wrapper constructor is lightweight. JNI/model initialization occurs only
    // in :ai after the MAIN client has admitted a load through its resource guard.
    override val orionInferenceEngineFactory = OrionInferenceEngineFactory { OrionLlamaEngine() }
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
                registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
                    private var started = 0
                    override fun onActivityStarted(activity: Activity) { started++; runtime.setInferenceForeground(started > 0) }
                    override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0); runtime.setInferenceForeground(started > 0) }
                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                    override fun onActivityResumed(activity: Activity) {}
                    override fun onActivityPaused(activity: Activity) {}
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                    override fun onActivityDestroyed(activity: Activity) {}
                })
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
