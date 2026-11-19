package network.orion.app

import java.time.Instant
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import network.orion.core.health.OrionSchedulingDiagnostics
import network.orion.core.health.TaskQueueDiagnostics

/** Ephemeral technical observations; missing sources never retain a previous successful value. */
internal data class OrionDiagnosticsSnapshot(
    val scheduling: OrionSchedulingDiagnostics?,
    val queues: TaskQueueDiagnostics?,
)

/** Each optional source fails independently. No read grants authority or invokes recovery. */
internal class OrionDiagnosticsReader(
    private val readScheduling: suspend () -> OrionSchedulingDiagnostics?,
    private val readQueues: suspend () -> TaskQueueDiagnostics?,
) {
    suspend fun read(): OrionDiagnosticsSnapshot {
        currentCoroutineContext().ensureActive()
        val scheduling = optional(readScheduling)
        currentCoroutineContext().ensureActive()
        val queues = optional(readQueues)
        currentCoroutineContext().ensureActive()
        return OrionDiagnosticsSnapshot(scheduling, queues)
    }

    /** Serial UI observation only. The lifecycle owner cancels both reads and the delay on pause. */
    suspend fun observe(intervalMillis: Long = 2_000, publish: (OrionDiagnosticsSnapshot) -> Unit) {
        require(intervalMillis in 1..60_000)
        while (true) {
            val snapshot = read()
            currentCoroutineContext().ensureActive()
            publish(snapshot)
            delay(intervalMillis)
        }
    }

    private suspend fun <T> optional(read: suspend () -> T): T? = try { read() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        }
        catch (_: Exception) { null }
}

/** Only composed for the selected diagnostics screen; resume always starts a fresh observation. */
@Composable
internal fun OrionDiagnosticsObservation(
    lifecycle: Lifecycle,
    reader: OrionDiagnosticsReader,
    recoveryVersion: Instant?,
    refreshRevision: Long,
    intervalMillis: Long = 2_000,
    onSnapshot: (OrionDiagnosticsSnapshot) -> Unit,
) {
    val publish by rememberUpdatedState(onSnapshot)
    LaunchedEffect(lifecycle, reader, recoveryVersion, refreshRevision, intervalMillis) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            reader.observe(intervalMillis) { publish(it) }
        }
    }
}
