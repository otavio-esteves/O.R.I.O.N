package network.orion.app

import android.app.Application
import network.orion.core.common.process.OrionProcessBootstrapper
import network.orion.core.common.process.OrionProcessInitializer
import network.orion.core.common.process.OrionProcessRole

class OrionApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val processRole = OrionProcessRole.fromProcessName(
            applicationId = packageName,
            processName = Application.getProcessName(),
        )
        OrionProcessBootstrapper(noOpInitializers).bootstrap(processRole)
    }

    private companion object {
        val noOpInitializers = OrionProcessRole.entries.associateWith {
            OrionProcessInitializer { }
        }
    }
}
