package network.orion.core.context

import org.junit.Assert.*
import org.junit.Test

class OrionContextBudgetTest {
    private val budget = OrionContextBudget()
    private val counter = OrionTokenCounter { it.toByteArray(Charsets.UTF_8).size }
    @Test fun mandatoryBlocksAndFramingAreCountedAndNeverTruncated() {
        val blocks = listOf(OrionContextBlock(OrionContextPriority.REQUEST, "request", true),
            OrionContextBlock(OrionContextPriority.SYSTEM_POLICY, "rules", true))
        val ready = budget.build(blocks, 64, 8, counter) { it.joinToString("|") { b -> b.text } }!!
        assertEquals("rules|request", ready.prompt)
        assertEquals(13, ready.promptTokenUpperBound)
        assertNull(budget.build(blocks, 20, 8, counter) { "<" + it.joinToString("|") { b -> b.text } + ">" })
    }
    @Test fun dropsWholeOptionalBlocksInNormativeOrder() {
        val blocks = OrionContextPriority.entries.reversed().map { OrionContextBlock(it, it.ordinal.toString(), it.ordinal < 4) }
        val ready = budget.build(blocks, 7, 2, counter) { it.joinToString("") { b -> b.text } }!!
        assertEquals("01234", ready.prompt)
        assertEquals(2, ready.omittedBlocks)
    }
    @Test fun unknownOrInvalidCountsFailClosed() {
        val blocks = listOf(OrionContextBlock(OrionContextPriority.REQUEST, "hello", true))
        assertNull(budget.build(blocks, 20, 2, OrionTokenCounter { null }) { "hello" })
        assertNull(budget.build(blocks, 20, 2, OrionTokenCounter { -1 }) { "hello" })
    }
    @Test fun byteBoundIsNotCharacterTruncation() {
        assertNull(budget.build(listOf(OrionContextBlock(OrionContextPriority.REQUEST, "é".repeat(40000), true)),
            100000, 1, counter) { "small" })
    }
}
