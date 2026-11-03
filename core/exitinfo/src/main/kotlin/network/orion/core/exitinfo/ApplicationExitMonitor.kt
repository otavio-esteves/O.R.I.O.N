package network.orion.core.exitinfo

import java.time.Instant

/** Numeric platform reason only. Never infer Force Stop from an exit reason alone. */
data class ProcessExitSignal(val reason: Int, val occurredAt: Instant)

/** Main-owned read boundary; no user content or OS descriptions are exposed. */
fun interface ApplicationExitMonitor {
    suspend fun recentExits(): List<ProcessExitSignal>
}
