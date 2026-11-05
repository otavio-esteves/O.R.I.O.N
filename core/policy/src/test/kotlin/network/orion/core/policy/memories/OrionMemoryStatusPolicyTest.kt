package network.orion.core.policy.memories

import java.util.UUID
import network.orion.core.commands.memories.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.policy.tasks.OrionPolicyEvidence
import network.orion.core.skills.metadata.memories.OrionCreateMemoryDescriptor
import org.junit.Assert.*
import org.junit.Test

class OrionMemoryStatusPolicyTest {
    private val id=UUID.randomUUID()
    private fun change(version:Long=0)=(OrionMemoryStatusPreparer.prepare(ForgetMemory(id,version,id,id)) as MemoryStatusPreparation.Ready).change
    private fun context(input:ValidatedMemoryStatusChange=change(),presence:OrionPolicyEvidence=OrionPolicyEvidence.SATISFIED)=
        MemoryStatusPolicyContext(id,MemoryStatusPolicyInputBinding.from(input),0,OrionProcessRole.MAIN,
            MemoryStatusAdmissionOrigin.EXPLICIT_USER_REQUEST,OrionPolicyEvidence.SATISFIED,presence,
            mapOf(OrionCreateMemoryDescriptor.WRITE_CAPABILITY to OrionPolicyEvidence.SATISFIED))
    @Test fun destructiveForgetStillNeedsConfirmationAndNeverGrantsExecution() {
        var reads=0
        val context=context()
        val decision=OrionMemoryStatusPolicy { reads++; MemoryStatusPolicyContextRead.Available(context) }.evaluate(change())
        assertTrue(decision is MemoryStatusPolicyDecision.NeedsConfirmation)
        assertEquals(2,reads)
        assertEquals(OrionMemoryStatusPolicy.POLICY_VERSION,(decision as MemoryStatusPolicyDecision.NeedsConfirmation).policyVersion)
    }
    @Test fun missingSourceStaleVersionAndAbsentPresenceCannotAuthorizeForget() {
        assertTrue(OrionMemoryStatusPolicy().evaluate(change()) is MemoryStatusPolicyDecision.Unavailable)
        assertEquals(MemoryStatusPolicyReason.INPUT_MISMATCH,
            (OrionMemoryStatusPolicy { MemoryStatusPolicyContextRead.Available(context()) }.evaluate(change(1)) as MemoryStatusPolicyDecision.Denied).reason)
        assertEquals(MemoryStatusPolicyReason.USER_NOT_PRESENT,
            (OrionMemoryStatusPolicy { MemoryStatusPolicyContextRead.Available(context(presence=OrionPolicyEvidence.NOT_SATISFIED)) }.evaluate(change()) as MemoryStatusPolicyDecision.Denied).reason)
    }
}
