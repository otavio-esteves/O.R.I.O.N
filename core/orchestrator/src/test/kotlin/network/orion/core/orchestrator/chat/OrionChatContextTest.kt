package network.orion.core.orchestrator.chat

import java.util.UUID
import network.orion.core.context.OrionTokenCounter
import org.junit.Assert.*
import org.junit.Test

class OrionChatContextTest {
    private fun turn(user: Boolean, text: String) = OrionChatTurn(UUID.randomUUID(), user, text)
    private fun model(context: Int = 8192) = OrionChatModel("fixture", "test", context, 128, 1,
        OrionTokenCounter { it.toByteArray(Charsets.UTF_8).size })

    @Test fun selectedHistoryPreservesTheOrderOfAnOriginalFactAndItsCorrection() {
        val history = listOf(turn(true, "Meu nome é Ana"), turn(false, "Olá, Ana"),
            turn(true, "Correção: meu nome é Beatriz"))
        val prompt = OrionChatContext.build("Qual é meu nome?", history, model())!!.prompt
        val positions = history.map { prompt.indexOf(OrionStructuredResponse.quote(it.text)) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
        assertTrue(prompt.indexOf("Request:") > positions.last())
    }

    @Test fun extractRetainsOnlyEarlierUserSourcesAndArchivedTextStaysLocal() {
        val original = turn(true, "Memória do início")
        val archived = OrionChatTurn(UUID.randomUUID(), true, "Conteúdo arquivado", archiveId = UUID.randomUUID())
        val live = List(12) { turn(it % 2 == 0, "Mensagem atual $it") }
        val history = listOf(original, archived, turn(false, "Afirmação anterior do assistente")) + live
        val summary = checkNotNull(OrionSessionSummary.from(history, recentTurns = 12))
        assertEquals(listOf(original.id), summary.entries.map { it.id })
        assertEquals(network.orion.core.model.OrionMemoryOrigin.SYSTEM_DERIVED, summary.origin)
        val prompt = OrionChatContext.build("Continue", history, model())!!.prompt
        assertTrue(prompt.contains("Memória do início"))
        assertFalse(prompt.contains("Conteúdo arquivado"))
        assertFalse(prompt.contains("Afirmação anterior do assistente"))
    }

    @Test fun tightBudgetSelectsNewestTurnsButRendersThemChronologically() {
        val recent = listOf(turn(true, "Correção recente"), turn(false, "Correção recebida"))
        val current = "Use a correção"
        val requiredAndRecent = OrionChatContext.build(current, recent, model())!!
        val boundedModel = model(requiredAndRecent.promptTokenUpperBound + 128)
        val result = OrionChatContext.build(current,
            listOf(turn(true, "informação antiga ".repeat(40))) + recent, boundedModel)!!
        assertFalse(result.prompt.contains("informação antiga"))
        assertTrue(result.prompt.indexOf("Correção recente") < result.prompt.indexOf("Correção recebida"))
        assertEquals(1, result.omittedBlocks)
        assertTrue(result.promptTokenUpperBound + boundedModel.reservedOutputTokens <= boundedModel.maxContextTokens)
    }
}
