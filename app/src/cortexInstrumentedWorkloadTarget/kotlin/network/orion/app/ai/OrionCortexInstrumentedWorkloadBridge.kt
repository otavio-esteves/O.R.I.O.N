package network.orion.app.ai

import android.app.Application
import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import network.orion.app.BuildConfig
import network.orion.app.OrionApplication
import network.orion.app.OrionFoundationRuntime
import network.orion.core.time.AndroidOrionClock

/** Qualification-only fixed operation bridge. Technical IDs never select a model or grant authority. */
object OrionCortexInstrumentedWorkloadBridge {
    private const val TARGET = "network.orion.qualification"
    private val gate = AtomicBoolean()
    private var pinnedMain: OrionFoundationRuntime? = null
    private var pinnedOwner: OrionCortexRuntimeOwner? = null
    private var identity: OrionInstrumentedGraphIdentity? = null
    private var selected: OrionCortexInstrumentedWorkloadProgram? = null
    private val completedRuns = HashSet<String>()
    private data class Command(val operation: OrionInstrumentedOperation, val run: String, val fixture: String,
        val nonce: String?, val attempt: String?)

    @JvmStatic fun run(instrumentation: Instrumentation, arguments: Bundle): Bundle {
        val app = validate(instrumentation)
        val context = OrionRuntimeInstrumentationContext.fromInstrumentation(instrumentation) ?: error("WORKLOAD_CONTEXT_UNAVAILABLE")
        val command = command(arguments)
        check(gate.compareAndSet(false, true)) { "WORKLOAD_BUSY" }
        try { return runBlocking {
            val main = withContext(Dispatchers.Main.immediate) { app.mainRuntime } ?: error("CORE_UNAVAILABLE")
            check(pinnedMain == null || pinnedMain === main) { "CORE_IDENTITY_CHANGED" }
            val owner = pinnedOwner ?: main.cortexRuntime() ?: error("CORE_UNAVAILABLE")
            if (pinnedMain == null) {
                pinnedMain = main; pinnedOwner = owner; identity = OrionInstrumentedGraphIdentity(main, owner)
            }
            val graph = checkNotNull(identity)
            check(graph.matches(main, owner) && sameRuntime(app, main)) { "CORE_IDENTITY_CHANGED" }
            // A distinct diagnostic clock/phase. No original functional Clock read is added by a checkpoint.
            val diagnosticClock = AndroidOrionClock(app.contentResolver)
            fun reply(outcome: OrionInstrumentedOutcome, refusal: OrionInstrumentedRefusal = OrionInstrumentedRefusal.NONE,
                ownerCalled: Boolean? = false, execution: OrionInstrumentedExecutionState = selected?.scope?.state() ?: OrionInstrumentedExecutionState.IDLE,
                binding: OrionInstrumentedConfigurationBinding? = selected?.binding,
                observed: OrionInstrumentedResourceObservation? = null, encoded: OrionRuntimeEncodedCapsule? = null,
                failure: network.orion.ai.api.OrionInferenceFailure? = null,
                settlement: OrionInstrumentedSettlement = OrionInstrumentedSettlement.NO_OWNER_CALL): Bundle {
                val at = diagnosticClock.monotonicNow()
                check(at.bootSessionId != null) { "CLOCK_UNAVAILABLE" }
                val value = OrionInstrumentedCheckpoint(command.operation, command.run, command.fixture, command.attempt,
                    graph.nonce, at, outcome, refusal, failure, ownerCalled, execution, settlement, binding, observed)
                return OrionInstrumentedCheckpointFraming.frame(value, encoded, context) ?: error("WORKLOAD_FRAME_UNAVAILABLE")
            }
            when (command.operation) {
                OrionInstrumentedOperation.OPEN -> {
                    val previous = selected
                    if (previous != null && !previous.scope.closed()) return@runBlocking reply(OrionInstrumentedOutcome.BUSY)
                    if (command.run in completedRuns || completedRuns.size >= 32)
                        return@runBlocking reply(OrionInstrumentedOutcome.REFUSED, OrionInstrumentedRefusal.ALREADY_CONSUMED)
                    owner.preResourceReadiness().unavailable?.let {
                        return@runBlocking reply(OrionInstrumentedOutcome.REFUSED, refusal(it), binding = null)
                    }
                    val configuration = owner.selectRuntimeCaptureTemplate()
                    if (configuration is OrionCortexAttemptConfiguration.Refused)
                        return@runBlocking reply(OrionInstrumentedOutcome.REFUSED, OrionInstrumentedRefusal.valueOf(configuration.reason.name), binding = null)
                    val template = (configuration as OrionCortexAttemptConfiguration.Ready).template
                    val binding = owner.instrumentedConfigurationBinding(template)
                        ?: return@runBlocking reply(OrionInstrumentedOutcome.REFUSED, OrionInstrumentedRefusal.CONFIGURATION_UNAVAILABLE, binding = null)
                    val scope = OrionInstrumentedWorkloadScope(graph, command.run, command.fixture)
                    selected = OrionCortexInstrumentedWorkloadProgram(owner, scope, binding)
                    reply(OrionInstrumentedOutcome.OPENED, binding = binding)
                }
                OrionInstrumentedOperation.WORK, OrionInstrumentedOperation.BOOTSTRAP -> {
                    val actual = selected
                    if (actual == null || !actual.scope.matches(command.run, command.fixture, checkNotNull(command.nonce)))
                        return@runBlocking reply(OrionInstrumentedOutcome.REFUSED, OrionInstrumentedRefusal.FOREIGN_OWNERSHIP)
                    var original: OrionCortexRunResult? = null
                    try {
                        // No callback wrapper, chunk inspection or content retention.
                        val discard: (List<network.orion.ai.api.OrionInferenceChunk>) -> Unit = { _ -> }
                        original = if (command.operation == OrionInstrumentedOperation.BOOTSTRAP)
                            actual.executeBootstrap(command.run, command.fixture, checkNotNull(command.nonce), checkNotNull(command.attempt), discard)
                        else actual.executeWork(command.run, command.fixture, checkNotNull(command.nonce), checkNotNull(command.attempt), discard)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt(); throw interrupted
                    } catch (_: Throwable) {
                        // executeWork already rethrew the same original object. Metadata is a later manual phase.
                    }
                    if (!actual.lastClaimSucceeded)
                        return@runBlocking reply(OrionInstrumentedOutcome.REFUSED, OrionInstrumentedRefusal.ALREADY_CONSUMED)
                    var encoded = try { actual.export(context.qualified) } catch (_: Throwable) {
                        OrionRuntimeCapsuleEncoder.unavailable(OrionRuntimeExportFailure.SOURCE_UNAVAILABLE, witness = actual.witness())
                    }
                    val execution = actual.finishManual(encoded)
                    val ownerCalled = actual.witness()?.ownerCallInvoked
                    val unavailable = original as? OrionCortexRunResult.Unavailable
                    val status = (original as? OrionCortexRunResult.Completed)?.status
                    val terminal = status?.terminal
                    val outcome = when {
                        unavailable != null -> OrionInstrumentedOutcome.REFUSED
                        terminal != null -> OrionInstrumentedOutcome.valueOf(terminal.name)
                        ownerCalled == false -> OrionInstrumentedOutcome.REFUSED
                        else -> OrionInstrumentedOutcome.UNAVAILABLE
                    }
                    val refused = if (unavailable != null) refusal(unavailable.reason) else if (outcome == OrionInstrumentedOutcome.REFUSED)
                        when (actual.witness()?.failure) {
                            OrionRuntimeExportFailure.NO_QUALIFIED_PROFILE -> OrionInstrumentedRefusal.NO_QUALIFIED_PROFILE
                            OrionRuntimeExportFailure.CONFIGURATION_UNAVAILABLE -> OrionInstrumentedRefusal.CONFIGURATION_UNAVAILABLE
                            else -> OrionInstrumentedRefusal.SELECTION_UNAVAILABLE
                        } else OrionInstrumentedRefusal.NONE
                    var framed = reply(outcome, refused, ownerCalled, execution, encoded = encoded, failure = status?.failure, settlement = actual.settlement)
                    val finalFailure = if (!sameRuntime(app, main)) OrionRuntimeExportFailure.IDENTITY_CHANGED
                        else if (framed.containsKey(OrionRuntimeCapsuleParcelGuard.SOURCE_KEY)) actual.finalExportFailure(context.qualified) else null
                    if (finalFailure != null) {
                        encoded = OrionRuntimeCapsuleEncoder.unavailable(finalFailure, encoded.diagnosticReceipt, actual.witness())
                        framed = reply(outcome, refused, ownerCalled, execution, encoded = encoded, failure = status?.failure, settlement = actual.settlement)
                    }
                    framed
                }
                OrionInstrumentedOperation.OBSERVE -> {
                    val actual = selected
                    if (actual == null || !actual.scope.claim(command.operation, command.run, command.fixture, checkNotNull(command.nonce), null))
                        return@runBlocking reply(OrionInstrumentedOutcome.REFUSED, OrionInstrumentedRefusal.FOREIGN_OWNERSHIP)
                    try {
                        val observed = readInstrumentedWorkloadResources(app, owner, diagnosticClock, main)
                        check(sameRuntime(app, main) && graph.matches(main, owner)) { "CORE_IDENTITY_CHANGED" }
                        reply(OrionInstrumentedOutcome.OBSERVED, execution = OrionInstrumentedExecutionState.IDLE, observed = observed)
                    } finally { actual.scope.finish(OrionInstrumentedOperation.OBSERVE) }
                }
                OrionInstrumentedOperation.CLEANUP -> {
                    val actual = selected
                    if (actual == null || !actual.scope.matches(command.run, command.fixture, checkNotNull(command.nonce)))
                        return@runBlocking reply(OrionInstrumentedOutcome.REFUSED, OrionInstrumentedRefusal.FOREIGN_OWNERSHIP)
                    if (!actual.cleanup(command.run, command.fixture, checkNotNull(command.nonce)))
                        return@runBlocking reply(OrionInstrumentedOutcome.REFUSED, OrionInstrumentedRefusal.UNKNOWN_EXECUTION)
                    completedRuns.add(command.run)
                    reply(OrionInstrumentedOutcome.CLEANED, execution = OrionInstrumentedExecutionState.IDLE)
                }
            }
        } } finally { gate.set(false) }
    }
    private fun refusal(value: OrionCortexUnavailable): OrionInstrumentedRefusal = OrionInstrumentedRefusal.valueOf(value.name)
    private fun command(arguments: Bundle): Command {
        fun text(key: String): String = arguments.get(key) as? String ?: error("ARGUMENTS_REJECTED")
        val operation = OrionInstrumentedOperation.valueOf(text("orionOperation"))
        val expected = mutableSetOf("orionOperation", "orionRunId", "orionFixtureId")
        if (operation != OrionInstrumentedOperation.OPEN) expected.add("orionSessionId")
        if (operation in setOf(OrionInstrumentedOperation.WORK, OrionInstrumentedOperation.BOOTSTRAP)) expected.add("orionAttemptId")
        require(arguments.keySet() == expected) { "ARGUMENTS_REJECTED" }
        val run = text("orionRunId"); val fixture = text("orionFixtureId")
        val nonce = if (operation == OrionInstrumentedOperation.OPEN) null else text("orionSessionId")
        val attempt = if (operation in setOf(OrionInstrumentedOperation.WORK, OrionInstrumentedOperation.BOOTSTRAP)) text("orionAttemptId") else null
        require(listOfNotNull(run, fixture, nonce, attempt).all(OrionInstrumentedWorkloadScope::validId)) { "ARGUMENTS_REJECTED" }
        return Command(operation, run, fixture, nonce, attempt)
    }
    private fun validate(instrumentation: Instrumentation): OrionApplication {
        require(BuildConfig.ORION_CORTEX_INSTRUMENTED_WORKLOAD && Looper.myLooper() != Looper.getMainLooper())
        val target = instrumentation.targetContext
        val app = target.applicationContext as? OrionApplication ?: error("APPLICATION_REJECTED")
        require(target.packageName == TARGET && app.packageName == TARGET && Application.getProcessName() == TARGET &&
            app.applicationInfo.uid == Process.myUid() && Binder.getCallingUid() == Process.myUid() && !instrumentation.isProfiling &&
            app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        return app
    }
    private suspend fun sameRuntime(app: OrionApplication, main: OrionFoundationRuntime): Boolean =
        withContext(Dispatchers.Main.immediate) { app.mainRuntime === main }
}
