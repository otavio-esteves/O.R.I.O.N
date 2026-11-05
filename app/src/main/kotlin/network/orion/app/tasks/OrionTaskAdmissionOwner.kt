package network.orion.app.tasks

import java.util.UUID
import java.util.concurrent.CancellationException
import network.orion.core.commands.tasks.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.ingress.StartupBarrier
import network.orion.core.policy.tasks.*
import network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor

internal sealed interface OrionTaskAdmissionResult {
    class Admitted(val request: OrionCreateTaskAdmission) : OrionTaskAdmissionResult {
        override fun toString() = "OrionTaskAdmissionResult.Admitted(<redacted>)"
    }
    data class Invalid(val reason: CreateTaskRejection) : OrionTaskAdmissionResult
    data object Unavailable : OrionTaskAdmissionResult
}

/** Input captured at the actual MAIN/UI boundary; neither a confirmation nor execution authority. */
internal class OrionCreateTaskAdmission(
    val task: ValidatedCreateTask,
    val source: CreateTaskPolicyContextSource,
) {
    fun evaluate() = OrionCreateTaskPolicy(source).evaluate(task)
    override fun toString() = "OrionCreateTaskAdmission(<redacted>)"
}

internal sealed interface OrionTaskStatusAdmissionResult {
    class Admitted(val request: OrionTaskStatusAdmission) : OrionTaskStatusAdmissionResult {
        override fun toString() = "OrionTaskStatusAdmissionResult.Admitted(<redacted>)"
    }
    data class Invalid(val reason: TaskStatusRejection) : OrionTaskStatusAdmissionResult
    data object Unavailable : OrionTaskStatusAdmissionResult
}

internal class OrionTaskStatusAdmission(val change: ValidatedTaskStatusChange, val source: TaskStatusPolicyContextSource) {
    fun evaluate() = OrionTaskStatusPolicy(source).evaluate(change)
    override fun toString() = "OrionTaskStatusAdmission(<redacted>)"
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
internal class OrionTaskAdmissionOwner(
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

        fun onResumed(value: Boolean) = synchronized(this@OrionTaskAdmissionOwner) {
            if (!closed && resumed != value) { resumed = value; advanceUi() }
        }

        fun onWindowFocus(value: Boolean) = synchronized(this@OrionTaskAdmissionOwner) {
            if (!closed && focused != value) { focused = value; advanceUi() }
        }

        fun close() = synchronized(this@OrionTaskAdmissionOwner) {
            if (!closed) {
                closed = true
                if (current === this) current = null
                advanceUi()
            }
        }

        /** Called only from the in-app user gesture handler, never a generic command/Intent adapter. */
        fun captureCreateFromUser(command: CreateTask): OrionTaskAdmissionResult = synchronized(this@OrionTaskAdmissionOwner) {
            if (!active() || role != OrionProcessRole.MAIN) return@synchronized OrionTaskAdmissionResult.Unavailable
            val present = try {
                devicePresence()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (_: Exception) {
                return@synchronized OrionTaskAdmissionResult.Unavailable
            }
            if (present != OrionPolicyEvidence.SATISFIED) return@synchronized OrionTaskAdmissionResult.Unavailable
            val prepared = OrionCreateTaskPreparer.prepare(command)
            if (prepared is CreateTaskPreparation.Rejected)
                return@synchronized OrionTaskAdmissionResult.Invalid(prepared.reason)
            val task = (prepared as CreateTaskPreparation.Ready).task
            val binding = CreateTaskPolicyInputBinding.from(task)
            val admissionId = UUID.randomUUID()
            val admittedGeneration = uiGeneration
            val source = CreateTaskPolicyContextSource {
                synchronized(this@OrionTaskAdmissionOwner) {
                    // A later pause/focus loss/channel replacement cannot revive old authority.
                    if (!active() || uiGeneration != admittedGeneration) CreateTaskPolicyContextRead.Unavailable
                    else observe(admissionId, binding)
                }
            }
            OrionTaskAdmissionResult.Admitted(OrionCreateTaskAdmission(task, source))
        }

        fun captureStatusFromUser(command: TaskStatusCommand): OrionTaskStatusAdmissionResult = synchronized(this@OrionTaskAdmissionOwner) {
            if (!active() || role != OrionProcessRole.MAIN) return@synchronized OrionTaskStatusAdmissionResult.Unavailable
            val present = try { devicePresence() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
            catch (_: Exception) { return@synchronized OrionTaskStatusAdmissionResult.Unavailable }
            if (present != OrionPolicyEvidence.SATISFIED) return@synchronized OrionTaskStatusAdmissionResult.Unavailable
            val prepared = OrionTaskStatusPreparer.prepare(command)
            if (prepared is TaskStatusPreparation.Rejected) return@synchronized OrionTaskStatusAdmissionResult.Invalid(prepared.reason)
            val change = (prepared as TaskStatusPreparation.Ready).change
            val binding = TaskStatusPolicyInputBinding.from(change)
            val id = UUID.randomUUID()
            val generation = uiGeneration
            val source = TaskStatusPolicyContextSource {
                synchronized(this@OrionTaskAdmissionOwner) {
                    if (!active() || uiGeneration != generation) TaskStatusPolicyContextRead.Unavailable
                    else observeStatus(id, binding)
                }
            }
            OrionTaskStatusAdmissionResult.Admitted(OrionTaskStatusAdmission(change, source))
        }

        fun captureDismissFromUser(): Boolean = synchronized(this@OrionTaskAdmissionOwner) {
            active() && role == OrionProcessRole.MAIN && devicePresence() == OrionPolicyEvidence.SATISFIED
        }

        internal fun activeForReplay() = active()
        private fun active() = !closed && current === this && resumed && focused
        override fun toString() = "OrionTaskUiChannel(<redacted>)"
    }

    @Synchronized fun presentForQueuedRequest(origin: OrionVerifiedTaskIngress): Boolean =
        origin.wire.operation == "DISMISS" && role == OrionProcessRole.MAIN && current?.activeForReplay() == true &&
            barrier.isOpen() && writeCapability() == OrionPolicyEvidence.SATISFIED && devicePresence() == OrionPolicyEvidence.SATISFIED

    /** Recovery revokes every previously sampled UI admission, including lifecycle ABA. */
    @Synchronized fun invalidateAdmissions() { advanceUi() }

    @Synchronized fun replayCreate(origin: OrionVerifiedTaskIngress): OrionCreateTaskAdmission? {
        val channel = current ?: return null
        if (!channel.activeForReplay() || origin.wire.operation != "CREATE") return null
        val prepared = OrionCreateTaskPreparer.prepare(origin.wire.create()) as? CreateTaskPreparation.Ready ?: return null
        val binding = CreateTaskPolicyInputBinding.from(prepared.task)
        val id = UUID.randomUUID(); val generation = uiGeneration
        return OrionCreateTaskAdmission(prepared.task, CreateTaskPolicyContextSource {
            synchronized(this@OrionTaskAdmissionOwner) {
                if (current !== channel || !channel.activeForReplay() || generation != uiGeneration)
                    CreateTaskPolicyContextRead.Unavailable else observe(id, binding)
            }
        })
    }

    @Synchronized fun replayStatus(origin: OrionVerifiedTaskIngress): OrionTaskStatusAdmission? {
        val channel = current ?: return null
        if (!channel.activeForReplay() || origin.wire.operation !in setOf("COMPLETE", "CANCEL")) return null
        val prepared = OrionTaskStatusPreparer.prepare(origin.wire.change()) as? TaskStatusPreparation.Ready ?: return null
        val binding = TaskStatusPolicyInputBinding.from(prepared.change)
        val id = UUID.randomUUID(); val generation = uiGeneration
        return OrionTaskStatusAdmission(prepared.change, TaskStatusPolicyContextSource {
            synchronized(this@OrionTaskAdmissionOwner) {
                if (current !== channel || !channel.activeForReplay() || generation != uiGeneration)
                    TaskStatusPolicyContextRead.Unavailable else observeStatus(id, binding)
            }
        })
    }

    private fun observe(id: UUID, binding: CreateTaskPolicyInputBinding): CreateTaskPolicyContextRead {
        val evidence = Evidence(barrier.isOpen(), writeCapability(), devicePresence())
        if (lastEvidence != evidence) { advance(); lastEvidence = evidence }
        return CreateTaskPolicyContextRead.Available(CreateTaskPolicyContext(
            id, binding, revision, role, CreateTaskAdmissionOrigin.EXPLICIT_USER_REQUEST,
            if (evidence.barrierOpen) OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED,
            evidence.presence, mapOf(OrionCreateTaskDescriptor.WRITE_CAPABILITY to evidence.capability),
        ))
    }

    private fun observeStatus(id: UUID, binding: TaskStatusPolicyInputBinding): TaskStatusPolicyContextRead {
        val evidence = Evidence(barrier.isOpen(), writeCapability(), devicePresence())
        if (lastEvidence != evidence) { advance(); lastEvidence = evidence }
        return TaskStatusPolicyContextRead.Available(TaskStatusPolicyContext(id, binding, revision, role,
            TaskStatusAdmissionOrigin.EXPLICIT_USER_REQUEST,
            if (evidence.barrierOpen) OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED,
            evidence.presence, mapOf(OrionCreateTaskDescriptor.WRITE_CAPABILITY to evidence.capability)))
    }

    private fun advance() { revision = Math.addExact(revision, 1) }
    private fun advanceUi() { uiGeneration = Math.addExact(uiGeneration, 1); advance() }
    private data class Evidence(val barrierOpen: Boolean, val capability: OrionPolicyEvidence, val presence: OrionPolicyEvidence)
}
