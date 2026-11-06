package network.orion.app.memories

import network.orion.core.policy.tasks.OrionPolicyEvidence
import java.util.UUID
import java.util.concurrent.CancellationException
import network.orion.core.commands.memories.CreateMemory
import network.orion.core.commands.memories.CreateMemoryRejection
import network.orion.core.commands.memories.ForgetMemory
import network.orion.core.commands.memories.MemoryStatusRejection
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.policy.memories.*
import org.junit.Assert.*
import org.junit.Test

class OrionMemoryAdmissionOwnerTest {
    private val barrier = StartupBarrier()
    private var capability = OrionPolicyEvidence.SATISFIED
    private var presence = OrionPolicyEvidence.SATISFIED
    private val owner = OrionMemoryAdmissionOwner(OrionProcessRole.MAIN, barrier, { capability }, { presence })
    private fun command(content: String = "Revisar orçamento") = CreateMemory(UUID.randomUUID(), content, UUID.randomUUID(), UUID.randomUUID())
    private fun active(owner: OrionMemoryAdmissionOwner = this.owner) = owner.openUiChannel().apply {
        onResumed(true); onWindowFocus(true)
    }
    private fun admitted(channel: OrionMemoryAdmissionOwner.UiChannel = active(), command: CreateMemory = command()) =
        (channel.captureCreateFromUser(command) as OrionMemoryAdmissionResult.Admitted).request
    private fun openBarrier() = barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.READY)

    @Test fun recoveryInvalidationRevokesPreviouslyCapturedAdmissionEvenWithUnchangedUi() {
        openBarrier()
        val channel = active()
        val request = admitted(channel)
        assertTrue(request.evaluate() is CreateMemoryPolicyDecision.NeedsConfirmation)
        owner.invalidateAdmissions()
        assertTrue(request.evaluate() is CreateMemoryPolicyDecision.Unavailable)
        assertTrue(admitted(channel).evaluate() is CreateMemoryPolicyDecision.NeedsConfirmation)
    }

    @Test fun explicitMainGestureBindsExactInputAndStillRequiresConfirmation() {
        openBarrier()
        val request = admitted()
        assertTrue(request.evaluate() is CreateMemoryPolicyDecision.NeedsConfirmation)
        val other = admitted(command = command())
        assertEquals(CreateMemoryPolicyDenial.ADMISSION_INPUT_MISMATCH,
            (OrionCreateMemoryPolicy(other.source).evaluate(request.memory) as CreateMemoryPolicyDecision.Denied).reason)
    }

    @Test fun matchingProducerIdsAndForegroundAloneCannotAdmitAnAuxiliaryProcess() {
        for (role in OrionProcessRole.entries.filter { it != OrionProcessRole.MAIN }) {
            val auxiliary = OrionMemoryAdmissionOwner(role, barrier, { capability }, { presence })
            assertEquals(OrionMemoryAdmissionResult.Unavailable, active(auxiliary).captureCreateFromUser(command()))
        }
    }

    @Test fun defaultPausedUnfocusedAndClosedChannelsCannotCaptureGestures() {
        val channel = owner.openUiChannel()
        assertEquals(OrionMemoryAdmissionResult.Unavailable, channel.captureCreateFromUser(command()))
        channel.onResumed(true)
        assertEquals(OrionMemoryAdmissionResult.Unavailable, channel.captureCreateFromUser(command()))
        channel.onWindowFocus(true)
        channel.close()
        assertEquals(OrionMemoryAdmissionResult.Unavailable, channel.captureCreateFromUser(command()))
    }

    @Test fun absentOrUnknownDevicePresenceCannotAuthenticateAGesture() {
        val channel = active()
        for (evidence in listOf(OrionPolicyEvidence.NOT_SATISFIED, OrionPolicyEvidence.UNAVAILABLE)) {
            presence = evidence
            assertEquals(OrionMemoryAdmissionResult.Unavailable, channel.captureCreateFromUser(command()))
        }
    }

    @Test fun unavailableDeviceServiceFailsClosedWithoutExceptionText() {
        val unavailable = OrionMemoryAdmissionOwner(OrionProcessRole.MAIN, barrier, { capability }, {
            error("synthetic-private-fixture")
        })
        val result = active(unavailable).captureCreateFromUser(command())
        assertEquals(OrionMemoryAdmissionResult.Unavailable, result)
        assertFalse(result.toString().contains("synthetic-private-fixture"))
    }

    @Test fun cancellationIsNeverConvertedToAdmission() {
        val cancelled = OrionMemoryAdmissionOwner(OrionProcessRole.MAIN, barrier, { capability }, { throw CancellationException() })
        assertThrows(CancellationException::class.java) { active(cancelled).captureCreateFromUser(command()) }
    }

    @Test fun closedBarrierAdmitsOnlyInputAndPolicyStillGatesIt() {
        val request = admitted()
        assertEquals(CreateMemoryPolicyDenial.STARTUP_GATED, (request.evaluate() as CreateMemoryPolicyDecision.Denied).reason)
        assertFalse(barrier.isOpen())
    }

    @Test fun missingFunctionalCapabilityNeverBecomesAPositivePolicy() {
        openBarrier()
        val request = admitted()
        capability = OrionPolicyEvidence.NOT_SATISFIED
        assertEquals(CreateMemoryPolicyDenial.CAPABILITY_UNAVAILABLE, (request.evaluate() as CreateMemoryPolicyDecision.Denied).reason)
        capability = OrionPolicyEvidence.UNAVAILABLE
        assertTrue(request.evaluate() is CreateMemoryPolicyDecision.Unavailable)
    }

    @Test fun pauseResumeAbaNeverRevivesAnOldAdmission() {
        openBarrier()
        val channel = active()
        val request = admitted(channel)
        channel.onResumed(false); channel.onResumed(true)
        assertTrue(request.evaluate() is CreateMemoryPolicyDecision.Unavailable)
        assertTrue(admitted(channel).evaluate() is CreateMemoryPolicyDecision.NeedsConfirmation)
    }

    @Test fun focusLossAndReturnInvalidateEarlierInput() {
        openBarrier()
        val channel = active()
        val request = admitted(channel)
        channel.onWindowFocus(false); channel.onWindowFocus(true)
        assertTrue(request.evaluate() is CreateMemoryPolicyDecision.Unavailable)
    }

    @Test fun activityReplacementAndCloseRevokeOldChannels() {
        openBarrier()
        val channel = active()
        val request = admitted(channel)
        val replacement = active()
        assertTrue(request.evaluate() is CreateMemoryPolicyDecision.Unavailable)
        assertEquals(OrionMemoryAdmissionResult.Unavailable, channel.captureCreateFromUser(command()))
        val second = admitted(replacement)
        replacement.close()
        assertTrue(second.evaluate() is CreateMemoryPolicyDecision.Unavailable)
    }

    @Test fun changedStateBetweenPolicyReadsInvalidatesTheDecision() {
        openBarrier()
        val request = admitted()
        var reads = 0
        val source = CreateMemoryPolicyContextSource {
            if (reads++ == 1) capability = OrionPolicyEvidence.NOT_SATISFIED
            request.source.read()
        }
        assertEquals(CreateMemoryPolicyUnavailable.CONTEXT_CHANGED,
            (OrionCreateMemoryPolicy(source).evaluate(request.memory) as CreateMemoryPolicyDecision.Unavailable).reason)
    }

    @Test fun invalidInputIsBoundedlyRejectedBeforeCreatingPolicyContext() {
        val result = active().captureCreateFromUser(command("\nsecret-fixture"))
        assertEquals(CreateMemoryRejection.INVALID_CONTENT_CHARACTER, (result as OrionMemoryAdmissionResult.Invalid).reason)
        assertFalse(result.toString().contains("secret-fixture"))
    }

    @Test fun admissionDiagnosticsContainNoTitleCanonicalPayloadOrIds() {
        val input = command("private-fixture")
        val result = active().captureCreateFromUser(input) as OrionMemoryAdmissionResult.Admitted
        for (value in listOf(result, result.request, result.request.memory)) {
            assertFalse(value.toString().contains(input.content))
            assertFalse(value.toString().contains(input.memoryId.toString()))
        }
    }

    @Test fun explicitStatusGesturesRequireFreshBoundMainPolicyAndConfirmation() {
        openBarrier()
        val channel = active()
        val id = UUID.randomUUID(); val key = UUID.randomUUID(); val trace = UUID.randomUUID()
        val admitted = (channel.captureStatusFromUser(ForgetMemory(id, 0, key, trace)) as OrionMemoryStatusAdmissionResult.Admitted).request
        assertTrue(admitted.evaluate() is MemoryStatusPolicyDecision.NeedsConfirmation)
        val cancel = (channel.captureStatusFromUser(ForgetMemory(id, 1, key, trace)) as OrionMemoryStatusAdmissionResult.Admitted).request
        assertEquals(MemoryStatusPolicyReason.INPUT_MISMATCH,
            (OrionMemoryStatusPolicy(admitted.source).evaluate(cancel.change) as MemoryStatusPolicyDecision.Denied).reason)
    }

    @Test fun statusAdmissionCannotReviveAfterPauseOrFocusAba() {
        openBarrier()
        val channel = active()
        val input = ForgetMemory(UUID.randomUUID(), 0, UUID.randomUUID(), UUID.randomUUID())
        val first = (channel.captureStatusFromUser(input) as OrionMemoryStatusAdmissionResult.Admitted).request
        channel.onWindowFocus(false); channel.onWindowFocus(true)
        assertTrue(first.evaluate() is MemoryStatusPolicyDecision.Unavailable)
        val second = (channel.captureStatusFromUser(input) as OrionMemoryStatusAdmissionResult.Admitted).request
        channel.onResumed(false); channel.onResumed(true)
        assertTrue(second.evaluate() is MemoryStatusPolicyDecision.Unavailable)
    }

    @Test fun statusCaptureRejectsInvalidVersionAndUnavailableUiOrDevice() {
        val channel = active()
        val input = ForgetMemory(UUID.randomUUID(), Long.MAX_VALUE, UUID.randomUUID(), UUID.randomUUID())
        assertEquals(MemoryStatusRejection.INVALID_VERSION, (channel.captureStatusFromUser(input) as OrionMemoryStatusAdmissionResult.Invalid).reason)
        presence = OrionPolicyEvidence.UNAVAILABLE
        assertEquals(OrionMemoryStatusAdmissionResult.Unavailable, channel.captureStatusFromUser(input.copy(expectedVersion = 0)))
        presence = OrionPolicyEvidence.SATISFIED; channel.close()
        assertEquals(OrionMemoryStatusAdmissionResult.Unavailable, channel.captureStatusFromUser(input.copy(expectedVersion = 0)))
    }
}
