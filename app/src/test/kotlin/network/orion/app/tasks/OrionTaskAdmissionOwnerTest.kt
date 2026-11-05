package network.orion.app.tasks

import java.util.UUID
import java.util.concurrent.CancellationException
import network.orion.core.commands.tasks.CreateTask
import network.orion.core.commands.tasks.CreateTaskRejection
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.policy.tasks.*
import org.junit.Assert.*
import org.junit.Test

class OrionTaskAdmissionOwnerTest {
    private val barrier = StartupBarrier()
    private var capability = OrionPolicyEvidence.SATISFIED
    private var presence = OrionPolicyEvidence.SATISFIED
    private val owner = OrionTaskAdmissionOwner(OrionProcessRole.MAIN, barrier, { capability }, { presence })
    private fun command(title: String = "Revisar orçamento") = CreateTask(UUID.randomUUID(), title, UUID.randomUUID(), UUID.randomUUID())
    private fun active(owner: OrionTaskAdmissionOwner = this.owner) = owner.openUiChannel().apply {
        onResumed(true); onWindowFocus(true)
    }
    private fun admitted(channel: OrionTaskAdmissionOwner.UiChannel = active(), command: CreateTask = command()) =
        (channel.captureCreateFromUser(command) as OrionTaskAdmissionResult.Admitted).request
    private fun openBarrier() = barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.READY)

    @Test fun explicitMainGestureBindsExactInputAndStillRequiresConfirmation() {
        openBarrier()
        val request = admitted()
        assertTrue(request.evaluate() is CreateTaskPolicyDecision.NeedsConfirmation)
        val other = admitted(command = command())
        assertEquals(CreateTaskPolicyDenial.ADMISSION_INPUT_MISMATCH,
            (OrionCreateTaskPolicy(other.source).evaluate(request.task) as CreateTaskPolicyDecision.Denied).reason)
    }

    @Test fun matchingProducerIdsAndForegroundAloneCannotAdmitAnAuxiliaryProcess() {
        for (role in OrionProcessRole.entries.filter { it != OrionProcessRole.MAIN }) {
            val auxiliary = OrionTaskAdmissionOwner(role, barrier, { capability }, { presence })
            assertEquals(OrionTaskAdmissionResult.Unavailable, active(auxiliary).captureCreateFromUser(command()))
        }
    }

    @Test fun defaultPausedUnfocusedAndClosedChannelsCannotCaptureGestures() {
        val channel = owner.openUiChannel()
        assertEquals(OrionTaskAdmissionResult.Unavailable, channel.captureCreateFromUser(command()))
        channel.onResumed(true)
        assertEquals(OrionTaskAdmissionResult.Unavailable, channel.captureCreateFromUser(command()))
        channel.onWindowFocus(true)
        channel.close()
        assertEquals(OrionTaskAdmissionResult.Unavailable, channel.captureCreateFromUser(command()))
    }

    @Test fun absentOrUnknownDevicePresenceCannotAuthenticateAGesture() {
        val channel = active()
        for (evidence in listOf(OrionPolicyEvidence.NOT_SATISFIED, OrionPolicyEvidence.UNAVAILABLE)) {
            presence = evidence
            assertEquals(OrionTaskAdmissionResult.Unavailable, channel.captureCreateFromUser(command()))
        }
    }

    @Test fun unavailableDeviceServiceFailsClosedWithoutExceptionText() {
        val unavailable = OrionTaskAdmissionOwner(OrionProcessRole.MAIN, barrier, { capability }, {
            error("synthetic-private-fixture")
        })
        val result = active(unavailable).captureCreateFromUser(command())
        assertEquals(OrionTaskAdmissionResult.Unavailable, result)
        assertFalse(result.toString().contains("synthetic-private-fixture"))
    }

    @Test fun cancellationIsNeverConvertedToAdmission() {
        val cancelled = OrionTaskAdmissionOwner(OrionProcessRole.MAIN, barrier, { capability }, { throw CancellationException() })
        assertThrows(CancellationException::class.java) { active(cancelled).captureCreateFromUser(command()) }
    }

    @Test fun closedBarrierAdmitsOnlyInputAndPolicyStillGatesIt() {
        val request = admitted()
        assertEquals(CreateTaskPolicyDenial.STARTUP_GATED, (request.evaluate() as CreateTaskPolicyDecision.Denied).reason)
        assertFalse(barrier.isOpen())
    }

    @Test fun missingFunctionalCapabilityNeverBecomesAPositivePolicy() {
        openBarrier()
        val request = admitted()
        capability = OrionPolicyEvidence.NOT_SATISFIED
        assertEquals(CreateTaskPolicyDenial.CAPABILITY_UNAVAILABLE, (request.evaluate() as CreateTaskPolicyDecision.Denied).reason)
        capability = OrionPolicyEvidence.UNAVAILABLE
        assertTrue(request.evaluate() is CreateTaskPolicyDecision.Unavailable)
    }

    @Test fun pauseResumeAbaNeverRevivesAnOldAdmission() {
        openBarrier()
        val channel = active()
        val request = admitted(channel)
        channel.onResumed(false); channel.onResumed(true)
        assertTrue(request.evaluate() is CreateTaskPolicyDecision.Unavailable)
        assertTrue(admitted(channel).evaluate() is CreateTaskPolicyDecision.NeedsConfirmation)
    }

    @Test fun focusLossAndReturnInvalidateEarlierInput() {
        openBarrier()
        val channel = active()
        val request = admitted(channel)
        channel.onWindowFocus(false); channel.onWindowFocus(true)
        assertTrue(request.evaluate() is CreateTaskPolicyDecision.Unavailable)
    }

    @Test fun activityReplacementAndCloseRevokeOldChannels() {
        openBarrier()
        val channel = active()
        val request = admitted(channel)
        val replacement = active()
        assertTrue(request.evaluate() is CreateTaskPolicyDecision.Unavailable)
        assertEquals(OrionTaskAdmissionResult.Unavailable, channel.captureCreateFromUser(command()))
        val second = admitted(replacement)
        replacement.close()
        assertTrue(second.evaluate() is CreateTaskPolicyDecision.Unavailable)
    }

    @Test fun changedStateBetweenPolicyReadsInvalidatesTheDecision() {
        openBarrier()
        val request = admitted()
        var reads = 0
        val source = CreateTaskPolicyContextSource {
            if (reads++ == 1) capability = OrionPolicyEvidence.NOT_SATISFIED
            request.source.read()
        }
        assertEquals(CreateTaskPolicyUnavailable.CONTEXT_CHANGED,
            (OrionCreateTaskPolicy(source).evaluate(request.task) as CreateTaskPolicyDecision.Unavailable).reason)
    }

    @Test fun invalidInputIsBoundedlyRejectedBeforeCreatingPolicyContext() {
        val result = active().captureCreateFromUser(command("\nsecret-fixture"))
        assertEquals(CreateTaskRejection.INVALID_TITLE_CHARACTER, (result as OrionTaskAdmissionResult.Invalid).reason)
        assertFalse(result.toString().contains("secret-fixture"))
    }

    @Test fun admissionDiagnosticsContainNoTitleCanonicalPayloadOrIds() {
        val input = command("private-fixture")
        val result = active().captureCreateFromUser(input) as OrionTaskAdmissionResult.Admitted
        for (value in listOf(result, result.request, result.request.task)) {
            assertFalse(value.toString().contains(input.title))
            assertFalse(value.toString().contains(input.taskId.toString()))
        }
    }
}
