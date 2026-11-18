package network.orion.app

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import network.orion.core.health.OrionSchedulingDiagnostics
import network.orion.core.health.TaskQueueDiagnostics
import org.junit.Assert.*
import org.junit.Test

class OrionDiagnosticsObservationTest {
    private val queues = TaskQueueDiagnostics(1, 2, 3, 4, 5, 6)
    private val scheduling = OrionSchedulingDiagnostics(7, 2, 1, "DENIED", true, false,
        "UNAVAILABLE", Instant.EPOCH, null)

    @Test fun schedulingFailureKeepsAvailableQueues() = runBlocking {
        val snapshot = OrionDiagnosticsReader({ throw IOException("private storage detail") }, { queues }).read()
        assertNull(snapshot.scheduling)
        assertEquals(queues, snapshot.queues)
    }

    @Test fun queueFailureKeepsAvailableScheduling() = runBlocking {
        val snapshot = OrionDiagnosticsReader({ scheduling }, { throw IOException() }).read()
        assertEquals(scheduling, snapshot.scheduling)
        assertNull(snapshot.queues)
    }

    @Test fun laterFailureClearsPreviouslyAvailableValues() = runBlocking {
        var fail = false
        val reader = OrionDiagnosticsReader({ if (fail) throw IOException() else scheduling },
            { if (fail) throw IOException() else queues })
        assertNotNull(reader.read().scheduling)
        fail = true
        val failed = reader.read()
        assertNull(failed.scheduling)
        assertNull(failed.queues)
    }

    @Test fun cancellationDoesNotReadTheNextSource() = runBlocking {
        val cancellation = CancellationException("cancelled")
        var queueReads = 0
        try {
            OrionDiagnosticsReader({ throw cancellation }, { queueReads++; queues }).read()
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertEquals(0, queueReads)
    }

    @Test fun interruptedReadPreservesInterruption() = runBlocking {
        try {
            try {
                OrionDiagnosticsReader({ throw InterruptedException() }, { queues }).read()
                fail("Interruption must propagate")
            } catch (_: InterruptedException) { assertTrue(Thread.currentThread().isInterrupted) }
        } finally { Thread.interrupted() }
    }

    @Test fun fatalFailureIsNotConvertedIntoMissingTelemetry() = runBlocking {
        val failure = AssertionError("fatal")
        try {
            OrionDiagnosticsReader({ throw failure }, { queues }).read()
            fail("Errors must propagate")
        } catch (actual: AssertionError) { assertSame(failure, actual) }
    }

    @Test fun pollingReadsNewCountsAndStopsAfterCancellation() = runBlocking {
        var reads = 0
        val snapshots = mutableListOf<OrionDiagnosticsSnapshot>()
        val reader = OrionDiagnosticsReader({ scheduling }, { queues.copy(queuedIngress = ++reads) })
        val observation = launch {
            reader.observe(intervalMillis = 1) {
                snapshots += it
                if (snapshots.size == 2) cancel()
            }
        }
        observation.join()
        assertEquals(listOf(1, 2), snapshots.map { it.queues?.queuedIngress })
        assertEquals(2, reads)
    }

    @Test fun cancellationDuringAReadNeverPublishesAStaleSnapshot() = runBlocking {
        var published = false
        val observation = launch {
            val reader = OrionDiagnosticsReader({ scheduling }, { cancel(); queues })
            reader.observe(intervalMillis = 1) { published = true }
        }
        observation.join()
        assertFalse(published)
    }
}
