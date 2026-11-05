package network.orion.core.policy.reminders

import java.util.UUID
import java.util.concurrent.CancellationException
import network.orion.core.commands.reminders.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.model.Capability
import network.orion.core.skills.metadata.reminders.OrionCreateReminderDescriptor
import org.junit.Assert.*
import org.junit.Test

class OrionReminderStatusPolicyTest {
    private fun CreateReminder(id: java.util.UUID, title: String, key: java.util.UUID, trace: java.util.UUID) =
        CreateReminder(id, title, java.time.Instant.EPOCH, java.time.ZoneId.of("UTC"), key, trace)
    private fun SnoozeReminder(id: java.util.UUID, version: Long, key: java.util.UUID, trace: java.util.UUID) =
        SnoozeReminder(id, version, java.time.Instant.EPOCH, java.time.ZoneId.of("UTC"), key, trace)

    private val id = UUID.randomUUID()
    private val key = UUID.randomUUID()
    private val trace = UUID.randomUUID()
    private val admissionId = UUID.randomUUID()
    private val write = OrionCreateReminderDescriptor.WRITE_CAPABILITY
    private val change = prepared(SnoozeReminder(id, 0, key, trace))
    private fun prepared(command: ReminderStatusCommand) = (OrionReminderStatusPreparer.prepare(command) as ReminderStatusPreparation.Ready).change
    private fun context(input: ValidatedReminderStatusChange = change, role: OrionProcessRole? = OrionProcessRole.MAIN,
        origin: ReminderStatusAdmissionOrigin = ReminderStatusAdmissionOrigin.EXPLICIT_USER_REQUEST,
        barrier: OrionPolicyEvidence = OrionPolicyEvidence.SATISFIED, presence: OrionPolicyEvidence = OrionPolicyEvidence.SATISFIED,
        revision: Long = 0, admission: UUID = admissionId,
        capabilities: Map<Capability, OrionPolicyEvidence> = mapOf(write to OrionPolicyEvidence.SATISFIED),
        permissions: Map<String, OrionPolicyEvidence> = emptyMap()) = ReminderStatusPolicyContext(admission,
        ReminderStatusPolicyInputBinding.from(input), revision, role, origin, barrier, presence, capabilities, permissions)
    private fun evaluate(context: ReminderStatusPolicyContext, input: ValidatedReminderStatusChange = change) =
        OrionReminderStatusPolicy(ReminderStatusPolicyContextSource { ReminderStatusPolicyContextRead.Available(context) }).evaluate(input)
    private fun denied(result: ReminderStatusPolicyDecision, reason: ReminderStatusPolicyReason) =
        assertEquals(reason, (result as ReminderStatusPolicyDecision.Denied).reason)

    @Test fun absentSourceCannotGrantPreparation() {
        assertTrue(OrionReminderStatusPolicy().evaluate(change) is ReminderStatusPolicyDecision.Unavailable)
    }

    @Test fun legitimateCompleteAndCancelAdmissionsStillRequireExactLaterConfirmation() {
        for (input in listOf(change, prepared(CancelReminder(id, 0, key, trace)))) {
            val result = evaluate(context(input), input) as ReminderStatusPolicyDecision.NeedsConfirmation
            assertEquals(OrionReminderStatusPolicy.POLICY_VERSION, result.policyVersion)
        }
    }

    @Test fun everyAuxiliaryProcessIsDeniedAndUnknownRoleIsUnavailable() {
        for (role in OrionProcessRole.entries.filter { it != OrionProcessRole.MAIN })
            denied(evaluate(context(role = role)), ReminderStatusPolicyReason.PROCESS_NOT_MAIN)
        assertTrue(evaluate(context(role = null)) is ReminderStatusPolicyDecision.Unavailable)
    }

    @Test fun reminderVersionOperationReplayKeyAndTraceAreBoundToTheExactAdmission() {
        val other = listOf(SnoozeReminder(UUID.randomUUID(), 0, key, trace), SnoozeReminder(id, 1, key, trace),
            CancelReminder(id, 0, key, trace), SnoozeReminder(id, 0, UUID.randomUUID(), trace), SnoozeReminder(id, 0, key, UUID.randomUUID()))
        for (input in other) denied(evaluate(context(), prepared(input)), ReminderStatusPolicyReason.INPUT_MISMATCH)
    }

    @Test fun matchingIdsAndPresenceCannotPromoteExternalOrAutomaticOrigin() {
        for (origin in listOf(ReminderStatusAdmissionOrigin.UNTRUSTED_CONTENT, ReminderStatusAdmissionOrigin.AUTOMATION))
            denied(evaluate(context(origin = origin)), ReminderStatusPolicyReason.ORIGIN_NOT_USER_REQUEST)
        assertTrue(evaluate(context(origin = ReminderStatusAdmissionOrigin.UNKNOWN)) is ReminderStatusPolicyDecision.Unavailable)
    }

    @Test fun closedBarrierAndAbsentPresenceCannotPrepareMutations() {
        denied(evaluate(context(barrier = OrionPolicyEvidence.NOT_SATISFIED)), ReminderStatusPolicyReason.STARTUP_GATED)
        denied(evaluate(context(presence = OrionPolicyEvidence.NOT_SATISFIED)), ReminderStatusPolicyReason.USER_NOT_PRESENT)
        assertTrue(evaluate(context(barrier = OrionPolicyEvidence.UNAVAILABLE)) is ReminderStatusPolicyDecision.Unavailable)
        assertTrue(evaluate(context(presence = OrionPolicyEvidence.UNAVAILABLE)) is ReminderStatusPolicyDecision.Unavailable)
    }

    @Test fun emptyUnknownAndNegativeCapabilitiesNeverBecomePermissionGrants() {
        assertTrue(evaluate(context(capabilities = emptyMap())) is ReminderStatusPolicyDecision.Unavailable)
        assertTrue(evaluate(context(capabilities = mapOf(write to OrionPolicyEvidence.UNAVAILABLE))) is ReminderStatusPolicyDecision.Unavailable)
        denied(evaluate(context(capabilities = mapOf(write to OrionPolicyEvidence.NOT_SATISFIED),
            permissions = mapOf("arbitrary.PERMISSION" to OrionPolicyEvidence.SATISFIED))), ReminderStatusPolicyReason.CAPABILITY_UNAVAILABLE)
    }

    @Test fun revocationBetweenObservationsInvalidatesEvenAnUnchangedRevision() {
        var reads = 0
        val source = ReminderStatusPolicyContextSource { ReminderStatusPolicyContextRead.Available(if (reads++ == 0) context()
            else context(capabilities = mapOf(write to OrionPolicyEvidence.NOT_SATISFIED))) }
        assertEquals(ReminderStatusPolicyReason.CONTEXT_CHANGED,
            (OrionReminderStatusPolicy(source).evaluate(change) as ReminderStatusPolicyDecision.Unavailable).reason)
    }

    @Test fun revisionAdmissionAndOtherwisePositiveSnapshotChangesAreNotCached() {
        for (second in listOf(context(revision = 1), context(admission = UUID.randomUUID()),
            context(permissions = mapOf("arbitrary.PERMISSION" to OrionPolicyEvidence.SATISFIED)))) {
            var reads = 0
            val source = ReminderStatusPolicyContextSource { ReminderStatusPolicyContextRead.Available(if (reads++ == 0) context() else second) }
            assertTrue(OrionReminderStatusPolicy(source).evaluate(change) is ReminderStatusPolicyDecision.Unavailable)
        }
    }

    @Test fun snapshotsCopyMapsAndCannotBeMutatedAfterCapture() {
        val values = mutableMapOf(write to OrionPolicyEvidence.SATISFIED)
        val snapshot = context(capabilities = values)
        values[write] = OrionPolicyEvidence.NOT_SATISFIED
        assertTrue(evaluate(snapshot) is ReminderStatusPolicyDecision.NeedsConfirmation)
    }

    @Test fun exceptionDetailsStayPrivateAndNeverBecomeAPositiveDecision() {
        val source = ReminderStatusPolicyContextSource { error("private-fixture-content") }
        val result = OrionReminderStatusPolicy(source).evaluate(change)
        assertTrue(result is ReminderStatusPolicyDecision.Unavailable)
        assertFalse(result.toString().contains("private-fixture-content"))
    }

    @Test fun unavailableFinalReadCannotKeepAnEarlierPositiveObservation() {
        var reads = 0
        val source = ReminderStatusPolicyContextSource {
            if (reads++ == 0) ReminderStatusPolicyContextRead.Available(context()) else ReminderStatusPolicyContextRead.Unavailable
        }
        assertTrue(OrionReminderStatusPolicy(source).evaluate(change) is ReminderStatusPolicyDecision.Unavailable)
    }

    @Test fun cancellationInterruptionAndFatalErrorsPropagate() {
        assertThrows(CancellationException::class.java) {
            OrionReminderStatusPolicy(ReminderStatusPolicyContextSource { throw CancellationException() }).evaluate(change)
        }
        try {
            assertThrows(InterruptedException::class.java) {
                OrionReminderStatusPolicy(ReminderStatusPolicyContextSource { throw InterruptedException() }).evaluate(change)
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertThrows(AssertionError::class.java) {
            OrionReminderStatusPolicy(ReminderStatusPolicyContextSource { throw AssertionError() }).evaluate(change)
        }
    }

    @Test fun laterEvaluationAlwaysReadsCurrentEvidenceAndLeavesInputUntouched() {
        var snapshot = context()
        val original = change.canonicalNormalizedArguments
        val policy = OrionReminderStatusPolicy(ReminderStatusPolicyContextSource { ReminderStatusPolicyContextRead.Available(snapshot) })
        assertTrue(policy.evaluate(change) is ReminderStatusPolicyDecision.NeedsConfirmation)
        snapshot = context(presence = OrionPolicyEvidence.NOT_SATISFIED)
        denied(policy.evaluate(change), ReminderStatusPolicyReason.USER_NOT_PRESENT)
        assertEquals(original, change.canonicalNormalizedArguments)
        for (value in listOf(change, context(), ReminderStatusPolicyInputBinding.from(change))) {
            assertFalse(value.toString().contains(id.toString()))
            assertFalse(value.toString().contains(key.toString()))
        }
    }
}
