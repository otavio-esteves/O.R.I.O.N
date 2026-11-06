package network.orion.app.reminders

import java.util.UUID
import java.util.concurrent.CancellationException
import network.orion.core.commands.reminders.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.ingress.StartupBarrier
import network.orion.core.policy.reminders.*
import network.orion.core.skills.metadata.reminders.OrionCreateReminderDescriptor

internal sealed interface OrionReminderAdmissionResult {
    class Admitted(val request: OrionCreateReminderAdmission) : OrionReminderAdmissionResult {
        override fun toString() = "OrionReminderAdmissionResult.Admitted(<redacted>)"
    }
    data class Invalid(val reason: CreateReminderRejection) : OrionReminderAdmissionResult
    data object Unavailable : OrionReminderAdmissionResult
}

/** Input captured at the actual MAIN/UI boundary; neither a confirmation nor execution authority. */
internal class OrionCreateReminderAdmission(
    val reminder: ValidatedCreateReminder,
    val source: CreateReminderPolicyContextSource,
) {
    fun evaluate() = OrionCreateReminderPolicy(source).evaluate(reminder)
    override fun toString() = "OrionCreateReminderAdmission(<redacted>)"
}

internal sealed interface OrionReminderStatusAdmissionResult {
    class Admitted(val request: OrionReminderStatusAdmission) : OrionReminderStatusAdmissionResult {
        override fun toString() = "OrionReminderStatusAdmissionResult.Admitted(<redacted>)"
    }
    data class Invalid(val reason: ReminderStatusRejection) : OrionReminderStatusAdmissionResult
    data object Unavailable : OrionReminderStatusAdmissionResult
}

internal class OrionReminderStatusAdmission(val change: ValidatedReminderStatusChange, val source: ReminderStatusPolicyContextSource) {
    fun evaluate() = OrionReminderStatusPolicy(source).evaluate(change)
    override fun toString() = "OrionReminderStatusAdmission(<redacted>)"
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
internal class OrionReminderAdmissionOwner(
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

        fun onResumed(value: Boolean) = synchronized(this@OrionReminderAdmissionOwner) {
            if (!closed && resumed != value) { resumed = value; advanceUi() }
        }

        fun onWindowFocus(value: Boolean) = synchronized(this@OrionReminderAdmissionOwner) {
            if (!closed && focused != value) { focused = value; advanceUi() }
        }

        fun close() = synchronized(this@OrionReminderAdmissionOwner) {
            if (!closed) {
                closed = true
                if (current === this) current = null
                advanceUi()
            }
        }

        /** Called only from the in-app user gesture handler, never a generic command/Intent adapter. */
        fun captureCreateFromUser(command: CreateReminder): OrionReminderAdmissionResult = synchronized(this@OrionReminderAdmissionOwner) {
            if (!active() || role != OrionProcessRole.MAIN) return@synchronized OrionReminderAdmissionResult.Unavailable
            val present = try {
                devicePresence()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (_: Exception) {
                return@synchronized OrionReminderAdmissionResult.Unavailable
            }
            if (present != OrionPolicyEvidence.SATISFIED) return@synchronized OrionReminderAdmissionResult.Unavailable
            val prepared = OrionCreateReminderPreparer.prepare(command)
            if (prepared is CreateReminderPreparation.Rejected)
                return@synchronized OrionReminderAdmissionResult.Invalid(prepared.reason)
            val reminder = (prepared as CreateReminderPreparation.Ready).reminder
            val binding = CreateReminderPolicyInputBinding.from(reminder)
            val admissionId = UUID.randomUUID()
            val admittedGeneration = uiGeneration
            val source = CreateReminderPolicyContextSource {
                synchronized(this@OrionReminderAdmissionOwner) {
                    // A later pause/focus loss/channel replacement cannot revive old authority.
                    if (!active() || uiGeneration != admittedGeneration) CreateReminderPolicyContextRead.Unavailable
                    else observe(admissionId, binding)
                }
            }
            OrionReminderAdmissionResult.Admitted(OrionCreateReminderAdmission(reminder, source))
        }

        fun captureStatusFromUser(command: ReminderStatusCommand): OrionReminderStatusAdmissionResult = synchronized(this@OrionReminderAdmissionOwner) {
            if (!active() || role != OrionProcessRole.MAIN) return@synchronized OrionReminderStatusAdmissionResult.Unavailable
            val present = try { devicePresence() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
            catch (_: Exception) { return@synchronized OrionReminderStatusAdmissionResult.Unavailable }
            if (present != OrionPolicyEvidence.SATISFIED) return@synchronized OrionReminderStatusAdmissionResult.Unavailable
            val prepared = OrionReminderStatusPreparer.prepare(command)
            if (prepared is ReminderStatusPreparation.Rejected) return@synchronized OrionReminderStatusAdmissionResult.Invalid(prepared.reason)
            val change = (prepared as ReminderStatusPreparation.Ready).change
            val binding = ReminderStatusPolicyInputBinding.from(change)
            val id = UUID.randomUUID()
            val generation = uiGeneration
            val source = ReminderStatusPolicyContextSource {
                synchronized(this@OrionReminderAdmissionOwner) {
                    if (!active() || uiGeneration != generation) ReminderStatusPolicyContextRead.Unavailable
                    else observeStatus(id, binding)
                }
            }
            OrionReminderStatusAdmissionResult.Admitted(OrionReminderStatusAdmission(change, source))
        }

        fun captureDismissFromUser(): Boolean = synchronized(this@OrionReminderAdmissionOwner) {
            active() && role == OrionProcessRole.MAIN && devicePresence() == OrionPolicyEvidence.SATISFIED
        }

        internal fun activeForReplay() = active()
        private fun active() = !closed && current === this && resumed && focused
        override fun toString() = "OrionReminderUiChannel(<redacted>)"
    }

    @Synchronized fun presentForQueuedRequest(origin: OrionVerifiedReminderIngress): Boolean =
        origin.wire.operation == "DISMISS" && role == OrionProcessRole.MAIN && current?.activeForReplay() == true &&
            barrier.isOpen() && writeCapability() == OrionPolicyEvidence.SATISFIED && devicePresence() == OrionPolicyEvidence.SATISFIED

    /** Recovery revokes every previously sampled UI admission, including lifecycle ABA. */
    @Synchronized fun invalidateAdmissions() { advanceUi() }

    @Synchronized fun replayCreate(origin: OrionVerifiedReminderIngress): OrionCreateReminderAdmission? {
        val channel = current ?: return null
        if (!channel.activeForReplay() || origin.wire.operation != "CREATE") return null
        val prepared = OrionCreateReminderPreparer.prepare(origin.wire.create()) as? CreateReminderPreparation.Ready ?: return null
        val binding = CreateReminderPolicyInputBinding.from(prepared.reminder)
        val id = UUID.randomUUID(); val generation = uiGeneration
        return OrionCreateReminderAdmission(prepared.reminder, CreateReminderPolicyContextSource {
            synchronized(this@OrionReminderAdmissionOwner) {
                if (current !== channel || !channel.activeForReplay() || generation != uiGeneration)
                    CreateReminderPolicyContextRead.Unavailable else observe(id, binding)
            }
        })
    }

    @Synchronized fun replayStatus(origin: OrionVerifiedReminderIngress): OrionReminderStatusAdmission? {
        val channel = current ?: return null
        if (!channel.activeForReplay() || origin.wire.operation !in setOf("SNOOZE", "CANCEL")) return null
        val prepared = OrionReminderStatusPreparer.prepare(origin.wire.change()) as? ReminderStatusPreparation.Ready ?: return null
        val binding = ReminderStatusPolicyInputBinding.from(prepared.change)
        val id = UUID.randomUUID(); val generation = uiGeneration
        return OrionReminderStatusAdmission(prepared.change, ReminderStatusPolicyContextSource {
            synchronized(this@OrionReminderAdmissionOwner) {
                if (current !== channel || !channel.activeForReplay() || generation != uiGeneration)
                    ReminderStatusPolicyContextRead.Unavailable else observeStatus(id, binding)
            }
        })
    }

    private fun observe(id: UUID, binding: CreateReminderPolicyInputBinding): CreateReminderPolicyContextRead {
        val evidence = Evidence(barrier.isOpen(), writeCapability(), devicePresence())
        if (lastEvidence != evidence) { advance(); lastEvidence = evidence }
        return CreateReminderPolicyContextRead.Available(CreateReminderPolicyContext(
            id, binding, revision, role, CreateReminderAdmissionOrigin.EXPLICIT_USER_REQUEST,
            if (evidence.barrierOpen) OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED,
            evidence.presence, mapOf(OrionCreateReminderDescriptor.WRITE_CAPABILITY to evidence.capability),
        ))
    }

    private fun observeStatus(id: UUID, binding: ReminderStatusPolicyInputBinding): ReminderStatusPolicyContextRead {
        val evidence = Evidence(barrier.isOpen(), writeCapability(), devicePresence())
        if (lastEvidence != evidence) { advance(); lastEvidence = evidence }
        return ReminderStatusPolicyContextRead.Available(ReminderStatusPolicyContext(id, binding, revision, role,
            ReminderStatusAdmissionOrigin.EXPLICIT_USER_REQUEST,
            if (evidence.barrierOpen) OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED,
            evidence.presence, mapOf(OrionCreateReminderDescriptor.WRITE_CAPABILITY to evidence.capability)))
    }

    private fun advance() { revision = Math.addExact(revision, 1) }
    private fun advanceUi() { uiGeneration = Math.addExact(uiGeneration, 1); advance() }
    private data class Evidence(val barrierOpen: Boolean, val capability: OrionPolicyEvidence, val presence: OrionPolicyEvidence)
}
