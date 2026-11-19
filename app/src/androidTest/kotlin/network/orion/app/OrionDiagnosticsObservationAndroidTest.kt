package network.orion.app

import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import network.orion.core.health.OrionSchedulingDiagnostics
import network.orion.core.health.TaskQueueDiagnostics
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionDiagnosticsObservationAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun optionalReadFailureDoesNotCrashAndNextObservationReplacesMissingData() {
        val failed = AtomicBoolean(true)
        val last = AtomicReference<OrionDiagnosticsSnapshot?>()
        val owner = Owner()
        val reader = OrionDiagnosticsReader({ if (failed.get()) throw IOException("private detail") else scheduling() },
            { TaskQueueDiagnostics(5, 0, 0, 0, 0, 0) })
        compose.activityRule.scenario.onActivity { activity ->
            owner.registry.currentState = Lifecycle.State.RESUMED
            activity.setContent {
                OrionDiagnosticsObservation(owner.lifecycle, reader, Instant.EPOCH, 0, intervalMillis = 100) { last.set(it) }
            }
        }
        compose.waitUntil(10_000) { last.get()?.queues?.queuedIngress == 5 }
        assertNull(checkNotNull(last.get()).scheduling)
        failed.set(false)
        compose.waitUntil(10_000) { last.get()?.scheduling?.exactAlarmCapability == "AVAILABLE" }
        failed.set(true)
        compose.waitUntil(10_000) { last.get()?.scheduling == null }
        assertEquals(5, checkNotNull(last.get()).queues?.queuedIngress)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }

    @Test fun pauseStopsPollingAndResumeAndSettingsRevisionRefreshWithoutRecovery() {
        val owner = Owner()
        val reads = AtomicInteger()
        val pending = AtomicInteger(1)
        val last = AtomicReference<OrionDiagnosticsSnapshot?>()
        val revision = mutableLongStateOf(0)
        val reader = OrionDiagnosticsReader({ scheduling() },
            { reads.incrementAndGet(); TaskQueueDiagnostics(pending.get(), 0, 0, 0, 0, 0) })
        compose.activityRule.scenario.onActivity { activity ->
            owner.registry.currentState = Lifecycle.State.CREATED
            activity.setContent {
                OrionDiagnosticsObservation(owner.lifecycle, reader, Instant.EPOCH, revision.longValue, intervalMillis = 100) { last.set(it) }
            }
        }
        compose.waitForIdle()
        assertEquals(0, reads.get())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(10_000) { last.get()?.queues?.queuedIngress == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        val pausedReads = reads.get()
        assertFalse(CountDownLatch(1).await(300, TimeUnit.MILLISECONDS))
        assertEquals(pausedReads, reads.get())
        pending.set(2)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(10_000) { last.get()?.queues?.queuedIngress == 2 }
        pending.set(3)
        compose.runOnIdle { revision.longValue++ }
        compose.waitUntil(10_000) { last.get()?.queues?.queuedIngress == 3 }
        pending.set(4)
        compose.waitUntil(10_000) { last.get()?.queues?.queuedIngress == 4 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun scheduling() = OrionSchedulingDiagnostics(0, 0, 0, "AVAILABLE", true, false, "UNAVAILABLE", null, null)
}
