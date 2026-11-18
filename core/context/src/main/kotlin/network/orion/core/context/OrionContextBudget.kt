package network.orion.core.context

/** Trusted, model-bound tokenizer or proven upper bound, including all rendered framing tokens. */
fun interface OrionTokenCounter { fun countUpperBound(renderedPrompt: String): Int? }
enum class OrionContextPriority { SYSTEM_POLICY, REQUEST, SKILL_REQUIRED, SAFETY, MEMORY, CONVERSATION, SECONDARY }
class OrionContextBlock(val priority: OrionContextPriority, val text: String, val required: Boolean) {
    override fun toString() = "OrionContextBlock(<redacted>)"
}
class OrionBudgetedContext(val prompt: String, val promptTokenUpperBound: Int, val omittedBlocks: Int) {
    override fun toString() = "OrionBudgetedContext(<redacted>)"
}

/** Selects whole blocks; never slices the rendered prompt or guesses tokens from character count. */
class OrionContextBudget {
    fun build(blocks: List<OrionContextBlock>, maxContextTokens: Int, reservedOutputTokens: Int,
        counter: OrionTokenCounter, render: (List<OrionContextBlock>) -> String): OrionBudgetedContext? {
        if (maxContextTokens <= 0 || reservedOutputTokens !in 1 until maxContextTokens || blocks.size > 64) return null
        if (blocks.sumOf { it.text.length.toLong() } > 65536 ||
            blocks.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() } > 65536) return null
        val ordered = blocks.sortedBy { it.priority.ordinal }
        val selected = ordered.filter { it.required }.toMutableList()
        fun evaluate(): OrionBudgetedContext? {
            val prompt = render(selected.sortedBy { it.priority.ordinal })
            if (prompt.length > 65536 || prompt.toByteArray(Charsets.UTF_8).size > 65536) return null
            val count = counter.countUpperBound(prompt) ?: return null
            if (count <= 0 || count.toLong() + reservedOutputTokens > maxContextTokens) return null
            return OrionBudgetedContext(prompt, count, blocks.size - selected.size)
        }
        var result = evaluate() ?: return null
        for (block in ordered.filterNot { it.required }) {
            selected.add(block)
            val candidate = evaluate()
            if (candidate == null) selected.removeAt(selected.lastIndex) else result = candidate
        }
        return result
    }
}
