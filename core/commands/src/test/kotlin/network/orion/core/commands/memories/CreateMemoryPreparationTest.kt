package network.orion.core.commands.memories

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CreateMemoryPreparationTest {
    private val id=UUID.fromString("00000000-0000-0000-0000-000000000001")
    private fun command(content:String)=CreateMemory(id,content,id,id)
    private fun ready(content:String)=(OrionCreateMemoryPreparer.prepare(command(content)) as CreateMemoryPreparation.Ready).memory
    private fun rejected(content:String)=(OrionCreateMemoryPreparer.prepare(command(content)) as CreateMemoryPreparation.Rejected).reason
    @Test fun canonicalMaterialFixesUserDeclaredPersonalProvenanceAndEscapesContent() {
        assertEquals("""{"memoryId":"00000000-0000-0000-0000-000000000001","content":"x\"\\y/é😀","origin":"USER_DECLARED","sensitivity":"PERSONAL"}""",
            ready("x\"\\y/é😀").canonicalNormalizedArguments)
        assertEquals("memory/$id",ready("Lembrança").normalizedTarget)
    }
    @Test fun normalizationKeepsEquivalentStatementsButPreservesMaterialDifferences() {
        assertEquals(ready("é").canonicalNormalizedArguments,ready(" e\u0301 ").canonicalNormalizedArguments)
        assertNotEquals(ready("Fato").canonicalNormalizedArguments,ready("fato").canonicalNormalizedArguments)
        val first=ready("Lembrança")
        val trace=(OrionCreateMemoryPreparer.prepare(command("Lembrança").copy(correlationId=UUID.randomUUID(),idempotencyKey=UUID.randomUUID())) as CreateMemoryPreparation.Ready).memory
        assertEquals(first.canonicalNormalizedArguments,trace.canonicalNormalizedArguments)
    }
    @Test fun UTF8AndNFCExpansionAreBoundedBeforeSensitiveAllocation() {
        for (content in listOf("a".repeat(1024),"é".repeat(512),"😀".repeat(256))) assertEquals(content,ready(content).content)
        for (content in listOf("a".repeat(1025),"é".repeat(513),"😀".repeat(257),"\u0344".repeat(512)))
            assertEquals(CreateMemoryRejection.CONTENT_TOO_LARGE,rejected(content))
        assertEquals(CreateMemoryRejection.BLANK_CONTENT,rejected(" \u00a0"))
    }
    @Test fun MalformedUnicodeControlsAndLineSeparatorsCannotBecomeExecutableMaterial() {
        for (content in listOf("\ud800","x\udfff")) assertEquals(CreateMemoryRejection.INVALID_UNICODE,rejected(content))
        for (c in (0..31).map(Int::toChar)+(127..159).map(Int::toChar)+listOf('\u2028','\u2029'))
            assertEquals(CreateMemoryRejection.INVALID_CONTENT_CHARACTER,rejected("a${c}b"))
    }
    @Test fun forgetRejectsInvalidVersionsAndNeverAcceptsProducerAuthority() {
        for (version in listOf(-1L,Long.MAX_VALUE)) assertEquals(MemoryStatusRejection.INVALID_VERSION,
            (OrionMemoryStatusPreparer.prepare(ForgetMemory(id,version,id,id)) as MemoryStatusPreparation.Rejected).reason)
        val change=(OrionMemoryStatusPreparer.prepare(ForgetMemory(id,0,id,id)) as MemoryStatusPreparation.Ready).change
        assertEquals("memory.delete",change.skillName)
        assertEquals("""{"memoryId":"00000000-0000-0000-0000-000000000001","expectedVersion":0}""",change.canonicalNormalizedArguments)
    }
    @Test fun diagnosticsNeverIncludeContentOrCanonicalArguments() {
        val input=command("private-memory-fixture")
        val prepared=OrionCreateMemoryPreparer.prepare(input) as CreateMemoryPreparation.Ready
        for (value in listOf(input,prepared,prepared.memory)) {
            assertFalse(value.toString().contains(input.content)); assertFalse(value.toString().contains(id.toString()))
        }
    }
}
