package network.orion.core.queries.memories

import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SearchMemoriesTest {
    @Test fun queryProducesOnlyQuotedTermsAndRejectsUnboundedInput() {
        val request = SearchMemories("Café \"OR\" almoço* -café")
        assertEquals(listOf("cafe", "or", "almoco"), request.tokens)
        assertEquals("\"cafe\" AND \"or\" AND \"almoco\"", request.ftsExpression)
        for (invalid in listOf("", "*****", "a\nb", "é".repeat(129), (1..13).joinToString(" "))) {
            assertThrows(IllegalArgumentException::class.java) { SearchMemories(invalid) }
        }
    }

    @Test fun cursorCannotBeReusedForADifferentQuery() {
        val original = SearchMemories("café da manhã")
        val cursor = MemorySearchCursor(original.ftsExpression, Instant.EPOCH, 200, UUID.randomUUID())
        assertEquals(original.ftsExpression, SearchMemories("Cafe da manha", after = cursor).ftsExpression)
        assertThrows(IllegalArgumentException::class.java) { SearchMemories("café da tarde", after = cursor) }
    }

    @Test fun phraseAndFrequencyDominateAndClockRollbackKeepsRecencyBounded() {
        val search = SearchMemories("café forte")
        val at = Instant.ofEpochSecond(3_000_000)
        fun score(content: String, whenUpdated: Instant, importance: Double, confidence: Double) =
            OrionMemoryRanking.score(search, content, whenUpdated, at, importance, confidence)
        assertTrue(score("café forte", at.minusSeconds(1_000_000), 0.0, 0.8) >
            score("café mais forte", at, 1.0, 1.0))
        assertEquals(score("café forte", at, 0.5, 1.0), score("café forte", at.plusSeconds(60), 0.5, 1.0))
    }
}
