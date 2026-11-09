package network.orion.ai.service

internal enum class OrionCortexWatchdogDisposition { MONITOR, IDLE, SHUTDOWN }

/** Destruction can transition from cancelled inference to native close; elapsed teardown time alone is insufficient. */
internal class OrionCortexWatchdogLifecycle {
    @Volatile private var destroying = false
    fun beginDestruction() { destroying = true }
    fun disposition(runtimeNeedsWatchdog: Boolean, workerTerminated: Boolean): OrionCortexWatchdogDisposition = when {
        runtimeNeedsWatchdog || destroying && !workerTerminated -> OrionCortexWatchdogDisposition.MONITOR
        destroying -> OrionCortexWatchdogDisposition.SHUTDOWN
        else -> OrionCortexWatchdogDisposition.IDLE
    }
}
