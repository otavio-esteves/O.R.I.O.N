package network.orion.core.commands.memories

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class MemoryRevisionPreparationTest {
    private val memory = UUID.randomUUID()
    private val replacement = UUID.randomUUID()
    private val claim = UUID.randomUUID()
    private val key = UUID.randomUUID()
    private val trace = UUID.randomUUID()

    @Test fun correctionBindsBothIdsTheOriginalVersionAndNormalizedReplacement() {
        val command = CorrectMemory(memory, 4, key, trace, replacement, "  cafe\u0301 ")
        val ready = prepare(command)
        assertEquals("memory.correct", ready.skillName)
        assertEquals("café", ready.replacement?.content)
        assertEquals(ready.canonicalNormalizedArguments, prepare(command.copy(content = "café")).canonicalNormalizedArguments)
        assertNotEquals(ready.canonicalNormalizedArguments, prepare(command.copy(expectedVersion = 5)).canonicalNormalizedArguments)
        assertNotEquals(ready.canonicalNormalizedArguments, prepare(command.copy(replacementId = UUID.randomUUID())).canonicalNormalizedArguments)
        assertNotEquals(ready.canonicalNormalizedArguments, prepare(command.copy(content = "chá")).canonicalNormalizedArguments)
        assertTrue(OrionMemoryStatusPreparer.prepare(command.copy(replacementId = memory)) is MemoryStatusPreparation.Rejected)
    }

    @Test fun reviewBindsTheIndividualClaimItsVersionAndTheDecision() {
        val command = ReviewMemoryClaim(memory, 4, key, trace, claim, 2, MemoryClaimDecision.CONFIRM)
        val ready = prepare(command)
        assertEquals("memory.claim.confirm", ready.skillName)
        assertEquals("memory/$memory/claim/$claim", ready.normalizedTarget)
        assertNotEquals(ready.canonicalNormalizedArguments, prepare(command.copy(expectedClaimVersion = 3)).canonicalNormalizedArguments)
        assertNotEquals(ready.canonicalNormalizedArguments, prepare(command.copy(claimId = UUID.randomUUID())).canonicalNormalizedArguments)
        assertNotEquals(ready.skillName, prepare(command.copy(decision = MemoryClaimDecision.REJECT)).skillName)
        assertTrue(OrionMemoryStatusPreparer.prepare(command.copy(expectedClaimVersion = Long.MAX_VALUE)) is MemoryStatusPreparation.Rejected)
    }

    @Test fun deleteRetainsItsExistingCanonicalV1Contract() {
        val ready = prepare(ForgetMemory(memory, 4, key, trace))
        assertEquals("memory.delete", ready.skillName)
        assertEquals(1, ready.argumentSchemaVersion)
        assertEquals("{\"memoryId\":\"$memory\",\"expectedVersion\":4}", ready.canonicalNormalizedArguments)
    }

    private fun prepare(command: MemoryStatusCommand) =
        (OrionMemoryStatusPreparer.prepare(command) as MemoryStatusPreparation.Ready).change
}
