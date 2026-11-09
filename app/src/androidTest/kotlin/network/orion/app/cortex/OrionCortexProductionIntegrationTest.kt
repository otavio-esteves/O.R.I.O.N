package network.orion.app.cortex

import android.content.ComponentName
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.ai.api.OrionCortexHealth
import network.orion.app.OrionApplication
import network.orion.app.OrionFoundationRuntime
import network.orion.core.health.OrionHealthStatus
import network.orion.core.health.OrionSubsystem
import network.orion.core.resources.OrionResourceBlock
import network.orion.core.skills.metadata.memories.OrionCreateMemoryDescriptor
import network.orion.core.skills.metadata.reminders.OrionCreateReminderDescriptor
import network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor
import network.orion.core.skills.metadata.timers.OrionCreateTimerDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production-default integration only: no qualified plan, native load, permission change or domain-data deletion. */
@RunWith(AndroidJUnit4::class)
class OrionCortexProductionIntegrationTest {
    private val app: OrionApplication get() = ApplicationProvider.getApplicationContext()

    private suspend fun recovered(): OrionFoundationRuntime {
        val runtime = checkNotNull(app.mainRuntime)
        withTimeout(30_000) {
            runtime.recover()
            runtime.health.first { it.core.readiness.isStartupBarrierOpen }
        }
        assertTrue(runtime.taskStartupBarrier.isOpen())
        return runtime
    }

    @Test fun optionalInferenceLeavesSchemaSixAndDeterministicCapabilitiesUsable() = runBlocking {
        val runtime = recovered()
        val health = runtime.health.value
        assertEquals(6, runtime.databaseSchemaVersion)
        assertEquals(OrionHealthStatus.HEALTHY, health.subsystems[OrionSubsystem.DATABASE])
        assertEquals(OrionHealthStatus.HEALTHY, health.subsystems[OrionSubsystem.FIELD_KEYS])
        assertEquals(OrionHealthStatus.UNAVAILABLE, health.subsystems[OrionSubsystem.CORTEX])
        assertEquals(OrionHealthStatus.DEGRADED, health.subsystems[OrionSubsystem.RESOURCES])
        for (capability in listOf(OrionCreateTaskDescriptor.WRITE_CAPABILITY,
            OrionCreateReminderDescriptor.WRITE_CAPABILITY, OrionCreateTimerDescriptor.WRITE_CAPABILITY,
            OrionCreateMemoryDescriptor.WRITE_CAPABILITY)) {
            assertTrue(capability.id, runtime.capabilities.isAvailable(capability))
        }
    }

    @Test fun unqualifiedModelCannotBindLoadInferOrPublishCallbacksThroughProductionAccess() = runBlocking {
        val runtime = recovered()
        val access = checkNotNull(runtime.cortexAccess)
        val callbackCount = AtomicInteger()
        val callbackSeen = CountDownLatch(1)
        val modelId = "sha256:" + "f".repeat(64)
        val prompt = "orion_private_integration_prompt_${UUID.randomUUID()}"
        assertEquals(0, access.qualifiedModelPlanCount)
        assertEquals(OrionCortexHealth.DISCONNECTED, access.status())
        assertNull(access.loadRegistered(modelId) { callbackCount.incrementAndGet(); callbackSeen.countDown() })
        assertNull(access.infer(modelId, prompt, 1) { callbackCount.incrementAndGet(); callbackSeen.countDown() })
        assertFalse("A denied request published a callback", callbackSeen.await(250, TimeUnit.MILLISECONDS))
        assertEquals(0, callbackCount.get())
        assertEquals(OrionCortexHealth.DISCONNECTED, access.status())
        assertEquals(OrionHealthStatus.UNAVAILABLE, runtime.health.value.subsystems[OrionSubsystem.CORTEX])
        assertTrue(runtime.taskStartupBarrier.isOpen())
        assertTrue(runtime.capabilities.isAvailable(OrionCreateTaskDescriptor.WRITE_CAPABILITY))
    }

    @Suppress("DEPRECATION")
    @Test fun mergedManifestKeepsCortexPrivateInOrdinaryAiProcess() {
        val component = ComponentName(app.packageName, "network.orion.ai.service.OrionCortexService")
        val service = app.packageManager.getServiceInfo(component, 0)
        assertFalse(service.exported)
        assertEquals(app.packageName + ":ai", service.processName)
        assertEquals(0, service.flags and ServiceInfo.FLAG_ISOLATED_PROCESS)
        assertEquals(app.applicationInfo.uid, service.applicationInfo.uid)
    }

    @Test fun productionDiagnosticsExposeQualificationCountsAndCodesWithoutPromptOrModelPaths() = runBlocking {
        val runtime = recovered()
        val access = checkNotNull(runtime.cortexAccess)
        val prompt = "orion_private_diagnostics_prompt_${UUID.randomUUID()}"
        val modelId = "sha256:" + "e".repeat(64)
        assertNull(access.infer(modelId, prompt, 1) { fail("An unqualified request emitted output") })
        val diagnostics = runtime.inferenceDiagnostics()
        assertEquals(0, diagnostics.qualifiedModelPlans)
        assertNotNull(diagnostics.registeredModels)
        assertNotNull(diagnostics.structurallyReadyModels)
        assertTrue(checkNotNull(diagnostics.registeredModels) >= checkNotNull(diagnostics.structurallyReadyModels))
        assertEquals(OrionCortexHealth.DISCONNECTED.name, diagnostics.cortexState)
        assertEquals(OrionResourceBlock.MODEL_UNQUALIFIED.name, diagnostics.resourceBlock)
        val rendered = diagnostics.toString()
        for (privateValue in listOf(prompt, modelId, app.filesDir.absolutePath, app.noBackupFilesDir.absolutePath))
            assertFalse("Inference diagnostics exposed private request/storage data", rendered.contains(privateValue))
    }
}
