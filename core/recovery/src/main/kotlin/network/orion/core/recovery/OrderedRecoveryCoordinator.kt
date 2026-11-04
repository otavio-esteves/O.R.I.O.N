package network.orion.core.recovery

import java.util.concurrent.CancellationException
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.ingress.StartupRecoveryCompletion

/** Exact pre-release order from Architecture §49. Steps are mandatory, even when explicitly no-op for a phase. */
enum class RecoveryStage {
    MINIMAL_LOGGING, OPEN_DATABASE, MIGRATIONS, STRUCTURAL_INVARIANTS, CONFIGURATION,
    PREVIOUS_EXIT_SIGNALS, GATE_INGRESS, DOMAIN_EVENT_OUTBOX, ACTION_OUTBOX, REMINDERS,
    UNIQUE_WORKERS, CAPABILITIES_AND_PERMISSIONS, EPHEMERAL_STATE, RESOURCE_GOVERNOR,
}

enum class RecoveryRunOutcome { RELEASED, BLOCKED, FAILED, SUPERSEDED }

/**
 * Synchronous IO orchestration primitive, not the F4 production recovery graph.
 * All hooks run on the caller's IO dispatcher. No action execution is permitted
 * in hooks. Production wiring must supply actual reconciliation implementations.
 */
class OrderedRecoveryCoordinator(
    private val barrier: StartupBarrier,
    steps: Map<RecoveryStage, () -> Unit>,
    private val calculateAndPublishHealth: () -> CoreReadiness,
    private val openIngressAndDrain: () -> Unit,
) {
    private val steps = steps.toMap()
    init { require(steps.keys == RecoveryStage.entries.toSet()) { "All recovery stages must be explicit." } }

    @Synchronized fun recover(): RecoveryRunOutcome {
        val ticket = barrier.beginRecovery()
        return try {
            RecoveryStage.entries.forEach { steps.getValue(it).invoke() }
            when (barrier.completeRecovery(ticket, calculateAndPublishHealth, openIngressAndDrain)) {
                StartupRecoveryCompletion.RELEASED -> RecoveryRunOutcome.RELEASED
                StartupRecoveryCompletion.BLOCKED -> RecoveryRunOutcome.BLOCKED
                StartupRecoveryCompletion.SUPERSEDED -> RecoveryRunOutcome.SUPERSEDED
            }
        } catch (cancelled: CancellationException) {
            barrier.abortRecovery(ticket)
            throw cancelled
        } catch (interrupted: InterruptedException) {
            barrier.abortRecovery(ticket)
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            // Technical caller records the failing subsystem; exception text may contain user content.
            if (barrier.abortRecovery(ticket)) RecoveryRunOutcome.FAILED else RecoveryRunOutcome.SUPERSEDED
        }
    }
}
