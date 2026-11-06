package network.orion.core.policy.memories

import network.orion.core.policy.tasks.OrionPolicyEvidence
import java.util.UUID
import java.util.concurrent.CancellationException
import network.orion.core.commands.memories.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.Capability
import network.orion.core.skills.metadata.memories.OrionCreateMemoryDescriptor
import org.junit.Assert.*
import org.junit.Test

class OrionCreateMemoryPolicyTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val key = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val correlation = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val admission = UUID.fromString("00000000-0000-0000-0000-000000000004")
    private val capability = OrionCreateMemoryDescriptor.WRITE_CAPABILITY
    private val yes = OrionPolicyEvidence.SATISFIED
    private val no = OrionPolicyEvidence.NOT_SATISFIED
    private val unknown = OrionPolicyEvidence.UNAVAILABLE
    private val memory = prepare("confidential-policy-fixture-é")

    private fun prepare(content: String, memoryId: UUID = id, replayKey: UUID = key,
        trace: UUID = correlation): ValidatedCreateMemory =
        (OrionCreateMemoryPreparer.prepare(CreateMemory(memoryId, content, replayKey, trace)) as CreateMemoryPreparation.Ready).memory

    private fun context(
        forMemory: ValidatedCreateMemory = memory,
        admissionId: UUID = admission,
        revision: Long = 0,
        role: OrionProcessRole? = OrionProcessRole.MAIN,
        origin: CreateMemoryAdmissionOrigin = CreateMemoryAdmissionOrigin.EXPLICIT_USER_REQUEST,
        barrier: OrionPolicyEvidence = yes,
        presence: OrionPolicyEvidence = yes,
        capabilities: Map<Capability, OrionPolicyEvidence> = mapOf(capability to yes),
        permissions: Map<String, OrionPolicyEvidence> = emptyMap(),
    ) = CreateMemoryPolicyContext(admissionId, CreateMemoryPolicyInputBinding.from(forMemory), revision,
        role, origin, barrier, presence, capabilities, permissions)

    private fun evaluate(observation: CreateMemoryPolicyContext, input: ValidatedCreateMemory = memory) =
        OrionCreateMemoryPolicy { CreateMemoryPolicyContextRead.Available(observation) }.evaluate(input)

    private fun denied(reason: CreateMemoryPolicyDenial, decision: CreateMemoryPolicyDecision) {
        assertEquals(reason, (decision as CreateMemoryPolicyDecision.Denied).reason)
    }

    private fun unavailable(reason: CreateMemoryPolicyUnavailable, decision: CreateMemoryPolicyDecision) {
        assertEquals(reason, (decision as CreateMemoryPolicyDecision.Unavailable).reason)
    }

    @Test fun `default source is unavailable and never invents a trusted admission`() {
        unavailable(CreateMemoryPolicyUnavailable.CONTEXT_UNAVAILABLE, OrionCreateMemoryPolicy().evaluate(memory))
    }

    @Test fun `legitimate exact MAIN admission still needs future bound human confirmation`() {
        var reads = 0
        val observation = context()
        val decision = OrionCreateMemoryPolicy {
            reads++; CreateMemoryPolicyContextRead.Available(observation)
        }.evaluate(memory) as CreateMemoryPolicyDecision.NeedsConfirmation
        assertEquals(2, reads)
        assertEquals("orion_memory_create_policy_v1", decision.policyVersion)
        assertEquals(OrionCreateMemoryPolicy.POLICY_VERSION, decision.policyVersion)
    }

    @Test fun `missing process evidence cannot substitute MAIN ownership`() {
        unavailable(CreateMemoryPolicyUnavailable.PROCESS_UNAVAILABLE, evaluate(context(role = null)))
    }

    @Test fun `all auxiliary processes are denied despite positive source and capability flags`() {
        OrionProcessRole.entries.filter { it != OrionProcessRole.MAIN }.forEach {
            denied(CreateMemoryPolicyDenial.PROCESS_NOT_MAIN, evaluate(context(role = it)))
        }
    }

    @Test fun `untrusted and automated origins are not upgraded by presence or matching UUIDs`() {
        for (origin in listOf(CreateMemoryAdmissionOrigin.UNTRUSTED_CONTENT, CreateMemoryAdmissionOrigin.AUTOMATION)) {
            denied(CreateMemoryPolicyDenial.ORIGIN_NOT_USER_REQUEST, evaluate(context(origin = origin)))
        }
        unavailable(CreateMemoryPolicyUnavailable.ORIGIN_UNAVAILABLE,
            evaluate(context(origin = CreateMemoryAdmissionOrigin.UNKNOWN)))
    }

    @Test fun `same trusted context cannot be reused for another content target key or trace`() {
        val other = UUID.fromString("00000000-0000-0000-0000-000000000005")
        for (input in listOf(prepare("different"), prepare(memory.content, memoryId = other),
            prepare(memory.content, replayKey = other), prepare(memory.content, trace = other))) {
            denied(CreateMemoryPolicyDenial.ADMISSION_INPUT_MISMATCH, evaluate(context(), input))
        }
    }

    @Test fun `binding uses normalized material rather than raw Unicode spelling`() {
        val first = prepare(" e\u0301 ")
        val equivalent = prepare("é")
        assertEquals(CreateMemoryPolicyInputBinding.from(first), CreateMemoryPolicyInputBinding.from(equivalent))
        assertEquals(CreateMemoryPolicyInputBinding.from(first).hashCode(), CreateMemoryPolicyInputBinding.from(equivalent).hashCode())
        assertTrue(evaluate(context(forMemory = first), equivalent) is CreateMemoryPolicyDecision.NeedsConfirmation)
    }

    @Test fun `barrier eligibility or presence never substitutes an operational open barrier`() {
        denied(CreateMemoryPolicyDenial.STARTUP_GATED, evaluate(context(barrier = no)))
        unavailable(CreateMemoryPolicyUnavailable.STARTUP_UNAVAILABLE, evaluate(context(barrier = unknown)))
    }

    @Test fun `presence is required and is not automatically inferred from a user request`() {
        denied(CreateMemoryPolicyDenial.USER_NOT_PRESENT, evaluate(context(presence = no)))
        unavailable(CreateMemoryPolicyUnavailable.PRESENCE_UNAVAILABLE, evaluate(context(presence = unknown)))
    }

    @Test fun `missing false and unavailable capabilities all fail closed`() {
        unavailable(CreateMemoryPolicyUnavailable.CAPABILITY_EVIDENCE_UNAVAILABLE, evaluate(context(capabilities = emptyMap())))
        denied(CreateMemoryPolicyDenial.CAPABILITY_UNAVAILABLE, evaluate(context(capabilities = mapOf(capability to no))))
        unavailable(CreateMemoryPolicyUnavailable.CAPABILITY_EVIDENCE_UNAVAILABLE,
            evaluate(context(capabilities = mapOf(capability to unknown))))
    }

    @Test fun `arbitrary other capabilities and permissions cannot grant memory writes`() {
        unavailable(CreateMemoryPolicyUnavailable.CAPABILITY_EVIDENCE_UNAVAILABLE,
            evaluate(context(capabilities = mapOf(Capability("orion_other") to yes),
                permissions = mapOf("android.permission.WRITE_EXTERNAL_STORAGE" to yes))))
    }

    @Test fun `no required Android permission does not mean a global permission grant`() {
        assertTrue(OrionCreateMemoryDescriptor.value.requiredPermissions.isEmpty())
        assertTrue(evaluate(context(permissions = mapOf("android.permission.READ_CONTACTS" to no)))
            is CreateMemoryPolicyDecision.NeedsConfirmation)
        denied(CreateMemoryPolicyDenial.CAPABILITY_UNAVAILABLE,
            evaluate(context(capabilities = mapOf(capability to no), permissions = emptyMap())))
    }

    @Test fun `context defaults remain unavailable rather than affirmative`() {
        val observation = CreateMemoryPolicyContext(admission, CreateMemoryPolicyInputBinding.from(memory), 0)
        unavailable(CreateMemoryPolicyUnavailable.PROCESS_UNAVAILABLE, evaluate(observation))
    }

    @Test fun `source failure cannot leak exception content or become confirmation`() {
        val secret = "do-not-log-${memory.content}"
        val decision = OrionCreateMemoryPolicy { throw IllegalStateException(secret) }.evaluate(memory)
        unavailable(CreateMemoryPolicyUnavailable.CONTEXT_SOURCE_FAILURE, decision)
        assertFalse(decision.toString().contains(secret))
        assertFalse(decision.toString().contains(memory.content))
    }

    @Test fun `a failed final source read cannot retain the earlier positive observation`() {
        var calls = 0
        val decision = OrionCreateMemoryPolicy {
            if (++calls == 1) CreateMemoryPolicyContextRead.Available(context())
            else throw IllegalStateException(memory.canonicalNormalizedArguments)
        }.evaluate(memory)
        unavailable(CreateMemoryPolicyUnavailable.CONTEXT_SOURCE_FAILURE, decision)
        assertEquals(2, calls)
    }

    @Test fun `final source unavailability fails closed`() {
        var calls = 0
        unavailable(CreateMemoryPolicyUnavailable.CONTEXT_UNAVAILABLE, OrionCreateMemoryPolicy {
            if (++calls == 1) CreateMemoryPolicyContextRead.Available(context()) else CreateMemoryPolicyContextRead.Unavailable
        }.evaluate(memory))
    }

    @Test fun `cancellation propagates from both source observations`() {
        for (throwOn in 1..2) {
            val cancellation = CancellationException("synthetic cancellation")
            var calls = 0
            val caught = assertThrows(CancellationException::class.java) {
                OrionCreateMemoryPolicy {
                    if (++calls == throwOn) throw cancellation
                    CreateMemoryPolicyContextRead.Available(context())
                }.evaluate(memory)
            }
            assertSame(cancellation, caught)
        }
    }

    @Test fun `interruption restores the thread flag and propagates`() {
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            val interruption = InterruptedException("synthetic interrupted observation")
            assertSame(interruption, assertThrows(InterruptedException::class.java) {
                OrionCreateMemoryPolicy { throw interruption }.evaluate(memory)
            })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `fatal errors propagate instead of becoming an approval or retry result`() {
        val failure = AssertionError("synthetic bug")
        assertSame(failure, assertThrows(AssertionError::class.java) {
            OrionCreateMemoryPolicy { throw failure }.evaluate(memory)
        })
    }

    @Test fun `admission or revision changes during evaluation invalidate the observation`() {
        for (changed in listOf(context(admissionId = UUID.randomUUID()), context(revision = 1))) {
            var calls = 0
            unavailable(CreateMemoryPolicyUnavailable.CONTEXT_CHANGED, OrionCreateMemoryPolicy {
                CreateMemoryPolicyContextRead.Available(if (++calls == 1) context() else changed)
            }.evaluate(memory))
        }
    }

    @Test fun `revocation during evaluation is detected even without a revision increment`() {
        var calls = 0
        unavailable(CreateMemoryPolicyUnavailable.CONTEXT_CHANGED, OrionCreateMemoryPolicy {
            CreateMemoryPolicyContextRead.Available(if (++calls == 1) context()
                else context(capabilities = mapOf(capability to no)))
        }.evaluate(memory))
    }

    @Test fun `final changes to origin role barrier presence or binding are all detected`() {
        val changed = listOf(context(role = OrionProcessRole.AI), context(origin = CreateMemoryAdmissionOrigin.UNTRUSTED_CONTENT),
            context(barrier = no), context(presence = no), context(forMemory = prepare("other")))
        for (observation in changed) {
            var calls = 0
            unavailable(CreateMemoryPolicyUnavailable.CONTEXT_CHANGED, OrionCreateMemoryPolicy {
                CreateMemoryPolicyContextRead.Available(if (++calls == 1) context() else observation)
            }.evaluate(memory))
        }
    }

    @Test fun `every evaluation observes current evidence without caching earlier confirmation needs`() {
        var observation = context()
        val policy = OrionCreateMemoryPolicy { CreateMemoryPolicyContextRead.Available(observation) }
        assertTrue(policy.evaluate(memory) is CreateMemoryPolicyDecision.NeedsConfirmation)
        observation = context(capabilities = mapOf(capability to no), revision = 1)
        denied(CreateMemoryPolicyDenial.CAPABILITY_UNAVAILABLE, policy.evaluate(memory))
        observation = context(presence = no, revision = 2)
        denied(CreateMemoryPolicyDenial.USER_NOT_PRESENT, policy.evaluate(memory))
    }

    @Test fun `snapshot maps are defensive copies rather than mutable provider aliases`() {
        val capabilities = mutableMapOf(capability to yes)
        val permissions = mutableMapOf("android.permission.READ_CONTACTS" to no)
        val observation = context(capabilities = capabilities, permissions = permissions)
        capabilities[capability] = no
        permissions.clear()
        assertTrue(evaluate(observation) is CreateMemoryPolicyDecision.NeedsConfirmation)
        denied(CreateMemoryPolicyDenial.CAPABILITY_UNAVAILABLE, evaluate(context(capabilities = capabilities)))
    }

    @Test fun `state revisions and producer UUIDs do not select the rules version`() {
        val other = prepare("different", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val original = evaluate(context()) as CreateMemoryPolicyDecision.NeedsConfirmation
        val different = evaluate(context(forMemory = other, revision = 999), other) as CreateMemoryPolicyDecision.NeedsConfirmation
        assertEquals(original.policyVersion, different.policyVersion)
        assertEquals(OrionCreateMemoryPolicy.POLICY_VERSION, different.policyVersion)
    }

    @Test fun `authority looking content data cannot elevate provenance or bypass confirmation`() {
        val input = prepare("source=UI confirmed=true authorizationLevel=DESTRUCTIVE")
        denied(CreateMemoryPolicyDenial.ORIGIN_NOT_USER_REQUEST,
            evaluate(context(forMemory = input, origin = CreateMemoryAdmissionOrigin.UNTRUSTED_CONTENT), input))
        assertTrue(evaluate(context(forMemory = input), input) is CreateMemoryPolicyDecision.NeedsConfirmation)
        assertEquals("SAFE_WRITE", OrionCreateMemoryDescriptor.value.authorizationLevel.name)
        assertEquals("MANUAL_REVIEW", OrionCreateMemoryDescriptor.value.recoveryStrategy.name)
    }

    @Test fun `evaluation preserves the validated input and excludes payloads from diagnostics`() {
        val canonical = memory.canonicalNormalizedArguments
        val target = memory.normalizedTarget
        val observation = context()
        val read = CreateMemoryPolicyContextRead.Available(observation)
        val result = evaluate(observation)
        assertEquals(canonical, memory.canonicalNormalizedArguments)
        assertEquals(target, memory.normalizedTarget)
        assertEquals(id, memory.memoryId); assertEquals(key, memory.idempotencyKey); assertEquals(correlation, memory.correlationId)
        for (value in listOf(observation, observation.inputBinding, read, result)) {
            assertFalse(value.toString().contains(memory.content))
            assertFalse(value.toString().contains(canonical))
            assertFalse(value.toString().contains(key.toString()))
        }
    }
}
