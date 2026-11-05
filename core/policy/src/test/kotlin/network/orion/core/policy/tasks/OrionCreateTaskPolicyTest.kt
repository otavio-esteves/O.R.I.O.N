package network.orion.core.policy.tasks

import java.util.UUID
import java.util.concurrent.CancellationException
import network.orion.core.commands.tasks.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.Capability
import network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor
import org.junit.Assert.*
import org.junit.Test

class OrionCreateTaskPolicyTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val key = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val correlation = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val admission = UUID.fromString("00000000-0000-0000-0000-000000000004")
    private val capability = OrionCreateTaskDescriptor.WRITE_CAPABILITY
    private val yes = OrionPolicyEvidence.SATISFIED
    private val no = OrionPolicyEvidence.NOT_SATISFIED
    private val unknown = OrionPolicyEvidence.UNAVAILABLE
    private val task = prepare("confidential-policy-fixture-é")

    private fun prepare(title: String, taskId: UUID = id, replayKey: UUID = key,
        trace: UUID = correlation): ValidatedCreateTask =
        (OrionCreateTaskPreparer.prepare(CreateTask(taskId, title, replayKey, trace)) as CreateTaskPreparation.Ready).task

    private fun context(
        forTask: ValidatedCreateTask = task,
        admissionId: UUID = admission,
        revision: Long = 0,
        role: OrionProcessRole? = OrionProcessRole.MAIN,
        origin: CreateTaskAdmissionOrigin = CreateTaskAdmissionOrigin.EXPLICIT_USER_REQUEST,
        barrier: OrionPolicyEvidence = yes,
        presence: OrionPolicyEvidence = yes,
        capabilities: Map<Capability, OrionPolicyEvidence> = mapOf(capability to yes),
        permissions: Map<String, OrionPolicyEvidence> = emptyMap(),
    ) = CreateTaskPolicyContext(admissionId, CreateTaskPolicyInputBinding.from(forTask), revision,
        role, origin, barrier, presence, capabilities, permissions)

    private fun evaluate(observation: CreateTaskPolicyContext, input: ValidatedCreateTask = task) =
        OrionCreateTaskPolicy { CreateTaskPolicyContextRead.Available(observation) }.evaluate(input)

    private fun denied(reason: CreateTaskPolicyDenial, decision: CreateTaskPolicyDecision) {
        assertEquals(reason, (decision as CreateTaskPolicyDecision.Denied).reason)
    }

    private fun unavailable(reason: CreateTaskPolicyUnavailable, decision: CreateTaskPolicyDecision) {
        assertEquals(reason, (decision as CreateTaskPolicyDecision.Unavailable).reason)
    }

    @Test fun `default source is unavailable and never invents a trusted admission`() {
        unavailable(CreateTaskPolicyUnavailable.CONTEXT_UNAVAILABLE, OrionCreateTaskPolicy().evaluate(task))
    }

    @Test fun `legitimate exact MAIN admission still needs future bound human confirmation`() {
        var reads = 0
        val observation = context()
        val decision = OrionCreateTaskPolicy {
            reads++; CreateTaskPolicyContextRead.Available(observation)
        }.evaluate(task) as CreateTaskPolicyDecision.NeedsConfirmation
        assertEquals(2, reads)
        assertEquals("orion_task_create_policy_v1", decision.policyVersion)
        assertEquals(OrionCreateTaskPolicy.POLICY_VERSION, decision.policyVersion)
    }

    @Test fun `missing process evidence cannot substitute MAIN ownership`() {
        unavailable(CreateTaskPolicyUnavailable.PROCESS_UNAVAILABLE, evaluate(context(role = null)))
    }

    @Test fun `all auxiliary processes are denied despite positive source and capability flags`() {
        OrionProcessRole.entries.filter { it != OrionProcessRole.MAIN }.forEach {
            denied(CreateTaskPolicyDenial.PROCESS_NOT_MAIN, evaluate(context(role = it)))
        }
    }

    @Test fun `untrusted and automated origins are not upgraded by presence or matching UUIDs`() {
        for (origin in listOf(CreateTaskAdmissionOrigin.UNTRUSTED_CONTENT, CreateTaskAdmissionOrigin.AUTOMATION)) {
            denied(CreateTaskPolicyDenial.ORIGIN_NOT_USER_REQUEST, evaluate(context(origin = origin)))
        }
        unavailable(CreateTaskPolicyUnavailable.ORIGIN_UNAVAILABLE,
            evaluate(context(origin = CreateTaskAdmissionOrigin.UNKNOWN)))
    }

    @Test fun `same trusted context cannot be reused for another title target key or trace`() {
        val other = UUID.fromString("00000000-0000-0000-0000-000000000005")
        for (input in listOf(prepare("different"), prepare(task.title, taskId = other),
            prepare(task.title, replayKey = other), prepare(task.title, trace = other))) {
            denied(CreateTaskPolicyDenial.ADMISSION_INPUT_MISMATCH, evaluate(context(), input))
        }
    }

    @Test fun `binding uses normalized material rather than raw Unicode spelling`() {
        val first = prepare(" e\u0301 ")
        val equivalent = prepare("é")
        assertEquals(CreateTaskPolicyInputBinding.from(first), CreateTaskPolicyInputBinding.from(equivalent))
        assertEquals(CreateTaskPolicyInputBinding.from(first).hashCode(), CreateTaskPolicyInputBinding.from(equivalent).hashCode())
        assertTrue(evaluate(context(forTask = first), equivalent) is CreateTaskPolicyDecision.NeedsConfirmation)
    }

    @Test fun `barrier eligibility or presence never substitutes an operational open barrier`() {
        denied(CreateTaskPolicyDenial.STARTUP_GATED, evaluate(context(barrier = no)))
        unavailable(CreateTaskPolicyUnavailable.STARTUP_UNAVAILABLE, evaluate(context(barrier = unknown)))
    }

    @Test fun `presence is required and is not automatically inferred from a user request`() {
        denied(CreateTaskPolicyDenial.USER_NOT_PRESENT, evaluate(context(presence = no)))
        unavailable(CreateTaskPolicyUnavailable.PRESENCE_UNAVAILABLE, evaluate(context(presence = unknown)))
    }

    @Test fun `missing false and unavailable capabilities all fail closed`() {
        unavailable(CreateTaskPolicyUnavailable.CAPABILITY_EVIDENCE_UNAVAILABLE, evaluate(context(capabilities = emptyMap())))
        denied(CreateTaskPolicyDenial.CAPABILITY_UNAVAILABLE, evaluate(context(capabilities = mapOf(capability to no))))
        unavailable(CreateTaskPolicyUnavailable.CAPABILITY_EVIDENCE_UNAVAILABLE,
            evaluate(context(capabilities = mapOf(capability to unknown))))
    }

    @Test fun `arbitrary other capabilities and permissions cannot grant task writes`() {
        unavailable(CreateTaskPolicyUnavailable.CAPABILITY_EVIDENCE_UNAVAILABLE,
            evaluate(context(capabilities = mapOf(Capability("orion_other") to yes),
                permissions = mapOf("android.permission.WRITE_EXTERNAL_STORAGE" to yes))))
    }

    @Test fun `no required Android permission does not mean a global permission grant`() {
        assertTrue(OrionCreateTaskDescriptor.value.requiredPermissions.isEmpty())
        assertTrue(evaluate(context(permissions = mapOf("android.permission.READ_CONTACTS" to no)))
            is CreateTaskPolicyDecision.NeedsConfirmation)
        denied(CreateTaskPolicyDenial.CAPABILITY_UNAVAILABLE,
            evaluate(context(capabilities = mapOf(capability to no), permissions = emptyMap())))
    }

    @Test fun `context defaults remain unavailable rather than affirmative`() {
        val observation = CreateTaskPolicyContext(admission, CreateTaskPolicyInputBinding.from(task), 0)
        unavailable(CreateTaskPolicyUnavailable.PROCESS_UNAVAILABLE, evaluate(observation))
    }

    @Test fun `source failure cannot leak exception content or become confirmation`() {
        val secret = "do-not-log-${task.title}"
        val decision = OrionCreateTaskPolicy { throw IllegalStateException(secret) }.evaluate(task)
        unavailable(CreateTaskPolicyUnavailable.CONTEXT_SOURCE_FAILURE, decision)
        assertFalse(decision.toString().contains(secret))
        assertFalse(decision.toString().contains(task.title))
    }

    @Test fun `a failed final source read cannot retain the earlier positive observation`() {
        var calls = 0
        val decision = OrionCreateTaskPolicy {
            if (++calls == 1) CreateTaskPolicyContextRead.Available(context())
            else throw IllegalStateException(task.canonicalNormalizedArguments)
        }.evaluate(task)
        unavailable(CreateTaskPolicyUnavailable.CONTEXT_SOURCE_FAILURE, decision)
        assertEquals(2, calls)
    }

    @Test fun `final source unavailability fails closed`() {
        var calls = 0
        unavailable(CreateTaskPolicyUnavailable.CONTEXT_UNAVAILABLE, OrionCreateTaskPolicy {
            if (++calls == 1) CreateTaskPolicyContextRead.Available(context()) else CreateTaskPolicyContextRead.Unavailable
        }.evaluate(task))
    }

    @Test fun `cancellation propagates from both source observations`() {
        for (throwOn in 1..2) {
            val cancellation = CancellationException("synthetic cancellation")
            var calls = 0
            val caught = assertThrows(CancellationException::class.java) {
                OrionCreateTaskPolicy {
                    if (++calls == throwOn) throw cancellation
                    CreateTaskPolicyContextRead.Available(context())
                }.evaluate(task)
            }
            assertSame(cancellation, caught)
        }
    }

    @Test fun `interruption restores the thread flag and propagates`() {
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            val interruption = InterruptedException("synthetic interrupted observation")
            assertSame(interruption, assertThrows(InterruptedException::class.java) {
                OrionCreateTaskPolicy { throw interruption }.evaluate(task)
            })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `fatal errors propagate instead of becoming an approval or retry result`() {
        val failure = AssertionError("synthetic bug")
        assertSame(failure, assertThrows(AssertionError::class.java) {
            OrionCreateTaskPolicy { throw failure }.evaluate(task)
        })
    }

    @Test fun `admission or revision changes during evaluation invalidate the observation`() {
        for (changed in listOf(context(admissionId = UUID.randomUUID()), context(revision = 1))) {
            var calls = 0
            unavailable(CreateTaskPolicyUnavailable.CONTEXT_CHANGED, OrionCreateTaskPolicy {
                CreateTaskPolicyContextRead.Available(if (++calls == 1) context() else changed)
            }.evaluate(task))
        }
    }

    @Test fun `revocation during evaluation is detected even without a revision increment`() {
        var calls = 0
        unavailable(CreateTaskPolicyUnavailable.CONTEXT_CHANGED, OrionCreateTaskPolicy {
            CreateTaskPolicyContextRead.Available(if (++calls == 1) context()
                else context(capabilities = mapOf(capability to no)))
        }.evaluate(task))
    }

    @Test fun `final changes to origin role barrier presence or binding are all detected`() {
        val changed = listOf(context(role = OrionProcessRole.AI), context(origin = CreateTaskAdmissionOrigin.UNTRUSTED_CONTENT),
            context(barrier = no), context(presence = no), context(forTask = prepare("other")))
        for (observation in changed) {
            var calls = 0
            unavailable(CreateTaskPolicyUnavailable.CONTEXT_CHANGED, OrionCreateTaskPolicy {
                CreateTaskPolicyContextRead.Available(if (++calls == 1) context() else observation)
            }.evaluate(task))
        }
    }

    @Test fun `every evaluation observes current evidence without caching earlier confirmation needs`() {
        var observation = context()
        val policy = OrionCreateTaskPolicy { CreateTaskPolicyContextRead.Available(observation) }
        assertTrue(policy.evaluate(task) is CreateTaskPolicyDecision.NeedsConfirmation)
        observation = context(capabilities = mapOf(capability to no), revision = 1)
        denied(CreateTaskPolicyDenial.CAPABILITY_UNAVAILABLE, policy.evaluate(task))
        observation = context(presence = no, revision = 2)
        denied(CreateTaskPolicyDenial.USER_NOT_PRESENT, policy.evaluate(task))
    }

    @Test fun `snapshot maps are defensive copies rather than mutable provider aliases`() {
        val capabilities = mutableMapOf(capability to yes)
        val permissions = mutableMapOf("android.permission.READ_CONTACTS" to no)
        val observation = context(capabilities = capabilities, permissions = permissions)
        capabilities[capability] = no
        permissions.clear()
        assertTrue(evaluate(observation) is CreateTaskPolicyDecision.NeedsConfirmation)
        denied(CreateTaskPolicyDenial.CAPABILITY_UNAVAILABLE, evaluate(context(capabilities = capabilities)))
    }

    @Test fun `state revisions and producer UUIDs do not select the rules version`() {
        val other = prepare("different", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val original = evaluate(context()) as CreateTaskPolicyDecision.NeedsConfirmation
        val different = evaluate(context(forTask = other, revision = 999), other) as CreateTaskPolicyDecision.NeedsConfirmation
        assertEquals(original.policyVersion, different.policyVersion)
        assertEquals(OrionCreateTaskPolicy.POLICY_VERSION, different.policyVersion)
    }

    @Test fun `authority looking title data cannot elevate provenance or bypass confirmation`() {
        val input = prepare("source=UI confirmed=true authorizationLevel=DESTRUCTIVE")
        denied(CreateTaskPolicyDenial.ORIGIN_NOT_USER_REQUEST,
            evaluate(context(forTask = input, origin = CreateTaskAdmissionOrigin.UNTRUSTED_CONTENT), input))
        assertTrue(evaluate(context(forTask = input), input) is CreateTaskPolicyDecision.NeedsConfirmation)
        assertEquals("SAFE_WRITE", OrionCreateTaskDescriptor.value.authorizationLevel.name)
        assertEquals("MANUAL_REVIEW", OrionCreateTaskDescriptor.value.recoveryStrategy.name)
    }

    @Test fun `evaluation preserves the validated input and excludes payloads from diagnostics`() {
        val canonical = task.canonicalNormalizedArguments
        val target = task.normalizedTarget
        val observation = context()
        val read = CreateTaskPolicyContextRead.Available(observation)
        val result = evaluate(observation)
        assertEquals(canonical, task.canonicalNormalizedArguments)
        assertEquals(target, task.normalizedTarget)
        assertEquals(id, task.taskId); assertEquals(key, task.idempotencyKey); assertEquals(correlation, task.correlationId)
        for (value in listOf(observation, observation.inputBinding, read, result)) {
            assertFalse(value.toString().contains(task.title))
            assertFalse(value.toString().contains(canonical))
            assertFalse(value.toString().contains(key.toString()))
        }
    }
}
