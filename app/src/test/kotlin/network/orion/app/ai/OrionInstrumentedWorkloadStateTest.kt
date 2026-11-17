package network.orion.app.ai

import java.util.UUID
import network.orion.ai.api.OrionInferencePriority
import network.orion.core.resources.InferenceModelProfile
import network.orion.core.resources.OrionInferenceResourcePlan
import org.junit.Assert.*
import org.junit.Test

/** Synthetic association/configuration controls. They confer no model, budget or platform approval. */
class OrionInstrumentedWorkloadStateTest {
    private fun id() = UUID.randomUUID().toString()
    private val hash = "a".repeat(64)
    private fun template(prompt: String = "SECRET_FIXTURE", threads: Int = 1) = OrionCortexAttemptTemplate(hash, 128,
        threads, 8, 1000, 0.1, OrionInferencePriority.INTERACTIVE, prompt, "")
    private fun plan() = OrionInferenceResourcePlan(InferenceModelProfile(hash, 1000), "SYNTHETIC_NOT_QUALIFIED",
        1, 128, 8, 0, 10000, 20000, 100, 20, 2)
    @Test fun ordinaryDebugArtifactHasWorkloadDisabled() { assertFalse(network.orion.app.BuildConfig.ORION_CORTEX_INSTRUMENTED_WORKLOAD) }
    @Test fun graphIdentityUsesActualReferencesAndNeverPrintsNonce() {
        val graph = Any(); val owner = Any(); val identity = OrionInstrumentedGraphIdentity(graph, owner)
        assertTrue(identity.matches(graph, owner)); assertFalse(identity.matches(Any(), owner)); assertFalse(identity.matches(graph, Any()))
        assertTrue(OrionInstrumentedWorkloadScope.validId(identity.nonce)); assertFalse(identity.toString().contains(identity.nonce))
    }
    @Test fun foreignNonceOrRunCannotClaimOrConsumeARealAttempt() {
        val identity = OrionInstrumentedGraphIdentity(Any(), Any()); val run = id(); val fixture = id(); val attempt = id()
        val scope = OrionInstrumentedWorkloadScope(identity, run, fixture)
        assertFalse(scope.claim(OrionInstrumentedOperation.WORK, run, fixture, id(), attempt))
        assertFalse(scope.claim(OrionInstrumentedOperation.WORK, id(), fixture, identity.nonce, attempt))
        assertTrue(scope.claim(OrionInstrumentedOperation.WORK, run, fixture, identity.nonce, attempt))
        assertEquals(OrionInstrumentedExecutionState.ACTIVE, scope.state())
        assertFalse(scope.claim(OrionInstrumentedOperation.OBSERVE, run, fixture, identity.nonce, null))
        assertTrue(scope.finish(OrionInstrumentedOperation.WORK))
        assertFalse(scope.claim(OrionInstrumentedOperation.WORK, run, fixture, identity.nonce, attempt))
    }
    @Test fun uncertainWorkCannotBeReplacedObservedOrCleaned() {
        val graph = OrionInstrumentedGraphIdentity(Any(), Any()); val scope = OrionInstrumentedWorkloadScope(graph, id(), id())
        assertTrue(scope.claim(OrionInstrumentedOperation.WORK, scope.runId, scope.fixtureId, graph.nonce, id()))
        assertTrue(scope.finish(OrionInstrumentedOperation.WORK, uncertain = true))
        for (operation in listOf(OrionInstrumentedOperation.WORK, OrionInstrumentedOperation.OBSERVE, OrionInstrumentedOperation.CLEANUP))
            assertFalse(scope.claim(operation, scope.runId, scope.fixtureId, graph.nonce, if (operation == OrionInstrumentedOperation.WORK) id() else null))
        assertEquals(OrionInstrumentedExecutionState.UNCERTAIN, scope.state())
    }
    @Test fun workAttemptsAreFiniteAndCleanupClosesOnlyThisScope() {
        val graph = OrionInstrumentedGraphIdentity(Any(), Any()); val scope = OrionInstrumentedWorkloadScope(graph, id(), id())
        repeat(OrionInstrumentedWorkloadScope.MAX_ATTEMPTS) {
            assertTrue(scope.claim(OrionInstrumentedOperation.WORK, scope.runId, scope.fixtureId, graph.nonce, id()))
            assertTrue(scope.finish(OrionInstrumentedOperation.WORK))
        }
        assertFalse(scope.claim(OrionInstrumentedOperation.WORK, scope.runId, scope.fixtureId, graph.nonce, id()))
        assertTrue(scope.claim(OrionInstrumentedOperation.CLEANUP, scope.runId, scope.fixtureId, graph.nonce, null))
        assertTrue(scope.finish(OrionInstrumentedOperation.CLEANUP)); assertTrue(scope.closed())
        assertFalse(scope.claim(OrionInstrumentedOperation.OBSERVE, scope.runId, scope.fixtureId, graph.nonce, null))
    }
    @Test fun bootstrapIsSingleUseBeforeWorkAndUnknownBootstrapNeverReopensScope() {
        val graph = OrionInstrumentedGraphIdentity(Any(), Any()); val scope = OrionInstrumentedWorkloadScope(graph, id(), id())
        val attempt = id()
        assertTrue(scope.claim(OrionInstrumentedOperation.BOOTSTRAP, scope.runId, scope.fixtureId, graph.nonce, attempt))
        assertFalse(scope.claim(OrionInstrumentedOperation.OBSERVE, scope.runId, scope.fixtureId, graph.nonce, null))
        assertTrue(scope.finish(OrionInstrumentedOperation.BOOTSTRAP))
        assertFalse(scope.claim(OrionInstrumentedOperation.BOOTSTRAP, scope.runId, scope.fixtureId, graph.nonce, id()))
        assertFalse(scope.claim(OrionInstrumentedOperation.WORK, scope.runId, scope.fixtureId, graph.nonce, attempt))
        assertTrue(scope.claim(OrionInstrumentedOperation.WORK, scope.runId, scope.fixtureId, graph.nonce, id()))
        assertTrue(scope.finish(OrionInstrumentedOperation.WORK))
        val other = OrionInstrumentedWorkloadScope(graph, id(), id())
        assertTrue(other.claim(OrionInstrumentedOperation.BOOTSTRAP, other.runId, other.fixtureId, graph.nonce, id()))
        assertTrue(other.finish(OrionInstrumentedOperation.BOOTSTRAP, uncertain = true))
        for (operation in listOf(OrionInstrumentedOperation.WORK, OrionInstrumentedOperation.BOOTSTRAP, OrionInstrumentedOperation.CLEANUP))
            assertFalse(other.claim(operation, other.runId, other.fixtureId, graph.nonce,
                if (operation == OrionInstrumentedOperation.CLEANUP) null else id()))
    }
    @Test fun descriptorUsesRealOrderedTechnicalScalarsWithoutReadingPromptOrGrantingAuthority() {
        val timing = OrionCortexResourceTiming(1000, 1000); val observed = OrionRuntimeCaptureObservationConfiguration(10, 1)
        val first = checkNotNull(deriveInstrumentedConfigurationBinding(listOf(plan()), timing, template(), observed))
        val second = checkNotNull(deriveInstrumentedConfigurationBinding(listOf(plan()), timing, template("OTHER_SECRET"), observed))
        assertEquals(first.configurationSha256, second.configurationSha256) // Text is bound separately by source/APK, never a content hash.
        val changed = checkNotNull(deriveInstrumentedConfigurationBinding(listOf(plan()), timing, template(threads = 2), observed))
        assertNotEquals(first.configurationSha256, changed.configurationSha256)
        assertEquals(hash, first.modelSha256); assertTrue(first.configurationSha256.matches(Regex("[0-9a-f]{64}")))
        assertFalse(first.toString().contains(hash)); assertFalse(first.toString().contains("SECRET"))
    }
    @Test fun diagnosticExitLimitsAndTheirExplicitAbsenceAreBoundIntoTheTechnicalDescriptor() {
        val timing = OrionCortexResourceTiming(1000, 1000); val observed = OrionRuntimeCaptureObservationConfiguration(10, 1)
        fun binding(exit: OrionQualificationExitConfiguration?) = checkNotNull(
            deriveInstrumentedConfigurationBinding(listOf(plan()), timing, template(), observed, exit)).configurationSha256
        val absent = binding(null)
        assertEquals(absent, checkNotNull(deriveInstrumentedConfigurationBinding(listOf(plan()), timing, template(), observed)).configurationSha256)
        val configured = binding(OrionQualificationExitConfiguration(10, 1000, 10000))
        assertNotEquals(absent, configured)
        for (changed in listOf(OrionQualificationExitConfiguration(11, 1000, 10000),
            OrionQualificationExitConfiguration(10, 1001, 10000), OrionQualificationExitConfiguration(10, 1000, 10001)))
            assertNotEquals(configured, binding(changed))
        for (values in listOf(Triple(0L, 1L, 1L), Triple(1L, 0L, 1L), Triple(1L, 1L, 0L))) {
            try { OrionQualificationExitConfiguration(values.first, values.second, values.third); fail("No zero implicit bound") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun missingConfigurationRemainsUnavailableInsteadOfHashingAnEmptyApproval() {
        val timing = OrionCortexResourceTiming(1000, 1000); val observed = OrionRuntimeCaptureObservationConfiguration(10, 1)
        assertNull(deriveInstrumentedConfigurationBinding(emptyList(), timing, template(), observed))
        assertNull(deriveInstrumentedConfigurationBinding(listOf(plan()), null, template(), observed))
        assertNull(deriveInstrumentedConfigurationBinding(listOf(plan()), timing, null, observed))
        assertNull(deriveInstrumentedConfigurationBinding(listOf(plan()), timing, template(), null))
    }
}
