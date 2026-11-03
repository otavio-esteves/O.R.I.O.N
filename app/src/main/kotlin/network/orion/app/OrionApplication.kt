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

    override fun onCreate() {
        super.onCreate()
        processRole = OrionProcessRole.fromProcessName(packageName, getProcessName())
        OrionProcessBootstrapper(mapOf(
            OrionProcessRole.MAIN to OrionProcessInitializer {
                mainRuntime = OrionFoundationRuntime(this).also { it.start() }
            },
            OrionProcessRole.AI to OrionProcessInitializer { },
            OrionProcessRole.VOICE_CONTROL to OrionProcessInitializer { },
            OrionProcessRole.VOICE_SESSION to OrionProcessInitializer { },
        )).bootstrap(processRole)
    }
}
