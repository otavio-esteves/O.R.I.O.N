package network.orion.app.ai

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import network.orion.ai.api.*
import network.orion.core.resources.*
import network.orion.core.time.AndroidOrionClock
import network.orion.app.resources.OrionCortexResourceFactBuffer
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.models.OrionModelImportCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No usable model or resource calibration: proves gates precede storage and binding. */
@RunWith(AndroidJUnit4::class)
class OrionCortexOwnerDefaultTest {
    @Test fun runtimeCaptureDoesNotActivateOrReadAnyClockPastTheEmptyProfileGate() = fixture { context, db, importer, bindings ->
        val reads = AtomicInteger()
        val clock = object : network.orion.core.time.OrionClock {
            override fun monotonicNow() = network.orion.core.time.MonotonicInstant(network.orion.core.time.BootSessionId(1), 1000)
                .also { reads.incrementAndGet() }
            override fun wallNow() = java.time.Instant.EPOCH
        }
        val owner = OrionCortexRuntimeOwner(context, importer, db, clock, coreReady = { error("Early profile gate must remain lazy") })
        val capture = checkNotNull(owner.beginRuntimeCapture())
        val request = OrionInferenceRequest("private-request", 1, "private-model", "SECRET_PROMPT", "SECRET_CONTEXT", 8, 0.0,
            OrionInferencePriority.INTERACTIVE, network.orion.core.time.MonotonicInstant(network.orion.core.time.BootSessionId(1), 1000).deadlineAfter(1000), "private-label")
        try { runBlocking {
            val original = owner.runCaptured(capture, request.modelId, request, 128, 1) { error("Unexpected output") }
            assertEquals(OrionCortexRunResult.Unavailable(OrionCortexUnavailable.NO_QUALIFIED_PROFILE), original)
            val receipt = checkNotNull(owner.readRuntimeCapture(capture))
            assertFalse(receipt.identityKnown); assertNull(receipt.facts); assertNull(receipt.lifecycle)
            assertEquals(false, receipt.originalOwnerEntered); assertTrue(receipt.actualTurnEntered != true)
            assertEquals(0, reads.get()); assertEquals(0, bindings.get())
            assertFalse(receipt.runtimeEvidenceV1Complete); assertFalse(receipt.productionInferenceApproved)
        } } finally { owner.close() }
    }

    @Test fun observedNoProfileNeverRetainsCallerIdentityContentOrCreatesAnAttempt() = fixture { context, db, importer, bindings ->
        val clock = AndroidOrionClock(context.contentResolver)
        val observations = OrionCortexCycleObserver()
        val owner = OrionCortexRuntimeOwner(context, importer, db, clock,
            coreReady = { error("Early plans gate must remain lazy") }, cycleObservations = observations)
        val token = checkNotNull(owner.beginCycleObservation())
        val input = OrionInferenceRequest("SECRET_CALLER_ID", 1, "SECRET_MODEL", "SECRET_PROMPT", "SECRET_CONTEXT", 8, 0.0,
            OrionInferencePriority.INTERACTIVE, clock.monotonicNow().deadlineAfter(10_000), "SECRET_LABEL")
        try { runBlocking {
            val result = owner.runObserved(token, "SECRET_MODEL", input, 128, 1) { error("Unexpected output") }
            val receipt = checkNotNull(owner.readCycleObservation(token))
            assertSame(result, receipt.originalResult)
            assertEquals(OrionCortexRunResult.Unavailable(OrionCortexUnavailable.NO_QUALIFIED_PROFILE), result)
            assertEquals(OrionCycleState.NO_ATTEMPT, receipt.state)
            assertNull(receipt.trustedModelSha256); assertNull(receipt.nativeMetrics); assertNull(receipt.stream)
            assertNull(receipt.unload); assertTrue(receipt.resources.isEmpty())
            assertFalse(receipt.toString().contains("SECRET")); assertFalse(token.toString().contains("SECRET"))
            assertFalse(receipt.actualTurnEntered == true); assertFalse(receipt.inferenceAccepted == true)
            assertTrue(owner.releaseCycleObservation(token))
        } } finally { owner.close() }
        assertEquals(0, bindings.get())
    }

    @Test fun observedOriginalCoreErrorKeepsIdentityAndDoesNotBecomeFailedCycle() = fixture { context, db, importer, bindings ->
        val clock = AndroidOrionClock(context.contentResolver)
        val observations = OrionCortexCycleObserver()
        val original = AssertionError("Original Core predicate failure")
        val plan = OrionInferenceResourcePlan(InferenceModelProfile(HASH, 100), "OBSERVATION_GATE_FIXTURE", 1, 128, 8, 0,
            10_000, 20_000, 100, 20, 2)
        val owner = OrionCortexRuntimeOwner(context, importer, db, clock, listOf(plan), OrionCortexResourceTiming(1000, 1000),
            coreReady = { throw original }, cycleObservations = observations)
        val token = checkNotNull(owner.beginCycleObservation())
        try { runBlocking {
            val seen = try { owner.runObserved(token, HASH, request(clock), 128, 1) { }; null } catch (failure: Throwable) { failure }
            assertSame(original, seen)
            val receipt = checkNotNull(owner.readCycleObservation(token))
            assertEquals(OrionCycleThrown.OTHER, receipt.thrown); assertNull(receipt.originalResult)
            assertNull(receipt.nativeMetrics); assertNull(receipt.stream); assertTrue(receipt.resources.isEmpty())
        } } finally { owner.close() }
        assertEquals(0, bindings.get())
    }

    @Test fun earlyRefusalsAndReadOnlyQueryNeverFabricateResourceEvaluations() = fixture { context, db, importer, bindings ->
        val clock = AndroidOrionClock(context.contentResolver)
        val facts = OrionCortexResourceFactBuffer()
        val plan = OrionInferenceResourcePlan(InferenceModelProfile(HASH, 100), "FACT_GATE_FIXTURE", 1, 128, 8, 0,
            10_000, 20_000, 100, 20, 2) // Mechanical negative control, never passed to a workload.
        val owners = listOf(
            OrionCortexRuntimeOwner(context, importer, db, clock, coreReady = { error("Plans must stay lazy") }, resourceFacts = facts)
                to OrionCortexUnavailable.NO_QUALIFIED_PROFILE,
            OrionCortexRuntimeOwner(context, importer, db, clock, listOf(plan), coreReady = { error("Timing must stay lazy") }, resourceFacts = facts)
                to OrionCortexUnavailable.RESOURCE_CONFIGURATION_UNAVAILABLE,
            OrionCortexRuntimeOwner(context, importer, db, clock, listOf(plan), OrionCortexResourceTiming(1000, 1000),
                coreReady = { false }, resourceFacts = facts) to OrionCortexUnavailable.CORE_NOT_READY,
        )
        try { runBlocking {
            for ((owner, reason) in owners) {
                assertEquals(reason, owner.preResourceReadiness().unavailable)
                assertEquals(OrionCortexRunResult.Unavailable(reason),
                    owner.run(HASH, request(clock), 128, 1) { error("Unexpected output") })
            }
        } } finally { owners.forEach { it.first.close() } }
        val batch = facts.drain()
        assertTrue(batch.facts.isEmpty())
        assertEquals(0L, batch.droppedAtLeast); assertEquals(0L, batch.recordingFailuresAtLeast)
        assertEquals(0, bindings.get())
    }

    @Test fun defaultProfileAndClosedOwnerNeverAccessDatabaseInventoryOrBinder() = fixture { context, db, importer, bindings ->
        val clock = AndroidOrionClock(context.contentResolver)
        val owner = OrionCortexRuntimeOwner(context, importer, db, clock)
        val request = request(clock)
        runBlocking {
            assertEquals(OrionCortexPreResourceReadiness.NO_QUALIFIED_PROFILE, owner.preResourceReadiness())
            assertEquals(OrionCortexRunResult.Unavailable(OrionCortexUnavailable.NO_QUALIFIED_PROFILE),
                owner.run(HASH, request, 128, 1) { error("Unexpected inference") })
            owner.close()
            assertEquals(OrionCortexPreResourceReadiness.CLOSED, owner.preResourceReadiness())
            assertEquals(OrionCortexRunResult.Unavailable(OrionCortexUnavailable.CLOSED),
                owner.run(HASH, request, 128, 1) { error("Unexpected inference") })
        }
        assertEquals(0, bindings.get())
    }

    @Test fun qualifiedFixtureStillRequiresCoreReadinessBeforeInventoryStorageOrBind() = fixture { context, db, importer, bindings ->
        val clock = AndroidOrionClock(context.contentResolver)
        // Mechanical gate fixture only; no inference can pass the default-false Core gate.
        val plan = OrionInferenceResourcePlan(InferenceModelProfile(HASH, 100), "OWNER_GATE_FIXTURE", 1, 128, 8, 0,
            10_000, 20_000, 100, 20, 2)
        val owner = OrionCortexRuntimeOwner(context, importer, db, clock, listOf(plan), OrionCortexResourceTiming(1000, 1000))
        try { runBlocking {
            assertEquals(OrionCortexPreResourceReadiness.CORE_NOT_READY, owner.preResourceReadiness())
            assertEquals(OrionCortexRunResult.Unavailable(OrionCortexUnavailable.CORE_NOT_READY),
                owner.run(HASH, request(clock), 128, 1) { error("Unexpected inference") })
        } } finally { owner.close() }
        assertEquals(0, bindings.get())
    }

    @Test fun diagnosticNeverAdvancesPastPreResourcePredicatesEvenWithConfiguredFixture() = fixture { context, db, importer, bindings ->
        val clock = AndroidOrionClock(context.contentResolver)
        // Existing mechanical gate fixture, never an approved device profile or executed load.
        val plan = OrionInferenceResourcePlan(InferenceModelProfile(HASH, 100), "READINESS_GATE_FIXTURE", 1, 128, 8, 0,
            10_000, 20_000, 100, 20, 2)
        val missingTiming = OrionCortexRuntimeOwner(context, importer, db, clock, listOf(plan),
            coreReady = { error("Core readiness must remain lazy when timing is absent") })
        try {
            assertEquals(OrionCortexPreResourceReadiness.RESOURCE_CONFIGURATION_UNAVAILABLE, missingTiming.preResourceReadiness())
        } finally { missingTiming.close() }
        val coreReads = AtomicInteger()
        val configured = OrionCortexRuntimeOwner(context, importer, db, clock, listOf(plan), OrionCortexResourceTiming(1000, 1000),
            coreReady = { coreReads.incrementAndGet(); true })
        try {
            assertEquals(OrionCortexPreResourceReadiness.DECISION_NOT_EVALUATED, configured.preResourceReadiness())
            assertEquals(1, coreReads.get())
        } finally { configured.close() }
        assertEquals(0, bindings.get())
    }

    @Test fun expiredRequestAndItsReplayNeverAccessInventoryDatabaseOrBinder() = fixture { context, db, importer, bindings ->
        val clock = AndroidOrionClock(context.contentResolver)
        val plan = OrionInferenceResourcePlan(InferenceModelProfile(HASH, 100), "DEADLINE_FIXTURE", 1, 128, 8, 0,
            10_000, 20_000, 100, 20, 2)
        val owner = OrionCortexRuntimeOwner(context, importer, db, clock, listOf(plan),
            OrionCortexResourceTiming(1000, 1000), coreReady = { true })
        val expired = OrionInferenceRequest(owner.newRequestId(), 1, HASH, "expired", "", 8, 0.0,
            OrionInferencePriority.INTERACTIVE, clock.monotonicNow().deadlineAfter(0), "deadline-fixture")
        try { runBlocking {
            repeat(2) {
                val result = owner.run(HASH, expired, 128, 1) { error("Expired output") } as OrionCortexRunResult.Completed
                assertEquals(OrionInferenceTerminal.TIMED_OUT, result.status.terminal)
            }
            val conflict = OrionInferenceRequest(expired.requestId, 1, HASH, "changed", "", 8, 0.0,
                expired.priority, expired.deadline, "deadline-fixture")
            assertTrue(owner.run(HASH, conflict, 128, 1) { error("Conflict output") } is OrionCortexRunResult.Unavailable)
        } } finally { owner.close() }
        assertEquals(0, bindings.get())
    }

    @Test fun invalidBootRequestFailsBeforeInventoryDatabaseOrBinder() = fixture { context, db, importer, bindings ->
        val clock = AndroidOrionClock(context.contentResolver)
        val plan = OrionInferenceResourcePlan(InferenceModelProfile(HASH, 100), "BOOT_FIXTURE", 1, 128, 8, 0,
            10_000, 20_000, 100, 20, 2)
        val owner = OrionCortexRuntimeOwner(context, importer, db, clock, listOf(plan),
            OrionCortexResourceTiming(1000, 1000), coreReady = { true })
        val deadline = network.orion.core.time.MonotonicInstant(null, 0).deadlineAfter(10_000)
        try { runBlocking {
            val request = OrionInferenceRequest(owner.newRequestId(), 1, HASH, "invalid boot", "", 8, 0.0,
                OrionInferencePriority.INTERACTIVE, deadline, "boot-fixture")
            val result = owner.run(HASH, request, 128, 1) { error("Invalid boot output") } as OrionCortexRunResult.Completed
            assertEquals(OrionInferenceTerminal.FAILED, result.status.terminal)
            assertEquals(OrionInferenceFailure.INVALID_TIME_REFERENCE, result.status.failure)
        } } finally { owner.close() }
        assertEquals(0, bindings.get())
    }

    private fun request(clock: AndroidOrionClock) = OrionInferenceRequest(UUID.randomUUID().toString(), 1, HASH,
        "fixture", "", 8, 0.0, OrionInferencePriority.INTERACTIVE, clock.monotonicNow().deadlineAfter(10_000),
        UUID.randomUUID().toString())

    private fun fixture(block: (Context, OrionDatabase, OrionModelImportCoordinator, AtomicInteger) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "orion_owner_gate_${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val bindings = AtomicInteger()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
            override fun getPackageManager(): PackageManager = throw AssertionError("Inventory accessed before readiness gate")
            override fun bindService(intent: Intent, owner: ServiceConnection, flags: Int): Boolean {
                bindings.incrementAndGet(); throw AssertionError("Unexpected Cortex bind")
            }
        }
        val name = "orion_owner_gate_${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(app, OrionDatabase::class.java, name).build()
        val importer = OrionModelImportCoordinator.open(context, db, AndroidOrionClock(context.contentResolver), 1024, 0)
        db.close() // Any accidental query would fail instead of silently creating a database.
        try { block(context, db, importer, bindings) }
        finally { app.deleteDatabase(name); root.deleteRecursively() }
    }

    companion object { private val HASH = "a".repeat(64) }
}
