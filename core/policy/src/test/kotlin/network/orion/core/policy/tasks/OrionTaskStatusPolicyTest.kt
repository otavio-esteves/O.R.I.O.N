package network.orion.core.policy.tasks

import java.util.UUID
import java.util.concurrent.CancellationException
import network.orion.core.commands.tasks.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.Capability
import network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor
import org.junit.Assert.*
import org.junit.Test

class OrionTaskStatusPolicyTest {
    private val id = UUID.randomUUID()
    private val key = UUID.randomUUID()
    private val trace = UUID.randomUUID()
    private val admissionId = UUID.randomUUID()
    private val write = OrionCreateTaskDescriptor.WRITE_CAPABILITY
    private val change = prepared(CompleteTask(id, 0, key, trace))
    private fun prepared(command: TaskStatusCommand) = (OrionTaskStatusPreparer.prepare(command) as TaskStatusPreparation.Ready).change
    private fun context(input: ValidatedTaskStatusChange = change, role: OrionProcessRole? = OrionProcessRole.MAIN,
        origin: TaskStatusAdmissionOrigin = TaskStatusAdmissionOrigin.EXPLICIT_USER_REQUEST,
        barrier: OrionPolicyEvidence = OrionPolicyEvidence.SATISFIED, presence: OrionPolicyEvidence = OrionPolicyEvidence.SATISFIED,
        revision: Long = 0, admission: UUID = admissionId,
        capabilities: Map<Capability, OrionPolicyEvidence> = mapOf(write to OrionPolicyEvidence.SATISFIED),
        permissions: Map<String, OrionPolicyEvidence> = emptyMap()) = TaskStatusPolicyContext(admission,
        TaskStatusPolicyInputBinding.from(input), revision, role, origin, barrier, presence, capabilities, permissions)
    private fun evaluate(context: TaskStatusPolicyContext, input: ValidatedTaskStatusChange = change) =
        OrionTaskStatusPolicy(TaskStatusPolicyContextSource { TaskStatusPolicyContextRead.Available(context) }).evaluate(input)
    private fun denied(result: TaskStatusPolicyDecision, reason: TaskStatusPolicyReason) =
        assertEquals(reason, (result as TaskStatusPolicyDecision.Denied).reason)

    @Test fun absentSourceCannotGrantPreparation() {
        assertTrue(OrionTaskStatusPolicy().evaluate(change) is TaskStatusPolicyDecision.Unavailable)
    }

    @Test fun legitimateCompleteAndCancelAdmissionsStillRequireExactLaterConfirmation() {
        for (input in listOf(change, prepared(CancelTask(id, 0, key, trace)))) {
            val result = evaluate(context(input), input) as TaskStatusPolicyDecision.NeedsConfirmation
            assertEquals(OrionTaskStatusPolicy.POLICY_VERSION, result.policyVersion)
        }
    }

    @Test fun everyAuxiliaryProcessIsDeniedAndUnknownRoleIsUnavailable() {
        for (role in OrionProcessRole.entries.filter { it != OrionProcessRole.MAIN })
            denied(evaluate(context(role = role)), TaskStatusPolicyReason.PROCESS_NOT_MAIN)
        assertTrue(evaluate(context(role = null)) is TaskStatusPolicyDecision.Unavailable)
    }

    @Test fun taskVersionOperationReplayKeyAndTraceAreBoundToTheExactAdmission() {
        val other = listOf(CompleteTask(UUID.randomUUID(), 0, key, trace), CompleteTask(id, 1, key, trace),
            CancelTask(id, 0, key, trace), CompleteTask(id, 0, UUID.randomUUID(), trace), CompleteTask(id, 0, key, UUID.randomUUID()))
        for (input in other) denied(evaluate(context(), prepared(input)), TaskStatusPolicyReason.INPUT_MISMATCH)
    }

    @Test fun matchingIdsAndPresenceCannotPromoteExternalOrAutomaticOrigin() {
        for (origin in listOf(TaskStatusAdmissionOrigin.UNTRUSTED_CONTENT, TaskStatusAdmissionOrigin.AUTOMATION))
            denied(evaluate(context(origin = origin)), TaskStatusPolicyReason.ORIGIN_NOT_USER_REQUEST)
        assertTrue(evaluate(context(origin = TaskStatusAdmissionOrigin.UNKNOWN)) is TaskStatusPolicyDecision.Unavailable)
    }

    @Test fun closedBarrierAndAbsentPresenceCannotPrepareMutations() {
        denied(evaluate(context(barrier = OrionPolicyEvidence.NOT_SATISFIED)), TaskStatusPolicyReason.STARTUP_GATED)
        denied(evaluate(context(presence = OrionPolicyEvidence.NOT_SATISFIED)), TaskStatusPolicyReason.USER_NOT_PRESENT)
        assertTrue(evaluate(context(barrier = OrionPolicyEvidence.UNAVAILABLE)) is TaskStatusPolicyDecision.Unavailable)
        assertTrue(evaluate(context(presence = OrionPolicyEvidence.UNAVAILABLE)) is TaskStatusPolicyDecision.Unavailable)
    }

    @Test fun emptyUnknownAndNegativeCapabilitiesNeverBecomePermissionGrants() {
        assertTrue(evaluate(context(capabilities = emptyMap())) is TaskStatusPolicyDecision.Unavailable)
        assertTrue(evaluate(context(capabilities = mapOf(write to OrionPolicyEvidence.UNAVAILABLE))) is TaskStatusPolicyDecision.Unavailable)
        denied(evaluate(context(capabilities = mapOf(write to OrionPolicyEvidence.NOT_SATISFIED),
            permissions = mapOf("arbitrary.PERMISSION" to OrionPolicyEvidence.SATISFIED))), TaskStatusPolicyReason.CAPABILITY_UNAVAILABLE)
    }

    @Test fun revocationBetweenObservationsInvalidatesEvenAnUnchangedRevision() {
        var reads = 0
        val source = TaskStatusPolicyContextSource { TaskStatusPolicyContextRead.Available(if (reads++ == 0) context()
            else context(capabilities = mapOf(write to OrionPolicyEvidence.NOT_SATISFIED))) }
        assertEquals(TaskStatusPolicyReason.CONTEXT_CHANGED,
            (OrionTaskStatusPolicy(source).evaluate(change) as TaskStatusPolicyDecision.Unavailable).reason)
    }

    @Test fun revisionAdmissionAndOtherwisePositiveSnapshotChangesAreNotCached() {
        for (second in listOf(context(revision = 1), context(admission = UUID.randomUUID()),
            context(permissions = mapOf("arbitrary.PERMISSION" to OrionPolicyEvidence.SATISFIED)))) {
            var reads = 0
            val source = TaskStatusPolicyContextSource { TaskStatusPolicyContextRead.Available(if (reads++ == 0) context() else second) }
            assertTrue(OrionTaskStatusPolicy(source).evaluate(change) is TaskStatusPolicyDecision.Unavailable)
        }
    }

    @Test fun snapshotsCopyMapsAndCannotBeMutatedAfterCapture() {
        val values = mutableMapOf(write to OrionPolicyEvidence.SATISFIED)
        val snapshot = context(capabilities = values)
        values[write] = OrionPolicyEvidence.NOT_SATISFIED
        assertTrue(evaluate(snapshot) is TaskStatusPolicyDecision.NeedsConfirmation)
    }

    @Test fun exceptionDetailsStayPrivateAndNeverBecomeAPositiveDecision() {
        val source = TaskStatusPolicyContextSource { error("private-fixture-content") }
        val result = OrionTaskStatusPolicy(source).evaluate(change)
        assertTrue(result is TaskStatusPolicyDecision.Unavailable)
        assertFalse(result.toString().contains("private-fixture-content"))
    }

    @Test fun unavailableFinalReadCannotKeepAnEarlierPositiveObservation() {
        var reads = 0
        val source = TaskStatusPolicyContextSource {
            if (reads++ == 0) TaskStatusPolicyContextRead.Available(context()) else TaskStatusPolicyContextRead.Unavailable
        }
        assertTrue(OrionTaskStatusPolicy(source).evaluate(change) is TaskStatusPolicyDecision.Unavailable)
    }

    @Test fun cancellationInterruptionAndFatalErrorsPropagate() {
        assertThrows(CancellationException::class.java) {
            OrionTaskStatusPolicy(TaskStatusPolicyContextSource { throw CancellationException() }).evaluate(change)
        }
        try {
            assertThrows(InterruptedException::class.java) {
                OrionTaskStatusPolicy(TaskStatusPolicyContextSource { throw InterruptedException() }).evaluate(change)
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertThrows(AssertionError::class.java) {
            OrionTaskStatusPolicy(TaskStatusPolicyContextSource { throw AssertionError() }).evaluate(change)
        }
    }

    @Test fun laterEvaluationAlwaysReadsCurrentEvidenceAndLeavesInputUntouched() {
        var snapshot = context()
        val original = change.canonicalNormalizedArguments
        val policy = OrionTaskStatusPolicy(TaskStatusPolicyContextSource { TaskStatusPolicyContextRead.Available(snapshot) })
        assertTrue(policy.evaluate(change) is TaskStatusPolicyDecision.NeedsConfirmation)
        snapshot = context(presence = OrionPolicyEvidence.NOT_SATISFIED)
        denied(policy.evaluate(change), TaskStatusPolicyReason.USER_NOT_PRESENT)
        assertEquals(original, change.canonicalNormalizedArguments)
        for (value in listOf(change, context(), TaskStatusPolicyInputBinding.from(change))) {
            assertFalse(value.toString().contains(id.toString()))
            assertFalse(value.toString().contains(key.toString()))
        }
    }
}
