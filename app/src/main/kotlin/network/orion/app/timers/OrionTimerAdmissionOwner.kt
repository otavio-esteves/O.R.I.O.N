package network.orion.app.timers

import java.util.UUID
import network.orion.core.policy.tasks.OrionPolicyEvidence
import java.util.concurrent.CancellationException
import network.orion.core.commands.timers.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.ingress.StartupBarrier
import network.orion.core.policy.timers.*
import network.orion.core.skills.metadata.timers.OrionCreateTimerDescriptor

internal sealed interface OrionTimerAdmissionResult {
    class Admitted(val request: OrionCreateTimerAdmission) : OrionTimerAdmissionResult {
        override fun toString() = "OrionTimerAdmissionResult.Admitted(<redacted>)"
    }
    data class Invalid(val reason: CreateTimerRejection) : OrionTimerAdmissionResult
    data object Unavailable : OrionTimerAdmissionResult
}

/** Input captured at the actual MAIN/UI boundary; neither a confirmation nor execution authority. */
internal class OrionCreateTimerAdmission(
    val timer: ValidatedCreateTimer,
    val source: CreateTimerPolicyContextSource,
) {
    fun evaluate() = OrionCreateTimerPolicy(source).evaluate(timer)
    override fun toString() = "OrionCreateTimerAdmission(<redacted>)"
}

internal sealed interface OrionTimerStatusAdmissionResult {
    class Admitted(val request: OrionTimerStatusAdmission) : OrionTimerStatusAdmissionResult {
        override fun toString() = "OrionTimerStatusAdmissionResult.Admitted(<redacted>)"
    }
    data class Invalid(val reason: TimerStatusRejection) : OrionTimerStatusAdmissionResult
    data object Unavailable : OrionTimerStatusAdmissionResult
}

internal class OrionTimerStatusAdmission(val change: ValidatedTimerStatusChange, val source: TimerStatusPolicyContextSource) {
    fun evaluate() = OrionTimerStatusPolicy(source).evaluate(change)
    override fun toString() = "OrionTimerStatusAdmission(<redacted>)"
}

/**
 * MAIN-only owner. Only the trusted Activity's explicit user callback calls
 * captureCreateFromUser; producer IDs, Intent extras and arbitrary transcripts
 * never select origin. Each callback captures its own normalized input binding.
 *
 * Lifecycle changes advance a generation even for ABA. OS evidence is sampled
 * afresh; unobserved OS transitions are not an atomic snapshot or authority for
 * later execution. The executor must revalidate all current conditions separately.
 * No callback under this monitor acquires SQLite or the barrier admission monitor.
 */
internal class OrionTimerAdmissionOwner(
    private val role: OrionProcessRole,
    private val barrier: StartupBarrier,
    private val writeCapability: () -> OrionPolicyEvidence,
    private val devicePresence: () -> OrionPolicyEvidence,
) {
    private var current: UiChannel? = null
    private var revision = 0L
    private var uiGeneration = 0L
    private var lastEvidence: Evidence? = null

    @Synchronized fun openUiChannel(): UiChannel {
        advanceUi()
        return UiChannel().also { current = it }
    }

    /** Owned by one Activity; no Activity reference or user content is retained here. */
    inner class UiChannel internal constructor() {
        private var resumed = false
        private var focused = false
        private var closed = false

        fun onResumed(value: Boolean) = synchronized(this@OrionTimerAdmissionOwner) {
            if (!closed && resumed != value) { resumed = value; advanceUi() }
        }

        fun onWindowFocus(value: Boolean) = synchronized(this@OrionTimerAdmissionOwner) {
            if (!closed && focused != value) { focused = value; advanceUi() }
        }

        fun close() = synchronized(this@OrionTimerAdmissionOwner) {
            if (!closed) {
                closed = true
                if (current === this) current = null
                advanceUi()
            }
        }

        /** Called only from the in-app user gesture handler, never a generic command/Intent adapter. */
        fun captureCreateFromUser(command: CreateTimer): OrionTimerAdmissionResult = synchronized(this@OrionTimerAdmissionOwner) {
            if (!active() || role != OrionProcessRole.MAIN) return@synchronized OrionTimerAdmissionResult.Unavailable
            val present = try {
                devicePresence()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (_: Exception) {
                return@synchronized OrionTimerAdmissionResult.Unavailable
            }
            if (present != OrionPolicyEvidence.SATISFIED) return@synchronized OrionTimerAdmissionResult.Unavailable
            val prepared = OrionCreateTimerPreparer.prepare(command)
            if (prepared is CreateTimerPreparation.Rejected)
                return@synchronized OrionTimerAdmissionResult.Invalid(prepared.reason)
            val timer = (prepared as CreateTimerPreparation.Ready).timer
            val binding = CreateTimerPolicyInputBinding.from(timer)
            val admissionId = UUID.randomUUID()
            val admittedGeneration = uiGeneration
            val source = CreateTimerPolicyContextSource {
                synchronized(this@OrionTimerAdmissionOwner) {
                    // A later pause/focus loss/channel replacement cannot revive old authority.
                    if (!active() || uiGeneration != admittedGeneration) CreateTimerPolicyContextRead.Unavailable
                    else observe(admissionId, binding)
                }
            }
            OrionTimerAdmissionResult.Admitted(OrionCreateTimerAdmission(timer, source))
        }

        fun captureStatusFromUser(command: TimerStatusCommand): OrionTimerStatusAdmissionResult = synchronized(this@OrionTimerAdmissionOwner) {
            if (!active() || role != OrionProcessRole.MAIN) return@synchronized OrionTimerStatusAdmissionResult.Unavailable
            val present = try { devicePresence() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
            catch (_: Exception) { return@synchronized OrionTimerStatusAdmissionResult.Unavailable }
            if (present != OrionPolicyEvidence.SATISFIED) return@synchronized OrionTimerStatusAdmissionResult.Unavailable
            val prepared = OrionTimerStatusPreparer.prepare(command)
            if (prepared is TimerStatusPreparation.Rejected) return@synchronized OrionTimerStatusAdmissionResult.Invalid(prepared.reason)
            val change = (prepared as TimerStatusPreparation.Ready).change
            val binding = TimerStatusPolicyInputBinding.from(change)
            val id = UUID.randomUUID()
            val generation = uiGeneration
            val source = TimerStatusPolicyContextSource {
                synchronized(this@OrionTimerAdmissionOwner) {
                    if (!active() || uiGeneration != generation) TimerStatusPolicyContextRead.Unavailable
                    else observeStatus(id, binding)
                }
            }
            OrionTimerStatusAdmissionResult.Admitted(OrionTimerStatusAdmission(change, source))
        }

        fun captureDismissFromUser(): Boolean = synchronized(this@OrionTimerAdmissionOwner) {
            active() && role == OrionProcessRole.MAIN && devicePresence() == OrionPolicyEvidence.SATISFIED
        }

        internal fun activeForReplay() = active()
        private fun active() = !closed && current === this && resumed && focused
        override fun toString() = "OrionTimerUiChannel(<redacted>)"
    }

    @Synchronized fun presentForQueuedRequest(origin: OrionVerifiedTimerIngress): Boolean =
        origin.wire.operation == "DISMISS" && role == OrionProcessRole.MAIN && current?.activeForReplay() == true &&
            barrier.isOpen() && writeCapability() == OrionPolicyEvidence.SATISFIED && devicePresence() == OrionPolicyEvidence.SATISFIED

    /** Recovery revokes every previously sampled UI admission, including lifecycle ABA. */
    @Synchronized fun invalidateAdmissions() { advanceUi() }

    @Synchronized fun replayCreate(origin: OrionVerifiedTimerIngress): OrionCreateTimerAdmission? {
        val channel = current ?: return null
        if (!channel.activeForReplay() || origin.wire.operation != "CREATE") return null
        val prepared = OrionCreateTimerPreparer.prepare(origin.wire.create()) as? CreateTimerPreparation.Ready ?: return null
        val binding = CreateTimerPolicyInputBinding.from(prepared.timer)
        val id = UUID.randomUUID(); val generation = uiGeneration
        return OrionCreateTimerAdmission(prepared.timer, CreateTimerPolicyContextSource {
            synchronized(this@OrionTimerAdmissionOwner) {
                if (current !== channel || !channel.activeForReplay() || generation != uiGeneration)
                    CreateTimerPolicyContextRead.Unavailable else observe(id, binding)
            }
        })
    }

    @Synchronized fun replayStatus(origin: OrionVerifiedTimerIngress): OrionTimerStatusAdmission? {
        val channel = current ?: return null
        if (!channel.activeForReplay() || origin.wire.operation !in setOf("CANCEL")) return null
        val prepared = OrionTimerStatusPreparer.prepare(origin.wire.change()) as? TimerStatusPreparation.Ready ?: return null
        val binding = TimerStatusPolicyInputBinding.from(prepared.change)
        val id = UUID.randomUUID(); val generation = uiGeneration
        return OrionTimerStatusAdmission(prepared.change, TimerStatusPolicyContextSource {
            synchronized(this@OrionTimerAdmissionOwner) {
                if (current !== channel || !channel.activeForReplay() || generation != uiGeneration)
                    TimerStatusPolicyContextRead.Unavailable else observeStatus(id, binding)
            }
        })
    }

    private fun observe(id: UUID, binding: CreateTimerPolicyInputBinding): CreateTimerPolicyContextRead {
        val evidence = Evidence(barrier.isOpen(), writeCapability(), devicePresence())
        if (lastEvidence != evidence) { advance(); lastEvidence = evidence }
        return CreateTimerPolicyContextRead.Available(CreateTimerPolicyContext(
            id, binding, revision, role, CreateTimerAdmissionOrigin.EXPLICIT_USER_REQUEST,
            if (evidence.barrierOpen) OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED,
            evidence.presence, mapOf(OrionCreateTimerDescriptor.WRITE_CAPABILITY to evidence.capability),
        ))
    }

    private fun observeStatus(id: UUID, binding: TimerStatusPolicyInputBinding): TimerStatusPolicyContextRead {
        val evidence = Evidence(barrier.isOpen(), writeCapability(), devicePresence())
        if (lastEvidence != evidence) { advance(); lastEvidence = evidence }
        return TimerStatusPolicyContextRead.Available(TimerStatusPolicyContext(id, binding, revision, role,
            TimerStatusAdmissionOrigin.EXPLICIT_USER_REQUEST,
            if (evidence.barrierOpen) OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED,
            evidence.presence, mapOf(OrionCreateTimerDescriptor.WRITE_CAPABILITY to evidence.capability)))
    }

    private fun advance() { revision = Math.addExact(revision, 1) }
    private fun advanceUi() { uiGeneration = Math.addExact(uiGeneration, 1); advance() }
    private data class Evidence(val barrierOpen: Boolean, val capability: OrionPolicyEvidence, val presence: OrionPolicyEvidence)
}
