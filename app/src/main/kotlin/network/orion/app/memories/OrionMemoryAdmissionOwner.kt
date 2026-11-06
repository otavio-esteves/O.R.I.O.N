package network.orion.app.memories

import network.orion.core.policy.tasks.OrionPolicyEvidence
import java.util.UUID
import java.util.concurrent.CancellationException
import network.orion.core.commands.memories.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.ingress.StartupBarrier
import network.orion.core.policy.memories.*
import network.orion.core.skills.metadata.memories.OrionCreateMemoryDescriptor

internal sealed interface OrionMemoryAdmissionResult {
    class Admitted(val request: OrionCreateMemoryAdmission) : OrionMemoryAdmissionResult {
        override fun toString() = "OrionMemoryAdmissionResult.Admitted(<redacted>)"
    }
    data class Invalid(val reason: CreateMemoryRejection) : OrionMemoryAdmissionResult
    data object Unavailable : OrionMemoryAdmissionResult
}

/** Input captured at the actual MAIN/UI boundary; neither a confirmation nor execution authority. */
internal class OrionCreateMemoryAdmission(
    val memory: ValidatedCreateMemory,
    val source: CreateMemoryPolicyContextSource,
) {
    fun evaluate() = OrionCreateMemoryPolicy(source).evaluate(memory)
    override fun toString() = "OrionCreateMemoryAdmission(<redacted>)"
}

internal sealed interface OrionMemoryStatusAdmissionResult {
    class Admitted(val request: OrionMemoryStatusAdmission) : OrionMemoryStatusAdmissionResult {
        override fun toString() = "OrionMemoryStatusAdmissionResult.Admitted(<redacted>)"
    }
    data class Invalid(val reason: MemoryStatusRejection) : OrionMemoryStatusAdmissionResult
    data object Unavailable : OrionMemoryStatusAdmissionResult
}

internal class OrionMemoryStatusAdmission(val change: ValidatedMemoryStatusChange, val source: MemoryStatusPolicyContextSource) {
    fun evaluate() = OrionMemoryStatusPolicy(source).evaluate(change)
    override fun toString() = "OrionMemoryStatusAdmission(<redacted>)"
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
internal class OrionMemoryAdmissionOwner(
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

        fun onResumed(value: Boolean) = synchronized(this@OrionMemoryAdmissionOwner) {
            if (!closed && resumed != value) { resumed = value; advanceUi() }
        }

        fun onWindowFocus(value: Boolean) = synchronized(this@OrionMemoryAdmissionOwner) {
            if (!closed && focused != value) { focused = value; advanceUi() }
        }

        fun close() = synchronized(this@OrionMemoryAdmissionOwner) {
            if (!closed) {
                closed = true
                if (current === this) current = null
                advanceUi()
            }
        }

        /** Called only from the in-app user gesture handler, never a generic command/Intent adapter. */
        fun captureCreateFromUser(command: CreateMemory): OrionMemoryAdmissionResult = synchronized(this@OrionMemoryAdmissionOwner) {
            if (!active() || role != OrionProcessRole.MAIN) return@synchronized OrionMemoryAdmissionResult.Unavailable
            val present = try {
                devicePresence()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (_: Exception) {
                return@synchronized OrionMemoryAdmissionResult.Unavailable
            }
            if (present != OrionPolicyEvidence.SATISFIED) return@synchronized OrionMemoryAdmissionResult.Unavailable
            val prepared = OrionCreateMemoryPreparer.prepare(command)
            if (prepared is CreateMemoryPreparation.Rejected)
                return@synchronized OrionMemoryAdmissionResult.Invalid(prepared.reason)
            val memory = (prepared as CreateMemoryPreparation.Ready).memory
            val binding = CreateMemoryPolicyInputBinding.from(memory)
            val admissionId = UUID.randomUUID()
            val admittedGeneration = uiGeneration
            val source = CreateMemoryPolicyContextSource {
                synchronized(this@OrionMemoryAdmissionOwner) {
                    // A later pause/focus loss/channel replacement cannot revive old authority.
                    if (!active() || uiGeneration != admittedGeneration) CreateMemoryPolicyContextRead.Unavailable
                    else observe(admissionId, binding)
                }
            }
            OrionMemoryAdmissionResult.Admitted(OrionCreateMemoryAdmission(memory, source))
        }

        fun captureStatusFromUser(command: MemoryStatusCommand): OrionMemoryStatusAdmissionResult = synchronized(this@OrionMemoryAdmissionOwner) {
            if (!active() || role != OrionProcessRole.MAIN) return@synchronized OrionMemoryStatusAdmissionResult.Unavailable
            val present = try { devicePresence() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
            catch (_: Exception) { return@synchronized OrionMemoryStatusAdmissionResult.Unavailable }
            if (present != OrionPolicyEvidence.SATISFIED) return@synchronized OrionMemoryStatusAdmissionResult.Unavailable
            val prepared = OrionMemoryStatusPreparer.prepare(command)
            if (prepared is MemoryStatusPreparation.Rejected) return@synchronized OrionMemoryStatusAdmissionResult.Invalid(prepared.reason)
            val change = (prepared as MemoryStatusPreparation.Ready).change
            val binding = MemoryStatusPolicyInputBinding.from(change)
            val id = UUID.randomUUID()
            val generation = uiGeneration
            val source = MemoryStatusPolicyContextSource {
                synchronized(this@OrionMemoryAdmissionOwner) {
                    if (!active() || uiGeneration != generation) MemoryStatusPolicyContextRead.Unavailable
                    else observeStatus(id, binding)
                }
            }
            OrionMemoryStatusAdmissionResult.Admitted(OrionMemoryStatusAdmission(change, source))
        }

        fun captureDismissFromUser(): Boolean = synchronized(this@OrionMemoryAdmissionOwner) {
            active() && role == OrionProcessRole.MAIN && devicePresence() == OrionPolicyEvidence.SATISFIED
        }

        internal fun activeForReplay() = active()
        private fun active() = !closed && current === this && resumed && focused
        override fun toString() = "OrionMemoryUiChannel(<redacted>)"
    }

    @Synchronized fun presentForQueuedRequest(origin: OrionVerifiedMemoryIngress): Boolean =
        origin.wire.operation == "DISMISS" && role == OrionProcessRole.MAIN && current?.activeForReplay() == true &&
            barrier.isOpen() && writeCapability() == OrionPolicyEvidence.SATISFIED && devicePresence() == OrionPolicyEvidence.SATISFIED

    /** Recovery revokes every previously sampled UI admission, including lifecycle ABA. */
    @Synchronized fun invalidateAdmissions() { advanceUi() }

    @Synchronized fun replayCreate(origin: OrionVerifiedMemoryIngress): OrionCreateMemoryAdmission? {
        val channel = current ?: return null
        if (!channel.activeForReplay() || origin.wire.operation != "CREATE") return null
        val prepared = OrionCreateMemoryPreparer.prepare(origin.wire.create()) as? CreateMemoryPreparation.Ready ?: return null
        val binding = CreateMemoryPolicyInputBinding.from(prepared.memory)
        val id = UUID.randomUUID(); val generation = uiGeneration
        return OrionCreateMemoryAdmission(prepared.memory, CreateMemoryPolicyContextSource {
            synchronized(this@OrionMemoryAdmissionOwner) {
                if (current !== channel || !channel.activeForReplay() || generation != uiGeneration)
                    CreateMemoryPolicyContextRead.Unavailable else observe(id, binding)
            }
        })
    }

    @Synchronized fun replayStatus(origin: OrionVerifiedMemoryIngress): OrionMemoryStatusAdmission? {
        val channel = current ?: return null
        if (!channel.activeForReplay() || origin.wire.operation !in setOf("DELETE", "DELETE")) return null
        val prepared = OrionMemoryStatusPreparer.prepare(origin.wire.change()) as? MemoryStatusPreparation.Ready ?: return null
        val binding = MemoryStatusPolicyInputBinding.from(prepared.change)
        val id = UUID.randomUUID(); val generation = uiGeneration
        return OrionMemoryStatusAdmission(prepared.change, MemoryStatusPolicyContextSource {
            synchronized(this@OrionMemoryAdmissionOwner) {
                if (current !== channel || !channel.activeForReplay() || generation != uiGeneration)
                    MemoryStatusPolicyContextRead.Unavailable else observeStatus(id, binding)
            }
        })
    }

    private fun observe(id: UUID, binding: CreateMemoryPolicyInputBinding): CreateMemoryPolicyContextRead {
        val evidence = Evidence(barrier.isOpen(), writeCapability(), devicePresence())
        if (lastEvidence != evidence) { advance(); lastEvidence = evidence }
        return CreateMemoryPolicyContextRead.Available(CreateMemoryPolicyContext(
            id, binding, revision, role, CreateMemoryAdmissionOrigin.EXPLICIT_USER_REQUEST,
            if (evidence.barrierOpen) OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED,
            evidence.presence, mapOf(OrionCreateMemoryDescriptor.WRITE_CAPABILITY to evidence.capability),
        ))
    }

    private fun observeStatus(id: UUID, binding: MemoryStatusPolicyInputBinding): MemoryStatusPolicyContextRead {
        val evidence = Evidence(barrier.isOpen(), writeCapability(), devicePresence())
        if (lastEvidence != evidence) { advance(); lastEvidence = evidence }
        return MemoryStatusPolicyContextRead.Available(MemoryStatusPolicyContext(id, binding, revision, role,
            MemoryStatusAdmissionOrigin.EXPLICIT_USER_REQUEST,
            if (evidence.barrierOpen) OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED,
            evidence.presence, mapOf(OrionCreateMemoryDescriptor.WRITE_CAPABILITY to evidence.capability)))
    }

    private fun advance() { revision = Math.addExact(revision, 1) }
    private fun advanceUi() { uiGeneration = Math.addExact(uiGeneration, 1); advance() }
    private data class Evidence(val barrierOpen: Boolean, val capability: OrionPolicyEvidence, val presence: OrionPolicyEvidence)
}
