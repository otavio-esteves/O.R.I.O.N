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

class OrionTimerStatusPolicyTest {
    private val id = UUID.randomUUID()
    private val key = UUID.randomUUID()
    private val trace = UUID.randomUUID()
    private val admissionId = UUID.randomUUID()
    private val write = OrionCreateTimerDescriptor.WRITE_CAPABILITY
    private val change = prepared(CancelTimer(id, 0, key, trace))
    private fun prepared(command: TimerStatusCommand) = (OrionTimerStatusPreparer.prepare(command) as TimerStatusPreparation.Ready).change
    private fun context(input: ValidatedTimerStatusChange = change, role: OrionProcessRole? = OrionProcessRole.MAIN,
        origin: TimerStatusAdmissionOrigin = TimerStatusAdmissionOrigin.EXPLICIT_USER_REQUEST,
        barrier: OrionPolicyEvidence = OrionPolicyEvidence.SATISFIED, presence: OrionPolicyEvidence = OrionPolicyEvidence.SATISFIED,
        revision: Long = 0, admission: UUID = admissionId,
        capabilities: Map<Capability, OrionPolicyEvidence> = mapOf(write to OrionPolicyEvidence.SATISFIED),
        permissions: Map<String, OrionPolicyEvidence> = emptyMap()) = TimerStatusPolicyContext(admission,
        TimerStatusPolicyInputBinding.from(input), revision, role, origin, barrier, presence, capabilities, permissions)
    private fun evaluate(context: TimerStatusPolicyContext, input: ValidatedTimerStatusChange = change) =
        OrionTimerStatusPolicy(TimerStatusPolicyContextSource { TimerStatusPolicyContextRead.Available(context) }).evaluate(input)
    private fun denied(result: TimerStatusPolicyDecision, reason: TimerStatusPolicyReason) =
        assertEquals(reason, (result as TimerStatusPolicyDecision.Denied).reason)

    @Test fun absentSourceCannotGrantPreparation() {
        assertTrue(OrionTimerStatusPolicy().evaluate(change) is TimerStatusPolicyDecision.Unavailable)
    }

    @Test fun legitimateCompleteAndCancelAdmissionsStillRequireExactLaterConfirmation() {
        for (input in listOf(change, prepared(CancelTimer(id, 0, key, trace)))) {
            val result = evaluate(context(input), input) as TimerStatusPolicyDecision.NeedsConfirmation
            assertEquals(OrionTimerStatusPolicy.POLICY_VERSION, result.policyVersion)
        }
    }

    @Test fun everyAuxiliaryProcessIsDeniedAndUnknownRoleIsUnavailable() {
        for (role in OrionProcessRole.entries.filter { it != OrionProcessRole.MAIN })
            denied(evaluate(context(role = role)), TimerStatusPolicyReason.PROCESS_NOT_MAIN)
        assertTrue(evaluate(context(role = null)) is TimerStatusPolicyDecision.Unavailable)
    }

    @Test fun timerVersionOperationReplayKeyAndTraceAreBoundToTheExactAdmission() {
        val other = listOf(CancelTimer(UUID.randomUUID(), 0, key, trace), CancelTimer(id, 1, key, trace),
            CancelTimer(id, 0, UUID.randomUUID(), trace), CancelTimer(id, 0, key, UUID.randomUUID()))
        for (input in other) denied(evaluate(context(), prepared(input)), TimerStatusPolicyReason.INPUT_MISMATCH)
    }

    @Test fun matchingIdsAndPresenceCannotPromoteExternalOrAutomaticOrigin() {
        for (origin in listOf(TimerStatusAdmissionOrigin.UNTRUSTED_CONTENT, TimerStatusAdmissionOrigin.AUTOMATION))
            denied(evaluate(context(origin = origin)), TimerStatusPolicyReason.ORIGIN_NOT_USER_REQUEST)
        assertTrue(evaluate(context(origin = TimerStatusAdmissionOrigin.UNKNOWN)) is TimerStatusPolicyDecision.Unavailable)
    }

    @Test fun closedBarrierAndAbsentPresenceCannotPrepareMutations() {
        denied(evaluate(context(barrier = OrionPolicyEvidence.NOT_SATISFIED)), TimerStatusPolicyReason.STARTUP_GATED)
        denied(evaluate(context(presence = OrionPolicyEvidence.NOT_SATISFIED)), TimerStatusPolicyReason.USER_NOT_PRESENT)
        assertTrue(evaluate(context(barrier = OrionPolicyEvidence.UNAVAILABLE)) is TimerStatusPolicyDecision.Unavailable)
        assertTrue(evaluate(context(presence = OrionPolicyEvidence.UNAVAILABLE)) is TimerStatusPolicyDecision.Unavailable)
    }

    @Test fun emptyUnknownAndNegativeCapabilitiesNeverBecomePermissionGrants() {
        assertTrue(evaluate(context(capabilities = emptyMap())) is TimerStatusPolicyDecision.Unavailable)
        assertTrue(evaluate(context(capabilities = mapOf(write to OrionPolicyEvidence.UNAVAILABLE))) is TimerStatusPolicyDecision.Unavailable)
        denied(evaluate(context(capabilities = mapOf(write to OrionPolicyEvidence.NOT_SATISFIED),
            permissions = mapOf("arbitrary.PERMISSION" to OrionPolicyEvidence.SATISFIED))), TimerStatusPolicyReason.CAPABILITY_UNAVAILABLE)
    }

    @Test fun revocationBetweenObservationsInvalidatesEvenAnUnchangedRevision() {
        var reads = 0
        val source = TimerStatusPolicyContextSource { TimerStatusPolicyContextRead.Available(if (reads++ == 0) context()
            else context(capabilities = mapOf(write to OrionPolicyEvidence.NOT_SATISFIED))) }
        assertEquals(TimerStatusPolicyReason.CONTEXT_CHANGED,
            (OrionTimerStatusPolicy(source).evaluate(change) as TimerStatusPolicyDecision.Unavailable).reason)
    }

    @Test fun revisionAdmissionAndOtherwisePositiveSnapshotChangesAreNotCached() {
        for (second in listOf(context(revision = 1), context(admission = UUID.randomUUID()),
            context(permissions = mapOf("arbitrary.PERMISSION" to OrionPolicyEvidence.SATISFIED)))) {
            var reads = 0
            val source = TimerStatusPolicyContextSource { TimerStatusPolicyContextRead.Available(if (reads++ == 0) context() else second) }
            assertTrue(OrionTimerStatusPolicy(source).evaluate(change) is TimerStatusPolicyDecision.Unavailable)
        }
    }

    @Test fun snapshotsCopyMapsAndCannotBeMutatedAfterCapture() {
        val values = mutableMapOf(write to OrionPolicyEvidence.SATISFIED)
        val snapshot = context(capabilities = values)
        values[write] = OrionPolicyEvidence.NOT_SATISFIED
        assertTrue(evaluate(snapshot) is TimerStatusPolicyDecision.NeedsConfirmation)
    }

    @Test fun exceptionDetailsStayPrivateAndNeverBecomeAPositiveDecision() {
        val source = TimerStatusPolicyContextSource { error("private-fixture-content") }
        val result = OrionTimerStatusPolicy(source).evaluate(change)
        assertTrue(result is TimerStatusPolicyDecision.Unavailable)
        assertFalse(result.toString().contains("private-fixture-content"))
    }

    @Test fun unavailableFinalReadCannotKeepAnEarlierPositiveObservation() {
        var reads = 0
        val source = TimerStatusPolicyContextSource {
            if (reads++ == 0) TimerStatusPolicyContextRead.Available(context()) else TimerStatusPolicyContextRead.Unavailable
        }
        assertTrue(OrionTimerStatusPolicy(source).evaluate(change) is TimerStatusPolicyDecision.Unavailable)
    }

    @Test fun cancellationInterruptionAndFatalErrorsPropagate() {
        assertThrows(CancellationException::class.java) {
            OrionTimerStatusPolicy(TimerStatusPolicyContextSource { throw CancellationException() }).evaluate(change)
        }
        try {
            assertThrows(InterruptedException::class.java) {
                OrionTimerStatusPolicy(TimerStatusPolicyContextSource { throw InterruptedException() }).evaluate(change)
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertThrows(AssertionError::class.java) {
            OrionTimerStatusPolicy(TimerStatusPolicyContextSource { throw AssertionError() }).evaluate(change)
        }
    }

    @Test fun laterEvaluationAlwaysReadsCurrentEvidenceAndLeavesInputUntouched() {
        var snapshot = context()
        val original = change.canonicalNormalizedArguments
        val policy = OrionTimerStatusPolicy(TimerStatusPolicyContextSource { TimerStatusPolicyContextRead.Available(snapshot) })
        assertTrue(policy.evaluate(change) is TimerStatusPolicyDecision.NeedsConfirmation)
        snapshot = context(presence = OrionPolicyEvidence.NOT_SATISFIED)
        denied(policy.evaluate(change), TimerStatusPolicyReason.USER_NOT_PRESENT)
        assertEquals(original, change.canonicalNormalizedArguments)
        for (value in listOf(change, context(), TimerStatusPolicyInputBinding.from(change))) {
            assertFalse(value.toString().contains(id.toString()))
            assertFalse(value.toString().contains(key.toString()))
        }
    }
}
