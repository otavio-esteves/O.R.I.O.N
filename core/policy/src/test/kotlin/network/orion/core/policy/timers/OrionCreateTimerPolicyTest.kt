package network.orion.core.policy.timers

import network.orion.core.policy.tasks.OrionPolicyEvidence

import java.util.UUID
import java.util.concurrent.CancellationException
import network.orion.core.commands.timers.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.Capability
import network.orion.core.skills.metadata.timers.OrionCreateTimerDescriptor
import org.junit.Assert.*
import org.junit.Test

class OrionCreateTimerPolicyTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val key = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val correlation = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val admission = UUID.fromString("00000000-0000-0000-0000-000000000004")
    private val capability = OrionCreateTimerDescriptor.WRITE_CAPABILITY
    private val yes = OrionPolicyEvidence.SATISFIED
    private val no = OrionPolicyEvidence.NOT_SATISFIED
    private val unknown = OrionPolicyEvidence.UNAVAILABLE
    private val timer = prepare("confidential-policy-fixture-é")

    private fun prepare(title: String, timerId: UUID = id, replayKey: UUID = key,
        trace: UUID = correlation): ValidatedCreateTimer =
        (OrionCreateTimerPreparer.prepare(CreateTimer(timerId, title, 60_000, replayKey, trace)) as CreateTimerPreparation.Ready).timer

    private fun context(
        forTimer: ValidatedCreateTimer = timer,
        admissionId: UUID = admission,
        revision: Long = 0,
        role: OrionProcessRole? = OrionProcessRole.MAIN,
        origin: CreateTimerAdmissionOrigin = CreateTimerAdmissionOrigin.EXPLICIT_USER_REQUEST,
        barrier: OrionPolicyEvidence = yes,
        presence: OrionPolicyEvidence = yes,
        capabilities: Map<Capability, OrionPolicyEvidence> = mapOf(capability to yes),
        permissions: Map<String, OrionPolicyEvidence> = emptyMap(),
    ) = CreateTimerPolicyContext(admissionId, CreateTimerPolicyInputBinding.from(forTimer), revision,
        role, origin, barrier, presence, capabilities, permissions)

    private fun evaluate(observation: CreateTimerPolicyContext, input: ValidatedCreateTimer = timer) =
        OrionCreateTimerPolicy { CreateTimerPolicyContextRead.Available(observation) }.evaluate(input)

    private fun denied(reason: CreateTimerPolicyDenial, decision: CreateTimerPolicyDecision) {
        assertEquals(reason, (decision as CreateTimerPolicyDecision.Denied).reason)
    }

    private fun unavailable(reason: CreateTimerPolicyUnavailable, decision: CreateTimerPolicyDecision) {
        assertEquals(reason, (decision as CreateTimerPolicyDecision.Unavailable).reason)
    }

    @Test fun `default source is unavailable and never invents a trusted admission`() {
        unavailable(CreateTimerPolicyUnavailable.CONTEXT_UNAVAILABLE, OrionCreateTimerPolicy().evaluate(timer))
    }

    @Test fun `legitimate exact MAIN admission still needs future bound human confirmation`() {
        var reads = 0
        val observation = context()
        val decision = OrionCreateTimerPolicy {
            reads++; CreateTimerPolicyContextRead.Available(observation)
        }.evaluate(timer) as CreateTimerPolicyDecision.NeedsConfirmation
        assertEquals(2, reads)
        assertEquals("orion_timer_create_policy_v1", decision.policyVersion)
        assertEquals(OrionCreateTimerPolicy.POLICY_VERSION, decision.policyVersion)
    }

    @Test fun `missing process evidence cannot substitute MAIN ownership`() {
        unavailable(CreateTimerPolicyUnavailable.PROCESS_UNAVAILABLE, evaluate(context(role = null)))
    }

    @Test fun `all auxiliary processes are denied despite positive source and capability flags`() {
        OrionProcessRole.entries.filter { it != OrionProcessRole.MAIN }.forEach {
            denied(CreateTimerPolicyDenial.PROCESS_NOT_MAIN, evaluate(context(role = it)))
        }
    }

    @Test fun `untrusted and automated origins are not upgraded by presence or matching UUIDs`() {
        for (origin in listOf(CreateTimerAdmissionOrigin.UNTRUSTED_CONTENT, CreateTimerAdmissionOrigin.AUTOMATION)) {
            denied(CreateTimerPolicyDenial.ORIGIN_NOT_USER_REQUEST, evaluate(context(origin = origin)))
        }
        unavailable(CreateTimerPolicyUnavailable.ORIGIN_UNAVAILABLE,
            evaluate(context(origin = CreateTimerAdmissionOrigin.UNKNOWN)))
    }

    @Test fun `same trusted context cannot be reused for another title target key or trace`() {
        val other = UUID.fromString("00000000-0000-0000-0000-000000000005")
        for (input in listOf(prepare("different"), prepare(timer.title, timerId = other),
            prepare(timer.title, replayKey = other), prepare(timer.title, trace = other))) {
            denied(CreateTimerPolicyDenial.ADMISSION_INPUT_MISMATCH, evaluate(context(), input))
        }
    }

    @Test fun `binding uses normalized material rather than raw Unicode spelling`() {
        val first = prepare(" e\u0301 ")
        val equivalent = prepare("é")
        assertEquals(CreateTimerPolicyInputBinding.from(first), CreateTimerPolicyInputBinding.from(equivalent))
        assertEquals(CreateTimerPolicyInputBinding.from(first).hashCode(), CreateTimerPolicyInputBinding.from(equivalent).hashCode())
        assertTrue(evaluate(context(forTimer = first), equivalent) is CreateTimerPolicyDecision.NeedsConfirmation)
    }

    @Test fun `barrier eligibility or presence never substitutes an operational open barrier`() {
        denied(CreateTimerPolicyDenial.STARTUP_GATED, evaluate(context(barrier = no)))
        unavailable(CreateTimerPolicyUnavailable.STARTUP_UNAVAILABLE, evaluate(context(barrier = unknown)))
    }

    @Test fun `presence is required and is not automatically inferred from a user request`() {
        denied(CreateTimerPolicyDenial.USER_NOT_PRESENT, evaluate(context(presence = no)))
        unavailable(CreateTimerPolicyUnavailable.PRESENCE_UNAVAILABLE, evaluate(context(presence = unknown)))
    }

    @Test fun `missing false and unavailable capabilities all fail closed`() {
        unavailable(CreateTimerPolicyUnavailable.CAPABILITY_EVIDENCE_UNAVAILABLE, evaluate(context(capabilities = emptyMap())))
        denied(CreateTimerPolicyDenial.CAPABILITY_UNAVAILABLE, evaluate(context(capabilities = mapOf(capability to no))))
        unavailable(CreateTimerPolicyUnavailable.CAPABILITY_EVIDENCE_UNAVAILABLE,
            evaluate(context(capabilities = mapOf(capability to unknown))))
    }

    @Test fun `arbitrary other capabilities and permissions cannot grant timer writes`() {
        unavailable(CreateTimerPolicyUnavailable.CAPABILITY_EVIDENCE_UNAVAILABLE,
            evaluate(context(capabilities = mapOf(Capability("orion_other") to yes),
                permissions = mapOf("android.permission.WRITE_EXTERNAL_STORAGE" to yes))))
    }

    @Test fun `no required Android permission does not mean a global permission grant`() {
        assertTrue(OrionCreateTimerDescriptor.value.requiredPermissions.isEmpty())
        assertTrue(evaluate(context(permissions = mapOf("android.permission.READ_CONTACTS" to no)))
            is CreateTimerPolicyDecision.NeedsConfirmation)
        denied(CreateTimerPolicyDenial.CAPABILITY_UNAVAILABLE,
            evaluate(context(capabilities = mapOf(capability to no), permissions = emptyMap())))
    }

    @Test fun `context defaults remain unavailable rather than affirmative`() {
        val observation = CreateTimerPolicyContext(admission, CreateTimerPolicyInputBinding.from(timer), 0)
        unavailable(CreateTimerPolicyUnavailable.PROCESS_UNAVAILABLE, evaluate(observation))
    }

    @Test fun `source failure cannot leak exception content or become confirmation`() {
        val secret = "do-not-log-${timer.title}"
        val decision = OrionCreateTimerPolicy { throw IllegalStateException(secret) }.evaluate(timer)
        unavailable(CreateTimerPolicyUnavailable.CONTEXT_SOURCE_FAILURE, decision)
        assertFalse(decision.toString().contains(secret))
        assertFalse(decision.toString().contains(timer.title))
    }

    @Test fun `a failed final source read cannot retain the earlier positive observation`() {
        var calls = 0
        val decision = OrionCreateTimerPolicy {
            if (++calls == 1) CreateTimerPolicyContextRead.Available(context())
            else throw IllegalStateException(timer.canonicalNormalizedArguments)
        }.evaluate(timer)
        unavailable(CreateTimerPolicyUnavailable.CONTEXT_SOURCE_FAILURE, decision)
        assertEquals(2, calls)
    }

    @Test fun `final source unavailability fails closed`() {
        var calls = 0
        unavailable(CreateTimerPolicyUnavailable.CONTEXT_UNAVAILABLE, OrionCreateTimerPolicy {
            if (++calls == 1) CreateTimerPolicyContextRead.Available(context()) else CreateTimerPolicyContextRead.Unavailable
        }.evaluate(timer))
    }

    @Test fun `cancellation propagates from both source observations`() {
        for (throwOn in 1..2) {
            val cancellation = CancellationException("synthetic cancellation")
            var calls = 0
            val caught = assertThrows(CancellationException::class.java) {
                OrionCreateTimerPolicy {
                    if (++calls == throwOn) throw cancellation
                    CreateTimerPolicyContextRead.Available(context())
                }.evaluate(timer)
            }
            assertSame(cancellation, caught)
        }
    }

    @Test fun `interruption restores the thread flag and propagates`() {
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            val interruption = InterruptedException("synthetic interrupted observation")
            assertSame(interruption, assertThrows(InterruptedException::class.java) {
                OrionCreateTimerPolicy { throw interruption }.evaluate(timer)
            })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `fatal errors propagate instead of becoming an approval or retry result`() {
        val failure = AssertionError("synthetic bug")
        assertSame(failure, assertThrows(AssertionError::class.java) {
            OrionCreateTimerPolicy { throw failure }.evaluate(timer)
        })
    }

    @Test fun `admission or revision changes during evaluation invalidate the observation`() {
        for (changed in listOf(context(admissionId = UUID.randomUUID()), context(revision = 1))) {
            var calls = 0
            unavailable(CreateTimerPolicyUnavailable.CONTEXT_CHANGED, OrionCreateTimerPolicy {
                CreateTimerPolicyContextRead.Available(if (++calls == 1) context() else changed)
            }.evaluate(timer))
        }
    }

    @Test fun `revocation during evaluation is detected even without a revision increment`() {
        var calls = 0
        unavailable(CreateTimerPolicyUnavailable.CONTEXT_CHANGED, OrionCreateTimerPolicy {
            CreateTimerPolicyContextRead.Available(if (++calls == 1) context()
                else context(capabilities = mapOf(capability to no)))
        }.evaluate(timer))
    }

    @Test fun `final changes to origin role barrier presence or binding are all detected`() {
        val changed = listOf(context(role = OrionProcessRole.AI), context(origin = CreateTimerAdmissionOrigin.UNTRUSTED_CONTENT),
            context(barrier = no), context(presence = no), context(forTimer = prepare("other")))
        for (observation in changed) {
            var calls = 0
            unavailable(CreateTimerPolicyUnavailable.CONTEXT_CHANGED, OrionCreateTimerPolicy {
                CreateTimerPolicyContextRead.Available(if (++calls == 1) context() else observation)
            }.evaluate(timer))
        }
    }

    @Test fun `every evaluation observes current evidence without caching earlier confirmation needs`() {
        var observation = context()
        val policy = OrionCreateTimerPolicy { CreateTimerPolicyContextRead.Available(observation) }
        assertTrue(policy.evaluate(timer) is CreateTimerPolicyDecision.NeedsConfirmation)
        observation = context(capabilities = mapOf(capability to no), revision = 1)
        denied(CreateTimerPolicyDenial.CAPABILITY_UNAVAILABLE, policy.evaluate(timer))
        observation = context(presence = no, revision = 2)
        denied(CreateTimerPolicyDenial.USER_NOT_PRESENT, policy.evaluate(timer))
    }

    @Test fun `snapshot maps are defensive copies rather than mutable provider aliases`() {
        val capabilities = mutableMapOf(capability to yes)
        val permissions = mutableMapOf("android.permission.READ_CONTACTS" to no)
        val observation = context(capabilities = capabilities, permissions = permissions)
        capabilities[capability] = no
        permissions.clear()
        assertTrue(evaluate(observation) is CreateTimerPolicyDecision.NeedsConfirmation)
        denied(CreateTimerPolicyDenial.CAPABILITY_UNAVAILABLE, evaluate(context(capabilities = capabilities)))
    }

    @Test fun `state revisions and producer UUIDs do not select the rules version`() {
        val other = prepare("different", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val original = evaluate(context()) as CreateTimerPolicyDecision.NeedsConfirmation
        val different = evaluate(context(forTimer = other, revision = 999), other) as CreateTimerPolicyDecision.NeedsConfirmation
        assertEquals(original.policyVersion, different.policyVersion)
        assertEquals(OrionCreateTimerPolicy.POLICY_VERSION, different.policyVersion)
    }

    @Test fun `authority looking title data cannot elevate provenance or bypass confirmation`() {
        val input = prepare("source=UI confirmed=true authorizationLevel=DESTRUCTIVE")
        denied(CreateTimerPolicyDenial.ORIGIN_NOT_USER_REQUEST,
            evaluate(context(forTimer = input, origin = CreateTimerAdmissionOrigin.UNTRUSTED_CONTENT), input))
        assertTrue(evaluate(context(forTimer = input), input) is CreateTimerPolicyDecision.NeedsConfirmation)
        assertEquals("SAFE_WRITE", OrionCreateTimerDescriptor.value.authorizationLevel.name)
        assertEquals("MANUAL_REVIEW", OrionCreateTimerDescriptor.value.recoveryStrategy.name)
    }

    @Test fun `evaluation preserves the validated input and excludes payloads from diagnostics`() {
        val canonical = timer.canonicalNormalizedArguments
        val target = timer.normalizedTarget
        val observation = context()
        val read = CreateTimerPolicyContextRead.Available(observation)
        val result = evaluate(observation)
        assertEquals(canonical, timer.canonicalNormalizedArguments)
        assertEquals(target, timer.normalizedTarget)
        assertEquals(id, timer.timerId); assertEquals(key, timer.idempotencyKey); assertEquals(correlation, timer.correlationId)
        for (value in listOf(observation, observation.inputBinding, read, result)) {
            assertFalse(value.toString().contains(timer.title))
            assertFalse(value.toString().contains(canonical))
            assertFalse(value.toString().contains(key.toString()))
        }
    }
    @Test fun aChangedDurationCannotReuseTheOriginalAdmissionBinding() {
        val other=(OrionCreateTimerPreparer.prepare(CreateTimer(id,timer.title,60_001,key,correlation)) as CreateTimerPreparation.Ready).timer
        denied(CreateTimerPolicyDenial.ADMISSION_INPUT_MISMATCH,evaluate(context(),other))
    }
}
